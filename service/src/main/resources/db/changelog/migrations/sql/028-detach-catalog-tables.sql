-- Каталог переехал в catalog-service, и его таблицы больше не принадлежат монолиту.
--
-- Внешние ключи на restaurant и menu_item снимаются: настоящие рестораны и блюда теперь
-- живут в другой базе, и ссылаться на них констрейнтом невозможно. Оставь ключи — и каждый
-- ресторан, созданный уже в каталоге, не прошёл бы проверку при первом же заказе: в старой
-- таблице монолита его нет. Целостность между сервисами обеспечивает не база, а порядок
-- операций: корзина берёт идентификаторы только из ответа каталога.
--
-- Сами таблицы restaurant, menu_category и menu_item пока остаются. Из них единожды
-- переносятся данные в каталог (docker/migrations/catalog-from-monolith.sh), а после
-- переноса монолит их больше не читает и не пишет. Удалять их стоит отдельной миграцией,
-- когда перенос сделан во всех окружениях.

ALTER TABLE ${appSchema}.cart
    DROP CONSTRAINT IF EXISTS fk_cart_restaurant;

ALTER TABLE ${appSchema}.cart_item
    DROP CONSTRAINT IF EXISTS fk_cart_item_menu_item;

ALTER TABLE ${appSchema}.customer_order
    DROP CONSTRAINT IF EXISTS fk_customer_order_restaurant;

ALTER TABLE ${appSchema}.customer_order_item
    DROP CONSTRAINT IF EXISTS fk_customer_order_item_menu_item;

-- Права на ресторан принадлежат пользователю и остаются в монолите, а сам ресторан — нет.
ALTER TABLE ${appSchema}.restaurant_manager
    DROP CONSTRAINT IF EXISTS fk_restaurant_manager_restaurant;

-- Снимок названия блюда в позиции корзины.
--
-- Раньше корзина брала название join'ом с menu_item. Теперь меню в чужой базе, и join'ить
-- его нечем. Решение то же, что уже применено к цене: значение копируется в момент
-- добавления в корзину. Это правильнее и по смыслу — клиент видит в корзине то название,
-- которое выбрал, даже если ресторан потом переименует блюдо.
ALTER TABLE ${appSchema}.cart_item
    ADD COLUMN IF NOT EXISTS menu_item_name VARCHAR(255);

-- Существующие позиции получают название из меню, пока оно ещё доступно в этой базе.
UPDATE ${appSchema}.cart_item ci
SET menu_item_name = mi.name
FROM ${appSchema}.menu_item mi
WHERE mi.id = ci.menu_item_id
  AND ci.menu_item_name IS NULL;

-- Позиция без блюда в меню могла остаться только при ручном вмешательстве в базу. Отметка
-- лучше пустоты: NOT NULL ниже не даст оставить NULL, а терять позицию корзины нельзя.
UPDATE ${appSchema}.cart_item
SET menu_item_name = 'Блюдо #' || menu_item_id
WHERE menu_item_name IS NULL;

ALTER TABLE ${appSchema}.cart_item
    ALTER COLUMN menu_item_name SET NOT NULL;

COMMENT ON COLUMN ${appSchema}.cart_item.menu_item_name IS 'Название блюда на момент добавления в корзину; меню живёт в catalog-service';
