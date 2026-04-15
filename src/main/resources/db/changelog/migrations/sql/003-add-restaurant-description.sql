ALTER TABLE ${appSchema}.restaurant
ADD COLUMN IF NOT EXISTS description VARCHAR(1000);

COMMENT ON COLUMN ${appSchema}.restaurant.description IS 'Текстовое описание ресторана';

UPDATE ${appSchema}.restaurant
SET description = 'Семейный ресторан с пиццей и пастой'
WHERE name = 'Pizza House' AND description IS NULL;

UPDATE ${appSchema}.restaurant
SET description = 'Бургеры, закуски и напитки'
WHERE name = 'Burger Point' AND description IS NULL;

UPDATE ${appSchema}.restaurant
SET description = 'Суши и роллы на вынос'
WHERE name = 'Sushi Time' AND description IS NULL;