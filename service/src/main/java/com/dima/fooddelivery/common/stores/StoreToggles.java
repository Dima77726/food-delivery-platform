package com.dima.fooddelivery.common.stores;

/**
 * Выключатели пяти дополнительных хранилищ.
 *
 * <p>Каждое из них тяжёлое: Cassandra и Elasticsearch поднимаются десятками секунд и просят
 * гигабайт памяти каждое. Держать их запущенными ради модуля, который сегодня не трогают,
 * бессмысленно, поэтому по умолчанию все пять выключены — и приложение стартует ровно так же,
 * как стартовало до их появления.
 *
 * <p>Выключенное хранилище означает буквально ноль бинов: ни клиента, ни планировщика,
 * ни потребителя Kafka, ни контроллера. Не «бины есть, но кидают ошибку», а именно ноль —
 * иначе выключатель приходилось бы проверять в каждом методе, и однажды его бы забыли.
 * Отсюда и форма: константы для {@code @ConditionalOnProperty}, а не поля в
 * {@code @ConfigurationProperties}.
 *
 * <p>Обратная сторона правила: включённое хранилище обязано быть доступно. Схему свою
 * инициализатор создаёт на старте и при недоступном сервере роняет приложение. Это
 * сознательный выбор в пользу громкого отказа: молча стартовавший сервис с наполовину
 * рабочим API нашли бы сильно позже и по жалобе пользователя.
 *
 * <p>Значения приезжают из переменных окружения {@code STORE_*_ENABLED} — см. .env.example.
 */
public final class StoreToggles {

    /** Отзывы о заказах. MongoDB. */
    public static final String MONGO = "app.stores.mongo.enabled";

    /** Трек курьера. Cassandra. */
    public static final String CASSANDRA = "app.stores.cassandra.enabled";

    /** Рекомендации. Neo4j. */
    public static final String NEO4J = "app.stores.neo4j.enabled";

    /** Аналитика заказов. ClickHouse. */
    public static final String CLICKHOUSE = "app.stores.clickhouse.enabled";

    /** Поиск по витрине. Elasticsearch. */
    public static final String ELASTICSEARCH = "app.stores.elasticsearch.enabled";

    private StoreToggles() {
    }
}
