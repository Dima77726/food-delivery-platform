package com.dima.fooddelivery.recommendation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Догоняет граф рекомендаций по расписанию.
 *
 * <p>Планировщик вынесен из сервиса ровно по той же причине, что и в {@code OutboxPublishJob}:
 * свойство {@code projection-enabled} обязано гасить таймер, а не сам сервис. Тестам бин
 * сервиса нужен, чтобы вызвать проекцию вручную и точно знать, когда она произошла.
 *
 * <p>Условие включения перечисляет два свойства: работает задача только при включённом
 * хранилище и не выключенной проекции. {@code @ConditionalOnProperty} со списком имён
 * требует истинности всех перечисленных — то, что нужно.
 *
 * <p>За один запуск делается один проход, а не «догнать всё». Так задача остаётся короткой
 * и предсказуемой по времени: отставание в тысячу заказов рассосётся за несколько тактов,
 * а не займёт один такт на несколько минут, заблокировав планировщик.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = {"app.stores.neo4j.enabled", "app.stores.neo4j.projection-enabled"},
        havingValue = "true"
)
public class OrderGraphProjectionJob {

    private final RecommendationService recommendationService;

    @Scheduled(fixedDelayString = "${app.stores.neo4j.projection-interval:PT30S}")
    public void project() {
        int read = recommendationService.projectNextBatch();

        if (read > 0) {
            log.debug("Проекция графа: прочитано заказов {}", read);
        }
    }
}
