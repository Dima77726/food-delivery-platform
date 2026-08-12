package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.user.domain.AppUser;
import com.dima.fooddelivery.user.domain.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Выпуск access-токена.
 *
 * <p>Проверкой подписи и срока занимается Spring Security на входе — своего парсера JWT
 * в проекте нет и быть не должно. Самописный разбор токена это классическое место,
 * где забывают проверить алгоритм подписи и принимают {@code alg: none}.
 *
 * <p>В токен кладётся ровно то, что нужно для авторизации: идентификатор пользователя
 * в {@code sub} и роли в claim {@code roles}. Ни имени, ни телефона: токен уходит клиенту,
 * лежит у него в браузере и легко читается — это подписанный, а не зашифрованный контейнер.
 */
@Component
@RequiredArgsConstructor
public class JwtIssuer {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;

    public IssuedToken issue(AppUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.accessTokenTtl());

        List<String> roles = user.roles().stream().map(UserRole::name).toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.id()))
                .claim(ROLES_CLAIM, roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(token, expiresAt, securityProperties.accessTokenTtl().toSeconds());
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
