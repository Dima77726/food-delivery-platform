package com.dima.fooddelivery.order.domain;

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

    public boolean canBeCanceledByCustomer() {
        return this == CREATED;
    }

    public boolean canBeAcceptedByRestaurant() {
        return this == CREATED;
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

    public static OrderStatus fromDbValue(String dbValue) {
        for (OrderStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный тип события заказа из базы данных: " + dbValue);
    }
}
