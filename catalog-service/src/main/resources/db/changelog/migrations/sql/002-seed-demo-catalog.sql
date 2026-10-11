-- Демонстрационная витрина для новых окружений: три ресторана с меню.
--
-- Раньше эти строки создавал монолит (его миграции 002 и 004), а каталог получал их
-- одноразовым переносом. Старые таблицы монолита удалены, переносить больше нечего — и
-- витрина для свежего окружения теперь рождается здесь, где ей и место.
--
-- Только в пустой каталог. В окружении, где перенос уже был или каталог живёт своей жизнью,
-- миграция ничего не делает: подмешать демо-рестораны к настоящим было бы хуже, чем не
-- создать их вовсе.
DO
$$
DECLARE
    pizza_house  BIGINT;
    burger_point BIGINT;
    sushi_time   BIGINT;
    category     BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM restaurant) THEN
        RETURN;
    END IF;

    INSERT INTO restaurant (name, description, city, is_active)
    VALUES ('Pizza House', 'Семейный ресторан с пиццей и пастой', 'Moscow', TRUE)
    RETURNING id INTO pizza_house;

    INSERT INTO restaurant (name, description, city, is_active)
    VALUES ('Burger Point', 'Бургеры, закуски и напитки', 'Saint Petersburg', TRUE)
    RETURNING id INTO burger_point;

    INSERT INTO restaurant (name, description, city, is_active)
    VALUES ('Sushi Time', 'Суши и роллы на вынос', 'Kazan', FALSE)
    RETURNING id INTO sushi_time;

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (pizza_house, 'Pizza', 1) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Margherita', 'Classic pizza with tomato sauce and mozzarella', 450.00, 1),
        (category, 'Pepperoni', 'Pizza with pepperoni and mozzarella', 520.00, 2);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (pizza_house, 'Drinks', 2) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Cola', 'Classic cold drink 0.5L', 120.00, 1);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (pizza_house, 'Desserts', 3) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Tiramisu', 'Italian dessert with mascarpone', 260.00, 1);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (burger_point, 'Burgers', 1) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Cheeseburger', 'Burger with beef patty and cheddar cheese', 390.00, 1),
        (category, 'Double Burger', 'Burger with double beef patty', 520.00, 2);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (burger_point, 'Snacks', 2) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'French Fries', 'Crispy potato fries', 180.00, 1);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (burger_point, 'Drinks', 3) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Lemonade', 'House lemonade 0.4L', 150.00, 1);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (sushi_time, 'Rolls', 1) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Philadelphia Roll', 'Roll with salmon and cream cheese', 430.00, 1),
        (category, 'California Roll', 'Roll with crab mix and cucumber', 410.00, 2);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (sushi_time, 'Sets', 2) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Family Set', 'Large sushi set for company', 1250.00, 1);

    INSERT INTO menu_category (restaurant_id, name, sort_order) VALUES (sushi_time, 'Drinks', 3) RETURNING id INTO category;
    INSERT INTO menu_item (category_id, name, description, price, sort_order) VALUES
        (category, 'Green Tea', 'Hot green tea', 110.00, 1);
END
$$;
