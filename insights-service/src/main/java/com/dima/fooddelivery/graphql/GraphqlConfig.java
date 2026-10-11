package com.dima.fooddelivery.graphql;

import com.dima.fooddelivery.common.exception.BusinessRuleViolationException;
import com.dima.fooddelivery.common.exception.ResourceNotFoundException;
import com.dima.fooddelivery.common.exception.UpstreamUnavailableException;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;

import java.time.format.DateTimeParseException;

/**
 * Настройки GraphQL: предел глубины запроса и перевод исключений в ошибки GraphQL.
 */
@Slf4j
@Configuration
public class GraphqlConfig {

    /**
     * Предел вложенности. Одним запросом в GraphQL можно попросить сколько угодно уровней,
     * и без предела один запрос становится сколь угодно дорогим. Глубже лимита запрос
     * отклоняется до выполнения.
     */
    @Bean
    MaxQueryDepthInstrumentation maxQueryDepthInstrumentation(@Value("${app.graphql.max-query-depth:6}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    /**
     * Ошибка поля — ошибка этого поля, а не всего ответа: GraphQL вернёт остальные поля
     * и список {@code errors} с путём к сломанному. Тип ошибки ({@code extensions.classification})
     * говорит клиенту то же, что HTTP-код в REST: нет такого, нельзя, войдите.
     *
     * <p>Неизвестные исключения адаптер оставляет фреймворку — тот отдаёт INTERNAL_ERROR без
     * подробностей, а подробности остаются в логе.
     */
    @Bean
    DataFetcherExceptionResolverAdapter insightsExceptionResolver() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
                ErrorType type = switch (exception) {
                    case ResourceNotFoundException ignored -> ErrorType.NOT_FOUND;
                    case AccessDeniedForResourceException ignored -> ErrorType.FORBIDDEN;
                    case NotAuthenticatedException ignored -> ErrorType.UNAUTHORIZED;
                    case BusinessRuleViolationException ignored -> ErrorType.BAD_REQUEST;
                    case DateTimeParseException ignored -> ErrorType.BAD_REQUEST;
                    case ModuleDisabledException ignored -> ErrorType.INTERNAL_ERROR;
                    case UpstreamUnavailableException ignored -> ErrorType.INTERNAL_ERROR;
                    default -> null;
                };

                if (type == null) {
                    return null;
                }

                String message = exception instanceof DateTimeParseException
                        ? "Дата ожидается в формате ГГГГ-ММ-ДД"
                        : exception.getMessage();

                log.warn("GraphQL: поле {} отклонено: {}", environment.getExecutionStepInfo().getPath(), message);

                return GraphqlErrorBuilder.newError(environment)
                        .errorType(type)
                        .message(message)
                        .build();
            }
        };
    }
}
