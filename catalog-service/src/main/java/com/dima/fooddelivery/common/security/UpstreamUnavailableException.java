package com.dima.fooddelivery.common.security;

/**
 * Сервис, без которого запрос не решить, не ответил.
 *
 * <p>Отдельный тип, чтобы клиент получил 503, а не 403 и не 500. Отказать в доступе
 * из-за недоступного соседа было бы ложью — пользователь решил бы, что у него нет прав,
 * и пошёл бы разбираться не туда. А 500 говорит «у нас сломалось», хотя правильный ответ —
 * «попробуйте позже».
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
