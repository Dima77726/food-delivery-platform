package com.dima.fooddelivery.catalog.grpc;

import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * gRPC-сервер каталога под взаимным TLS — так, как он работает в compose.
 *
 * <p>Проверяется не «TLS включён», а то, ради чего он включён: сервер знает, кто к нему пришёл.
 * Монолит ({@code CN=core}) получает ответ. Посторонний с настоящим сертификатом от того же CA
 * получает PERMISSION_DENIED — сертификат подлинный, но вызывать каталог ему не положено.
 * Без сертификата не проходит даже рукопожатие.
 *
 * <p>Сертификаты — тестовые, из {@code src/test/resources/grpc-tls}: свой CA, выпущенный
 * только для этих тестов, ключи которого нигде больше не используются.
 */
@ActiveProfiles("grpc-tls")
@TestPropertySource(properties = {
        "GRPC_TLS_CERTIFICATE=classpath:grpc-tls/catalog-service.crt",
        "GRPC_TLS_PRIVATE_KEY=classpath:grpc-tls/catalog-service.key",
        "GRPC_TLS_CA=classpath:grpc-tls/ca.crt"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CatalogGrpcMtlsIT extends AbstractIntegrationTest {

    @LocalGrpcPort
    private int grpcPort;

    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void closeChannels() {
        channels.forEach(ManagedChannel::shutdownNow);
    }

    @Test
    void shouldServeMonolithPresentingItsCertificate() throws IOException {
        Long restaurantId = testData.insertRestaurant(true);

        RestaurantSummary summary = catalogAs("core").getActiveRestaurant(request(restaurantId));

        assertEquals(restaurantId, summary.getId());
    }

    /** Сертификат подлинный, подписан нашим CA, — но этому сервису каталог не положен. */
    @Test
    void shouldDenyServiceThatIsNotAllowed() throws IOException {
        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> catalogAs("intruder").getActiveRestaurant(request(1L))
        );

        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
    }

    /** Без клиентского сертификата сервер обрывает рукопожатие: до кода сервиса дело не доходит. */
    @Test
    void shouldRejectClientWithoutCertificate() throws IOException {
        SslContextBuilder tls = GrpcSslContexts.forClient().trustManager(resource("ca.crt"));
        CatalogServiceGrpc.CatalogServiceBlockingStub anonymous = stub(tls);

        StatusRuntimeException error = assertThrows(
                StatusRuntimeException.class,
                () -> anonymous.getActiveRestaurant(request(1L))
        );

        assertEquals(Status.Code.UNAVAILABLE, error.getStatus().getCode(), "неожиданный статус: " + error.getStatus());
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub catalogAs(String service) throws IOException {
        SslContextBuilder tls = GrpcSslContexts.forClient()
                .keyManager(resource(service + ".crt"), resource(service + ".key"))
                .trustManager(resource("ca.crt"));

        return stub(tls);
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub stub(SslContextBuilder tls) throws IOException {
        // Подключение к localhost: он есть в SAN серверного сертификата, и проверка имени проходит.
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", grpcPort)
                .sslContext(tls.build())
                .build();
        channels.add(channel);

        return CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private InputStream resource(String name) throws IOException {
        return new ClassPathResource("grpc-tls/" + name).getInputStream();
    }

    private GetActiveRestaurantRequest request(Long restaurantId) {
        return GetActiveRestaurantRequest.newBuilder().setRestaurantId(restaurantId).build();
    }
}
