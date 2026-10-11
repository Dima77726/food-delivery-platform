package com.dima.fooddelivery.order;

import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.grpc.order.v1.DeliveryView;
import com.dima.fooddelivery.grpc.order.v1.GetDeliveryRequest;
import com.dima.fooddelivery.grpc.order.v1.GetOrderRequest;
import com.dima.fooddelivery.grpc.order.v1.ListOrdersAfterRequest;
import com.dima.fooddelivery.grpc.order.v1.ListOrdersAfterResponse;
import com.dima.fooddelivery.grpc.order.v1.OrderItemView;
import com.dima.fooddelivery.grpc.order.v1.OrderQueryServiceGrpc;
import com.dima.fooddelivery.grpc.order.v1.OrderView;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * gRPC-клиент к сервису заказов против сервера в памяти процесса: разбор ответа, перевод
 * NOT_FOUND в «нет такого» и сбоя — в 503.
 */
class GrpcOrderClientTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void shutdown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    @Test
    void shouldMapOrderWithItems() throws IOException {
        Order order = clientFor(new FakeOrders(false)).requireOrder(10L);

        assertAll(
                () -> assertEquals(10L, order.id()),
                () -> assertEquals(7L, order.customerId()),
                () -> assertEquals("DELIVERED", order.status()),
                () -> assertEquals(new BigDecimal("900.00"), order.totalAmount()),
                () -> assertEquals(1, order.items().size()),
                () -> assertEquals("Пицца", order.items().get(0).menuItemName())
        );
    }

    @Test
    void shouldListOrdersAfterCursor() throws IOException {
        List<Order> orders = clientFor(new FakeOrders(false)).findAfter(5L, 10);

        assertEquals(List.of(10L), orders.stream().map(Order::id).toList());
    }

    @Test
    void shouldTurnNotFoundIntoResourceNotFound() throws IOException {
        assertThrows(ResourceNotFoundException.class, () -> clientFor(new FakeOrders(false)).requireOrder(404L));
    }

    @Test
    void shouldTurnServerFailureIntoUpstreamUnavailable() throws IOException {
        GrpcOrderClient client = clientFor(new FakeOrders(true));

        assertThrows(UpstreamUnavailableException.class, () -> client.requireOrder(10L));
        assertThrows(UpstreamUnavailableException.class, () -> client.findAfter(0L, 10));
    }

    private GrpcOrderClient clientFor(FakeOrders service) throws IOException {
        String name = InProcessServerBuilder.generateName();

        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        resources.add(server::shutdownNow);
        resources.add(channel::shutdownNow);

        return new GrpcOrderClient(channel, Duration.ofSeconds(2));
    }

    private static class FakeOrders extends OrderQueryServiceGrpc.OrderQueryServiceImplBase {

        private final boolean failing;

        FakeOrders(boolean failing) {
            this.failing = failing;
        }

        @Override
        public void getOrder(GetOrderRequest request, StreamObserver<OrderView> responseObserver) {
            if (failing) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            } else if (request.getOrderId() == 404L) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Заказ с id=404 не найден").asRuntimeException());
            } else {
                responseObserver.onNext(order());
                responseObserver.onCompleted();
            }
        }

        @Override
        public void listOrdersAfter(ListOrdersAfterRequest request, StreamObserver<ListOrdersAfterResponse> responseObserver) {
            if (failing) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }

            responseObserver.onNext(ListOrdersAfterResponse.newBuilder().addOrders(order()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getDelivery(GetDeliveryRequest request, StreamObserver<DeliveryView> responseObserver) {
            responseObserver.onNext(DeliveryView.newBuilder()
                    .setId(request.getDeliveryId())
                    .setOrderId(10L)
                    .setStatus("CREATED")
                    .build());
            responseObserver.onCompleted();
        }

        private static OrderView order() {
            return OrderView.newBuilder()
                    .setId(10L)
                    .setCustomerId(7L)
                    .setRestaurantId(3L)
                    .setStatus("DELIVERED")
                    .setTotalAmount("900.00")
                    .setCreatedAt("2026-10-11T18:30:00+03:00")
                    .addItems(OrderItemView.newBuilder()
                            .setMenuItemId(100L)
                            .setMenuItemName("Пицца")
                            .setQuantity(2)
                            .setPrice("450.00"))
                    .setDelivery(DeliveryView.newBuilder()
                            .setId(3L)
                            .setOrderId(10L)
                            .setCourierId(55L)
                            .setStatus("DELIVERED"))
                    .build();
        }
    }
}
