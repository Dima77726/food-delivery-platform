package com.dima.fooddelivery.common.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание API для Swagger UI.
 *
 * <p>Схема безопасности объявлена здесь один раз, а контроллеры ссылаются на неё
 * по имени через {@code @SecurityRequirement(name = "bearer-jwt")}. Благодаря этому
 * в Swagger UI появляется кнопка Authorize, и защищённые эндпоинты можно дёргать
 * прямо из браузера, вставив токен из {@code /api/v1/auth/login}.
 */
@Configuration
public class OpenApiConfig {

    static final String SECURITY_SCHEME_NAME = "bearer-jwt";

    @Bean
    OpenAPI foodDeliveryOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Food Delivery — Insights Service API")
                        .version("v1")
                        .description("""
                                Отзывы, аналитика и рекомендации.

                                REST — как раньше. Для экранов клиента есть GraphQL: POST /graphql,
                                песочница — /graphiql, схема — GET /graphql/schema. Одним запросом
                                берётся ровно то, что нужно экрану: ресторан, рейтинг, отзывы
                                и похожие блюда.
                                """))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
