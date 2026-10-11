package com.dima.fooddelivery.catalog.grpc;

import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.GetAvailableMenuItemRequest;
import com.dima.fooddelivery.grpc.catalog.v1.MenuItemSnapshot;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC-вход каталога по-настоящему: сервер на сетевом порту, вызов через канал Netty.
 *
 * <p>Не вызов метода реализации напрямую: так проверяется вся цепочка, которую увидит монолит, —
 * регистрация сервиса в сервере Spring gRPC, сериализация protobuf, статусы ошибок на проводе.
 *
 * <p>Без транзакции теста: сервер отвечает из своего потока и своей транзакции и не увидел бы
 * незакоммиченных данных. Изоляцию дают уникальные рестораны.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
class CatalogGrpcServiceIT extends AbstractIntegrationTest {

    @LocalGrpcPort
    private int grpcPort;

    private ManagedChannel channel;
    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
        catalog = CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        channel.shutdownNow();
    }

    @Test
    void shouldReturnActiveRestaurant() {
        Long restaurantId = testData.insertRestaurant(true);

        RestaurantSummary summary = catalog.getActiveRestaurant(
                GetActiveRestaurantRequest.newBuilder().setRestaurantId(restaurantId).build()
        );

        assertAll(
                () -> assertEquals(restaurantId, summary.getId()),
                () -> assertEquals("Moscow", summary.getCity())
        );
    }

    /** Закрытый ресторан — «есть, но нельзя», а не «нет такого»: клиенту это разные ошибки. */
    @Test
    void shouldAnswerFailedPreconditionForClosedRestaurant() {
        Long restaurantId = testData.insertRestaurant(false);

        assertStatus(Status.Code.FAILED_PRECONDITION, () -> catalog.getActiveRestaurant(
                GetActiveRestaurantRequest.newBuilder().setRestaurantId(restaurantId).build()
        ));
    }

    @Test
    void shouldAnswerNotFoundForUnknownRestaurant() {
        assertStatus(Status.Code.NOT_FOUND, () -> catalog.getActiveRestaurant(
                GetActiveRestaurantRequest.newBuilder().setRestaurantId(Long.MAX_VALUE).build()
        ));
    }

    /** Цена едет строкой и обязана доехать до копейки: ради этого она и не double. */
    @Test
    void shouldReturnMenuItemSnapshotWithExactPrice() {
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.10"));

        MenuItemSnapshot snapshot = catalog.getAvailableMenuItem(
                GetAvailableMenuItemRequest.newBuilder()
                        .setRestaurantId(restaurantId)
                        .setMenuItemId(itemId)
                        .build()
        );

        assertAll(
                () -> assertEquals(itemId, snapshot.getId()),
                () -> assertEquals(0, new BigDecimal("450.10").compareTo(new BigDecimal(snapshot.getPrice())))
        );
    }

    /**
     * Блюдо из чужого меню — «нет такого». Иначе в корзину пиццерии можно было бы положить
     * блюдо суши-бара по его цене.
     */
    @Test
    void shouldNotReturnItemOfAnotherRestaurant() {
        Long ownRestaurantId = testData.insertRestaurant();
        Long foreignRestaurantId = testData.insertRestaurant();
        Long foreignItemId = testData.insertMenuItem(foreignRestaurantId, new BigDecimal("100.00"));

        assertStatus(Status.Code.NOT_FOUND, () -> catalog.getAvailableMenuItem(
                GetAvailableMenuItemRequest.newBuilder()
                        .setRestaurantId(ownRestaurantId)
                        .setMenuItemId(foreignItemId)
                        .build()
        ));
    }

    @Test
    void shouldAnswerFailedPreconditionForUnavailableItem() {
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("100.00"), false);

        assertStatus(Status.Code.FAILED_PRECONDITION, () -> catalog.getAvailableMenuItem(
                GetAvailableMenuItemRequest.newBuilder()
                        .setRestaurantId(restaurantId)
                        .setMenuItemId(itemId)
                        .build()
        ));
    }

    /**
     * Метка запроса из метаданных вызова попадает в MDC каталога: строка лога, которую пишет
     * сервис, обрабатывая вызов монолита, несёт ту же метку, что и запрос пользователя
     * в монолите. Без этого цепочка запроса рвалась бы на каждом синхронном вызове.
     */
    @Test
    void shouldCarryCorrelationIdFromMetadataIntoLogs(CapturedOutput output) {
        Long restaurantId = testData.insertRestaurant(false);
        String correlationId = "grpc-trace-" + restaurantId;

        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER), correlationId);

        assertStatus(Status.Code.FAILED_PRECONDITION, () -> catalog
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .getActiveRestaurant(GetActiveRestaurantRequest.newBuilder().setRestaurantId(restaurantId).build()));

        // Каталог пишет предупреждение о закрытом ресторане внутри обработки вызова.
        String warning = output.getOut().lines()
                .filter(line -> line.contains("Ресторан недоступен для заказов: restaurantId=" + restaurantId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("строка лога о закрытом ресторане не найдена"));

        assertTrue(warning.contains(correlationId), "метка вызова обязана попасть в строку лога: " + warning);
    }

    private void assertStatus(Status.Code expected, Runnable call) {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call::run);

        assertEquals(expected, error.getStatus().getCode(), "неожиданный статус: " + error.getStatus());
    }
}
