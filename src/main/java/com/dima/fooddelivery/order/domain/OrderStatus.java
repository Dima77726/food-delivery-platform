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

    public static OrderStatus fromDbValue(String dbValue) {
        for (OrderStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown order status from database: " + dbValue);
    }
}
