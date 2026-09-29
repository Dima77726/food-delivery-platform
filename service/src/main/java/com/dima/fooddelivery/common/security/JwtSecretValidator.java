package com.dima.fooddelivery.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Не даёт приложению работать в боевом окружении с ключом по умолчанию.
 *
 * <p>Секрет в репозитории — то, чего в проекте быть не должно. Но и локальный запуск не должен
 * требовать возни с переменными окружения. Компромисс: дефолт есть, однако под любым профилем
 * кроме dev и test запуск с ним прерывается.
 *
 * <p>Проверка на {@link ApplicationReadyEvent}, а не в конструкторе: так в логе успевают
 * появиться остальные сообщения о старте, и причина падения читается однозначно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtSecretValidator {

    static final String DEV_DEFAULT_SECRET = "dev-only-insecure-secret-change-me-in-any-real-environment";

    /** HS256 требует ключ не короче 256 бит. Более короткий Nimbus отвергнет уже при выпуске токена. */
    static final int MIN_SECRET_LENGTH_BYTES = 32;

    private static final List<String> RELAXED_PROFILES = List.of("dev", "test", "local");

    private final SecurityProperties securityProperties;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        String secret = securityProperties.secret();

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET должен быть не короче " + MIN_SECRET_LENGTH_BYTES + " байт: этого требует HS256"
            );
        }

        if (!DEV_DEFAULT_SECRET.equals(secret)) {
            return;
        }

        if (hasRelaxedProfile()) {
            log.warn(
                    "Используется JWT-секрет по умолчанию. Это допустимо только локально — "
                            + "в любом другом окружении задайте переменную JWT_SECRET"
            );
            return;
        }

        throw new IllegalStateException(
                "Приложение запущено с JWT-секретом по умолчанию вне профилей "
                        + RELAXED_PROFILES + ". Задайте переменную окружения JWT_SECRET"
        );
    }

    private boolean hasRelaxedProfile() {
        String[] activeProfiles = environment.getActiveProfiles();

        if (activeProfiles.length == 0) {
            // Профиль не задан — это локальный запуск разработчика.
            return true;
        }

        return Arrays.stream(activeProfiles).anyMatch(RELAXED_PROFILES::contains);
    }
}
