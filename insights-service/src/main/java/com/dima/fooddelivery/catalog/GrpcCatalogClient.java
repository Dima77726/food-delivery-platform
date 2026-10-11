package com.dima.fooddelivery.catalog;

import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetRestaurantRequest;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link CatalogClient} поверх gRPC: дедлайн на каждый вызов, NOT_FOUND — «нет такого»,
 * остальное — 503. Устроен так же, как клиенты сервиса заказов и прав на рестораны.
 */
@Slf4j
@Component
public class GrpcCatalogClient implements CatalogClient {

    private final CatalogServiceGrpc.CatalogServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcCatalogClient(
            GrpcChannelFactory channels,
            @Value("${app.grpc.catalog.deadline:PT2S}") Duration deadline
    ) {
        this(channels.createChannel("catalog"), deadline);
    }

    /** Для тестов: канал к серверу в памяти процесса. */
    GrpcCatalogClient(Channel channel, Duration deadline) {
        this.stub = CatalogServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    @Override
    public Optional<RestaurantInfo> findRestaurant(Long restaurantId) {
        try {
            com.dima.fooddelivery.grpc.catalog.v1.RestaurantInfo info = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getRestaurant(GetRestaurantRequest.newBuilder().setRestaurantId(restaurantId).build());

            return Optional.of(new RestaurantInfo(info.getId(), info.getName(), info.getCity(), info.getActive()));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }

            log.error("Не удалось прочитать ресторан: каталог ответил {}", exception.getStatus());

            throw new UpstreamUnavailableException("Каталог недоступен, повторите позже", exception);
        }
    }
}
