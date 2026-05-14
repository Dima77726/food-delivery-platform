package com.dima.fooddelivery.order.domain;

public enum OrderEventType {

    ORDER_CREATED("ORDER_CREATED"),

    ORDER_CANCELED("ORDER_CANCELED"),

    ORDER_ACCEPTED("ORDER_ACCEPTED"),

    ORDER_COOKING_STARTED("ORDER_COOKING_STARTED");

    private final String dbValue;

    OrderEventType(String dbValue)
    {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public  static OrderEventType fromDbValue(String dbValue) {
        for (OrderEventType orderEventType : values()) {
            if (orderEventType.dbValue.equals(dbValue)) {
                return orderEventType;
            }
        }

        throw new IllegalArgumentException("Unknown order event type from database: " + dbValue);
    }
}
