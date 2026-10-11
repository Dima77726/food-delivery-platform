package com.dima.fooddelivery.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Выпускает токены так же, как монолит: HS256, издатель, {@code sub} — числовой идентификатор,
 * {@code roles} — список ролей.
 *
 * <p>Регистрироваться через монолит каталогу в тестах негде, да и незачем: проверяется,
 * что каталог правильно понимает токен, а выпуск — забота монолита и его тестов.
 * Форма токена — общий контракт, и совпадение формы здесь и там — ровно то, что проверяется.
 */
public class TestJwt {

    public static final String ISSUER = "food-delivery-platform";

    /** Идентификаторы пользователей далеко от тех, что выдаёт база: пользователей здесь нет вовсе. */
    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private final NimbusJwtEncoder encoder;

    public TestJwt(String secret) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
    }

    public long newUserId() {
        return USER_IDS.incrementAndGet();
    }

    public String tokenFor(long userId, String... roles) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .subject(String.valueOf(userId))
                .claim("roles", List.of(roles))
                .build();

        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Новый пользователь с ролями — токен для него. */
    public String newUserToken(String... roles) {
        return tokenFor(newUserId(), roles);
    }
}
