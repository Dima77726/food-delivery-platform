package com.dima.fooddelivery.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки выпуска JWT.
 *
 * <p>Типизированные свойства вместо {@code @Value}: опечатка в имени ключа ловится при старте,
 * а не в момент первого логина.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record SecurityProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl
) {
}
