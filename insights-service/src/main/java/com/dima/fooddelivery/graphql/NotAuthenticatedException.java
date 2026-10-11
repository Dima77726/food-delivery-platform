package com.dima.fooddelivery.graphql;

/** Поле требует пользователя, а запрос пришёл без токена. */
public class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException(String message) {
        super(message);
    }
}
