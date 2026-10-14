#!/usr/bin/env python3
"""Демо-данные для локальной работы: пользователи трёх ролей и несколько ресторанов с меню.

Запуск (платформа должна работать):

    python scripts/seed-demo.py [--base http://localhost:8080]

Скрипт идемпотентен: существующих пользователей он только входит под их именем, а рестораны с теми же
названиями не дублирует. Пароли демо-пользователей одинаковые и известны (см. DEMO_PASSWORD) - это
учебные учётные записи для локального компьютера; на общедоступном сервере их заводить нельзя.
Администратор платформы создаётся ею самой из APP_ADMIN_EMAIL и APP_ADMIN_PASSWORD в .env. По этим данным скрипт
заводит ещё и демо-администратора admin@demo.local (роль выдаёт настоящий администратор); пропустить это - ключ --no-admin.

Только стандартная библиотека Python: ничего устанавливать не нужно.
"""

import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

DEMO_PASSWORD = "demo12345"

USERS = [
    ("anna@demo.local", "Анна Клиентова", "+79990000101", "CUSTOMER"),
    ("oleg@demo.local", "Олег Рестораторов", "+79990000102", "RESTAURANT_OWNER"),
    ("kirill@demo.local", "Кирилл Курьеров", "+79990000103", "COURIER"),
]

# (название, город, описание, [(категория, [(блюдо, цена, описание)])])
RESTAURANTS = [
    (
        "Траттория «Милано»", "Москва", "Пицца на тонком тесте, паста и десерты по домашним рецептам",
        [
            ("Пицца", [
                ("Маргарита", 450, "Томаты, моцарелла, базилик"),
                ("Пепперони", 520, "Острая пепперони, моцарелла"),
                ("Четыре сыра", 590, "Моцарелла, горгонзола, пармезан, чеддер"),
            ]),
            ("Паста", [
                ("Карбонара", 480, "Гуанчале, желток, пекорино"),
                ("Болоньезе", 450, "Говяжий рагу, томаты, пармезан"),
            ]),
            ("Десерты", [
                ("Тирамису", 320, "Маскарпоне, савоярди, эспрессо"),
                ("Панна-котта", 290, "Сливки, ваниль, ягодный соус"),
            ]),
        ],
    ),
    (
        "Бургерная №7", "Москва", "Сочные бургеры на мраморной говядине, картофель и лимонады",
        [
            ("Бургеры", [
                ("Классик", 390, "Говяжья котлета, чеддер, салат, соус"),
                ("Двойной бекон", 560, "Две котлеты, бекон, чеддер, BBQ"),
                ("Чикен", 360, "Куриная котлета, айсберг, сырный соус"),
            ]),
            ("Закуски", [
                ("Картофель фри", 180, "Хрустящий, с морской солью"),
                ("Луковые кольца", 210, "В панко, с соусом ранч"),
            ]),
            ("Напитки", [
                ("Лимонад «Цитрус»", 190, "Лимон, лайм, мята"),
                ("Холодный чай", 160, "Чёрный чай с персиком"),
            ]),
        ],
    ),
    (
        "Суши Мастер", "Москва", "Роллы, суши и том ям: свежая рыба каждый день",
        [
            ("Роллы", [
                ("Филадельфия", 520, "Лосось, сливочный сыр, огурец"),
                ("Калифорния", 470, "Краб, авокадо, икра масаго"),
                ("Дракон", 560, "Угорь, авокадо, унаги"),
            ]),
            ("Супы", [
                ("Том ям", 420, "Креветки, грибы, кокосовое молоко"),
                ("Мисо", 220, "Тофу, вакамэ, зелёный лук"),
            ]),
        ],
    ),
    (
        "Пельменная на Арбате", "Москва", "Домашние пельмени, вареники и бульоны со сметаной",
        [
            ("Пельмени", [
                ("С говядиной", 340, "Тонкое тесто, сочная начинка"),
                ("С курицей и грибами", 320, "Домашняя лепка"),
                ("С лососем", 420, "Сливочный соус"),
            ]),
            ("Вареники", [
                ("С картофелем и грибами", 290, "Со сметаной и жареным луком"),
                ("С вишней", 280, "Сладкие, со сгущёнкой"),
            ]),
            ("Супы", [
                ("Куриный бульон", 230, "Лапша, зелень, яйцо"),
            ]),
        ],
    ),
]


