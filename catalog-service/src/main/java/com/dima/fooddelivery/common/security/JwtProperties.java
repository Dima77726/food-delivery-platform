package com.dima.fooddelivery.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры проверки JWT. Только проверки: токены выпускает монолит.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        String issuer
) {
}
