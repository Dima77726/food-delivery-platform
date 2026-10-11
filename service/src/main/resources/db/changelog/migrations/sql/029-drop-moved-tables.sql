-- Удаление таблиц, переехавших в другие сервисы.
--
-- Уведомления живут в notification-service, рестораны и меню — в catalog-service,
-- пользователи, роли и права на рестораны — в identity-service. Данные перенесены
-- одноразовыми задачами прошлых релизов, монолит эти таблицы не читает и не пишет.
--
-- Порядок как в любой миграции с переездом данных: сначала релиз, который переносит
-- и перестаёт пользоваться, и только следующим релизом — удаление. Удали таблицы в одном
-- релизе с переносом, и откатиться назад было бы не к чему.
--
-- CASCADE не нужен и не используется: внешние ключи из оставшихся таблиц сняты миграцией 028,
-- а между собой эти таблицы удаляются в порядке зависимостей. Если бы на них ещё что-то
-- ссылалось, миграция упала бы — и это правильно: молча снести чужую связь хуже.

DROP TABLE IF EXISTS ${appSchema}.notification;

DROP TABLE IF EXISTS ${appSchema}.restaurant_manager;
DROP TABLE IF EXISTS ${appSchema}.app_user_role;
DROP TABLE IF EXISTS ${appSchema}.app_user;

DROP TABLE IF EXISTS ${appSchema}.menu_item;
DROP TABLE IF EXISTS ${appSchema}.menu_category;
DROP TABLE IF EXISTS ${appSchema}.restaurant;
