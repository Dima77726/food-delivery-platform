package com.dima.fooddelivery.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.Map;

/**
 * Кэш витрины в Redis.
 *
 * <p>Кэшируется только то, что читают анонимные посетители: список ресторанов и публичное меню.
 * Всё, что участвует в принятии решений — проверка «ресторан принимает заказы», цена при
 * добавлении в корзину, меню глазами владельца — читается из базы напрямую. Разница
 * принципиальная: устаревшая витрина это неудобство, а устаревшая цена или устаревший признак
 * «ресторан открыт» это неверно исполненный заказ.
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    /**
     * TTL — не основной механизм актуальности, а страховка.
     *
     * <p>Основной механизм — явное вытеснение при изменении меню или ресторана. Но полагаться
     * только на него нельзя: достаточно однажды добавить метод-мутатор и забыть повесить
     * {@code @CacheEvict}, чтобы данные протухли навсегда. TTL ограничивает ущерб от такой
     * ошибки минутами вместо бесконечности.
     */
    private static final Duration RESTAURANTS_TTL = Duration.ofMinutes(5);
    private static final Duration MENU_TTL = Duration.ofMinutes(10);

    /**
     * Сериализатор значений с записью типа.
     *
     * <p>Без информации о типе JSON-объект читается обратно как {@code LinkedHashMap},
     * и на первом же обращении к кэшу прилетает ClassCastException. Поэтому в документ
     * дописывается имя класса.
     *
     * <p>И вот здесь важно не срезать угол. У билдера есть {@code enableUnsafeDefaultTyping()},
     * который разрешает восстанавливать объект любого класса из classpath. Это классический
     * способ получить выполнение произвольного кода: тот, кто может записать значение в Redis,
     * подсовывает имя «удобного» класса, и десериализация сама вызывает его конструктор
     * или сеттер с нужным побочным эффектом.
     *
     * <p>{@link BasicPolymorphicTypeValidator} ограничивает восстановление нашими доменными
     * пакетами. Даже при доступе к Redis подставить чужой класс не выйдет.
     */
    private GenericJacksonJsonRedisSerializer jsonSerializer() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.dima.fooddelivery.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();

        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
    }

    @Bean
    RedisCacheManager cacheManager(org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                // Ключи строкой: иначе в redis-cli вместо restaurant-menu::42 видно
                // бинарный мусор, и отладка кэша превращается в гадание.
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // JSON, а не сериализация Java. У последней три беды: требует Serializable
                // на каждом кэшируемом типе, ломается при малейшем изменении класса
                // и нечитаема глазами.
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()))
                // null в кэш не кладём: иначе «ресторана нет» запомнится наравне с ответом,
                // и только что созданный ресторан будет считаться отсутствующим до конца TTL.
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        CacheNames.RESTAURANTS, defaults.entryTtl(RESTAURANTS_TTL),
                        CacheNames.RESTAURANT_MENU, defaults.entryTtl(MENU_TTL)
                ))
                // Нужно для метрик попаданий и промахов в Micrometer.
                .enableStatistics()
                .build();
    }

    /**
     * Падение Redis не должно ронять приложение.
     *
     * <p>Поведение по умолчанию — пробросить исключение вызывающему. Для кэша это неверно:
     * кэш ускоряет чтение, но не является источником данных. Недоступный Redis обязан
     * означать «работаем медленнее», а не «витрина отдаёт 500».
     *
     * <p>Ошибки логируются на WARN, а не проглатываются молча: сервис, который тихо потерял
     * кэш и работает вчетверо медленнее, — отдельный вид проблемы, и о ней нужно узнать.
     *
     * <p>Обратная сторона решения: сбой вытеснения тоже не заметят вызывающие. Ограничивает
     * ущерб как раз TTL.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Кэш недоступен при чтении, читаем из базы: cache={}, key={}, error={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Не удалось записать в кэш: cache={}, key={}, error={}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                // Самый неприятный из четырёх: данные изменились, а кэш об этом не узнал.
                log.warn("Не удалось вытеснить из кэша, данные могут быть устаревшими до истечения TTL: "
                        + "cache={}, key={}, error={}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Не удалось очистить кэш: cache={}, error={}", cache.getName(), exception.getMessage());
            }
        };
    }
}
