package com.dima.fooddelivery.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Не даёт приложению работать с непригодным ключом подписи токенов.
 *
 * <p>Значения по умолчанию у секрета нет: переменная JWT_SECRET обязательна, и её отсутствие
 * приложение замечает раньше — на разборе конфигурации. Здесь остаётся проверка длины:
 * переменная может быть задана и при этом оказаться слишком короткой для HS256.
 *
 * <p>Проверка на {@link ApplicationReadyEvent}, а не в конструкторе: так в логе успевают
 * появиться остальные сообщения о старте, и причина падения читается однозначно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtSecretValidator {

    /** HS256 требует ключ не короче 256 бит. Более короткий Nimbus отвергнет уже при выпуске токена. */
    static final int MIN_SECRET_LENGTH_BYTES = 32;

    private final SecurityProperties securityProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        String secret = securityProperties.secret();

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET должен быть не короче " + MIN_SECRET_LENGTH_BYTES + " байт: этого требует HS256"
            );
        }
    }
}