class Platform:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.last_auth = 0.0

    def call(self, method, path, body=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            try:
                return error.code, json.loads(raw)
            except ValueError:
                return error.code, raw

    def auth(self, path, body):
        # Вход и регистрация ограничены по частоте на шлюзе: пять попыток подряд, дальше примерно одна в 4 секунды.
        wait = 4.2 - (time.monotonic() - self.last_auth)
        if wait > 0:
            time.sleep(wait)
        result = self.call("POST", path, body)
        self.last_auth = time.monotonic()
        return result

    def login(self, email, password):
        status, body = self.auth("/api/v1/auth/login", {"email": email, "password": password})
        if status != 200:
            raise SystemExit(f"Вход {email} не удался: HTTP {status} {body}")
        return body


def ensure_user(platform, email, name, phone, role):
    status, body = platform.auth("/api/v1/auth/register", {
        "email": email, "password": DEMO_PASSWORD, "fullName": name, "phone": phone, "role": role,
    })
    if status == 201:
        print(f"  создан {email} ({role})")
        return body
    if status == 409:
        print(f"  уже есть {email}")
        return platform.login(email, DEMO_PASSWORD)
    raise SystemExit(f"Регистрация {email} не удалась: HTTP {status} {body}")


def read_env_admin():
    """Данные администратора из .env в корне репозитория (файл в Git не попадает)."""
    env_file = pathlib.Path(__file__).resolve().parent.parent / ".env"
    values = {}
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values.get("APP_ADMIN_EMAIL"), values.get("APP_ADMIN_PASSWORD")


def ensure_demo_admin(platform):
    email, password = read_env_admin()
    if not email or not password:
        print("  демо-администратор пропущен: в .env нет APP_ADMIN_EMAIL и APP_ADMIN_PASSWORD")
        return False
    demo = ensure_user(platform, "admin@demo.local", "Админ Админов", "+79990000100", "CUSTOMER")
    if "ADMIN" in demo["user"]["roles"]:
        return True
    admin = platform.login(email, password)
    status, body = platform.call("POST", f"/api/v1/admin/users/{demo['user']['id']}/roles/ADMIN", None, admin["accessToken"])
    if status not in (200, 201, 204):
        print(f"  роль ADMIN для admin@demo.local не выдана: HTTP {status} {body}")
        return False
    print("  admin@demo.local получил роль ADMIN")
    return True


def ensure_restaurants(platform, owner_token):
    status, existing = platform.call("GET", "/api/v1/restaurants")
    names = {item["name"] for item in existing} if status == 200 else set()

    for name, city, description, categories in RESTAURANTS:
        if name in names:
            print(f'  ресторан "{name}" уже есть')
            continue

        status, restaurant = platform.call(
            "POST", "/api/v1/restaurants", {"name": name, "city": city, "description": description}, owner_token
        )
        if status != 201:
            raise SystemExit(f"Ресторан «{name}» не создан: HTTP {status} {restaurant}")
        rid = restaurant["id"]

        for order, (category, items) in enumerate(categories):
            status, created = platform.call(
                "POST", f"/api/v1/restaurants/{rid}/menu/categories", {"name": category, "sortOrder": order}, owner_token
            )
            if status != 201:
                raise SystemExit(f"Категория «{category}» не создана: HTTP {status} {created}")
            for position, (dish, price, text) in enumerate(items):
                status, item = platform.call(
                    "POST",
                    f"/api/v1/restaurants/{rid}/menu/categories/{created['id']}/items",
                    {"name": dish, "description": text, "price": price, "sortOrder": position},
                    owner_token,
                )
                if status != 201:
                    raise SystemExit(f"Блюдо «{dish}» не создано: HTTP {status} {item}")

        print(f'  ресторан "{name}" создан: {sum(len(items) for _, items in categories)} блюд')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080", help="адрес шлюза платформы")
    parser.add_argument("--no-admin", action="store_true", help="не заводить демо-администратора")
    args = parser.parse_args()

    platform = Platform(args.base)
    status, _ = platform.call("GET", "/api/v1/restaurants")
    if status != 200:
        print(f"Платформа не отвечает по адресу {args.base} (HTTP {status}). Сначала запустите её.", file=sys.stderr)
        return 1

    print("Пользователи:")
    sessions = {}
    for email, name, phone, role in USERS:
        sessions[role] = ensure_user(platform, email, name, phone, role)

    admin_ready = False if args.no_admin else ensure_demo_admin(platform)

    print("Рестораны:")
    ensure_restaurants(platform, sessions["RESTAURANT_OWNER"]["accessToken"])

    print("\nГотово. Демо-вход (пароль одинаковый - " + DEMO_PASSWORD + "):")
    for email, name, _, role in USERS:
        print(f"  {role:17} {email}")
    if admin_ready:
        print(f"  {'ADMIN':17} admin@demo.local")
    return 0


if __name__ == "__main__":
    sys.exit(main())
