package com.dima.fooddelivery.search.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Перестраивает поисковый индекс по расписанию.
 *
 * <p>{@code fixedDelay} отсчитывается от конца предыдущего запуска, а не от его начала.
 * Для переиндексации это единственный правильный выбор: {@code fixedRate} на медленном
 * проходе запустил бы следующий, не дождавшись предыдущего, и два прохода начали бы
 * переписывать одни и те же документы наперегонки.
 *
 * <p>Первый запуск происходит сразу после старта приложения — задержка отсчитывается после
 * выполнения. Это удобно: индекс наполняется без ручного вмешательства, ещё до первого
 * поискового запроса.
 *
 * <p>Ошибка переиндексации не должна валить приложение и не должна останавливать расписание.
 * Планировщик Spring при исключении просто пропускает такт, но в логе оно тогда выглядит
 * как невнятный стектрейс из недр TaskScheduler, поэтому исключение перехватывается здесь -
 * с понятным сообщением о том, что именно не удалось.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = {"app.stores.elasticsearch.enabled", "app.stores.elasticsearch.reindex-enabled"},
        havingValue = "true"
)
public class SearchReindexJob {

    private final SearchService searchService;

    @Scheduled(fixedDelayString = "${app.stores.elasticsearch.reindex-interval:PT5M}")
    public void reindex() {
        try {
            searchService.reindexAll();
        } catch (RuntimeException exception) {
            log.error("Переиндексация витрины не удалась: {}", exception.getMessage(), exception);
        }
    }
}
