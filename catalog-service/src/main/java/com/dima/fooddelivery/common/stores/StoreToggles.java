package com.dima.fooddelivery.common.stores;

/**
 * Выключатели дополнительных хранилищ каталога. Сейчас оно одно — Elasticsearch под поиском.
 *
 * <p>Правило то же, что в монолите: выключенное хранилище означает ноль бинов — ни клиента,
 * ни планировщика, ни контроллера. Включённое обязано быть доступно: индекс создаётся
 * на старте, и при недоступном сервере сервис не поднимается.
 *
 * <p>Значение приезжает из переменной окружения {@code STORE_ELASTICSEARCH_ENABLED}.
 */
public final class StoreToggles {

    /** Поиск по витрине. Elasticsearch. */
    public static final String ELASTICSEARCH = "app.stores.elasticsearch.enabled";

    private StoreToggles() {
    }
}
