package com.dima.fooddelivery.order.domain;

/**
 * Статусы заказа и допустимые переходы.
 *
 * <pre>
 *   CREATED ──оплата──► PAID ──ресторан принял──► ACCEPTED ──► COOKING ──► READY_FOR_DELIVERY
 *      │                  │                                                        │
 *      └──── отмена ──────┘                                            курьер забрал│
 *                                                                                   ▼
 *                                                          DELIVERED ◄── IN_DELIVERY
 * </pre>
 *
 * <p>Появление модуля Payment сделало статус PAID обязательным шагом: до этого он был объявлен,
 * но ни один переход его не использовал. Ресторан теперь принимает только оплаченный заказ —
 * иначе кухня начинает работать за счёт заведения.
 *
 * <p>Отмена доступна клиенту до того, как ресторан взял заказ в работу. После ACCEPTED отмена
 * клиентом невозможна: продукты уже списаны. Возврат денег за отменённый оплаченный заказ —
 * задача модуля Payment.
 */
public enum OrderStatus {

    CREATED("CREATED"),
    PAID("PAID"),
    ACCEPTED("ACCEPTED"),
    COOKING("COOKING"),
    READY_FOR_DELIVERY("READY_FOR_DELIVERY"),
    IN_DELIVERY("IN_DELIVERY"),
    DELIVERED("DELIVERED"),
    CANCELED("CANCELED");

    private final String dbValue;

    OrderStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public boolean canBePaidByCustomer() {
        return this == CREATED;
    }

    public boolean canBeCanceledByCustomer() {
        return this == CREATED || this == PAID;
    }

    public boolean canBeAcceptedByRestaurant() {
        return this == PAID;
    }

    public boolean canStartCookingByRestaurant() {
        return this == ACCEPTED;
    }

    public boolean canBeMarkedReadyForDeliveryByRestaurant() {
        return this == COOKING;
    }

    public boolean canCreateDelivery() {
        return this == READY_FOR_DELIVERY;
    }

    public boolean canBeMovedToInDeliveryByCourier() {
        return this == READY_FOR_DELIVERY;
    }

    public boolean canBeCompletedByCourier() {
        return this == IN_DELIVERY;
    }

    /**
     * Терминальные статусы: из них нет ни одного перехода. Полезно для проверок в админке
     * и для отчётности.
     */
    public boolean isFinal() {
        return this == DELIVERED || this == CANCELED;
    }

    public static OrderStatus fromDbValue(String dbValue) {
        for (OrderStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный статус заказа из базы данных: " + dbValue);
    }
}
