package com.dima.fooddelivery.common.security;

import com.dima.fooddelivery.common.web.UserIdMdcFilter;
import com.dima.fooddelivery.user.domain.UserRole;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Конфигурация Spring Security.
 *
 * <p>Симметричная подпись HS256: один секрет и выпускает, и проверяет токены. Для монолита
 * этого достаточно. Асимметричная (RS256) понадобится, когда токены начнут проверять сервисы,
 * которым нельзя доверять выпуск — например, при переезде на микросервисы.
 *
 * <p>Сессий нет: {@code STATELESS}. Состояние пользователя целиком в токене, сервер его
 * не помнит. Отсюда же выключенный CSRF — защищаться нечему, cookie для аутентификации
 * не используются.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/restaurants",
            "/api/v1/restaurants/*",
            "/api/v1/restaurants/*/menu",
            "/api/v1/ping",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final SecurityProperties securityProperties;
    private final CurrentUser currentUser;

    /**
     * Цепочка для служебных эндпоинтов.
     *
     * <p>{@code @Order(1)} ставит её перед основной: иначе запросы к actuator дошли бы
     * до {@code anyRequest().authenticated()} и Prometheus получал бы 401.
     *
     * <p>Доступ открыт, и это безопасно ровно потому, что actuator слушает отдельный порт,
     * который в docker-compose не публикуется наружу. Защита здесь сетевая, а не прикладная —
     * у скрейпера всё равно нет способа обновлять JWT.
     *
     * <p>Если однажды порт придётся выставить наружу, эту цепочку нужно закрыть:
     * открытый {@code /actuator/metrics} рассказывает о нагрузке, версиях и внутренних
     * именах эндпоинтов больше, чем стоит показывать посторонним.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Регистрация и вход обязаны быть открыты, иначе войти невозможно.
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Витрину — список ресторанов и меню — видно без входа: это то,
                        // ради чего пользователь на платформу и приходит.
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(UserRole.ADMIN.name())
                        .anyRequest().authenticated()
                )
                // httpBasic намеренно не включён: единственный способ аутентификации — токен.
                // Иначе Boot поднял бы вход по логину user и паролю из лога при старте.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                // Строго после разбора токена: до него контекст безопасности пуст, и фильтр
                // получал бы анонима на каждом запросе. Метка запроса при этом проставляется
                // раньше — отдельным фильтром вне этой цепочки, чтобы её видели и логи
                // о неудачной аутентификации.
                .addFilterAfter(new UserIdMdcFilter(currentUser), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt, а не SHA/MD5: он намеренно медленный и солит пароль сам. Быстрый хеш здесь —
     * ошибка, потому что скорость играет на стороне того, кто перебирает.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey().getEncoded()));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Переводит claim {@code roles} в authorities Spring Security.
     *
     * <p>По умолчанию конвертер ищет claim {@code scope} и вешает префикс {@code SCOPE_}.
     * Нам нужен префикс {@code ROLE_}, иначе {@code hasRole('ADMIN')} не сработает —
     * и это молчаливый отказ в доступе, который тяжело искать.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(JwtIssuer.ROLES_CLAIM);
        authoritiesConverter.setAuthorityPrefix(UserRole.ROLE_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return converter;
    }

    /**
     * Ключ подписи с проверкой на месте.
     *
     * <p>Проверка длины есть и в {@link JwtSecretValidator}, но он срабатывает на
     * ApplicationReadyEvent, то есть уже после создания бинов. Пустой или короткий секрет
     * ломает приложение раньше — на {@code SecretKeySpec}, причём с сообщением
     * «Empty key», по которому невозможно догадаться, что дело в переменной окружения.
     *
     * <p>Так и случилось при первом запуске в docker-compose: запись
     * {@code JWT_SECRET: ${JWT_SECRET:-}} передавала пустую строку, значение по умолчанию
     * из application.yml не применялось, и приложение падало с невнятной ошибкой.
     */
    private SecretKeySpec secretKey() {
        String secret = securityProperties.secret();

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT-секрет пуст. Задайте переменную окружения JWT_SECRET либо не передавайте её вовсе, "
                            + "чтобы применилось значение по умолчанию для локальной разработки. "
                            + "Пустая строка значением по умолчанию не считается"
            );
        }

        if (secret.getBytes(StandardCharsets.UTF_8).length < JwtSecretValidator.MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "JWT-секрет короче " + JwtSecretValidator.MIN_SECRET_LENGTH_BYTES
                            + " байт: этого требует алгоритм HS256"
            );
        }

        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
