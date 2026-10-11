package com.dima.fooddelivery.catalog.grpc;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.grpc.catalog.v1.CatalogServiceGrpc;
import com.dima.fooddelivery.grpc.catalog.v1.GetActiveRestaurantRequest;
import com.dima.fooddelivery.grpc.catalog.v1.GetAvailableMenuItemRequest;
import com.dima.fooddelivery.grpc.catalog.v1.MenuItemSnapshot;
import com.dima.fooddelivery.grpc.catalog.v1.RestaurantSummary;
import com.dima.fooddelivery.menu.service.MenuService;
import com.dima.fooddelivery.restaurant.domain.Restaurant;
import com.dima.fooddelivery.restaurant.service.RestaurantService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * gRPC-вход в каталог для других сервисов.
 *
 * <p>Реализация тонкая: вся логика — в тех же {@link RestaurantService} и {@link MenuService},
 * которыми пользуется REST. Правило «закрытый ресторан не продаёт» не дублируется в двух
 * местах, а значит, не может разойтись между REST и gRPC.
 *
 * <p>Доменные исключения переводятся в статусы gRPC — так же, как {@code GlobalExceptionHandler}
 * переводит их в HTTP-коды. Клиент различает «нет такого» и «есть, но нельзя» по статусу,
 * не разбирая текст сообщения. Всё прочее уходит как INTERNAL: подробности внутренней ошибки
 * клиенту ни к чему, они остаются в логе каталога.
 *
 * <p>Аутентификации на этом входе нет, и это осознанное решение текущего этапа: gRPC-порт
 * не публикуется наружу и доступен только внутри сети сервисов. Следующий шаг — mTLS между
 * сервисами, чтобы сетевая граница перестала быть единственной защитой.
 */
@Service
@RequiredArgsConstructor
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final RestaurantService restaurantService;
    private final MenuService menuService;

    @Override
    public void getActiveRestaurant(
            GetActiveRestaurantRequest request,
            StreamObserver<RestaurantSummary> responseObserver
    ) {
        respond(responseObserver, () -> {
            Restaurant restaurant = restaurantService.requireActiveRestaurant(request.getRestaurantId());

            return RestaurantSummary.newBuilder()
                    .setId(restaurant.getId())
                    .setName(restaurant.getName())
                    .setCity(restaurant.getCity())
                    .build();
        });
    }

    @Override
    public void getAvailableMenuItem(
            GetAvailableMenuItemRequest request,
            StreamObserver<MenuItemSnapshot> responseObserver
    ) {
        respond(responseObserver, () -> {
            var item = menuService.requireAvailableItem(request.getRestaurantId(), request.getMenuItemId());

            return MenuItemSnapshot.newBuilder()
                    .setId(item.id())
                    .setName(item.name())
                    .setPrice(item.price().toPlainString())
                    .build();
        });
    }

    private <T> void respond(StreamObserver<T> responseObserver, Supplier<T> action) {
        T response;

        try {
            response = action.get();
        } catch (ResourceNotFoundException exception) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(exception.getMessage()).asRuntimeException());
            return;
        } catch (BusinessRuleViolationException exception) {
            responseObserver.onError(
                    Status.FAILED_PRECONDITION.withDescription(exception.getMessage()).asRuntimeException()
            );
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
