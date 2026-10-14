#!/usr/bin/env python3
"""Закрывает тестовые рестораны, оставшиеся от автотестов и ручных проверок, чтобы каталог был чистым.

Ничего не удаляется: закрытый ресторан остаётся в базе, пропадает из каталога по умолчанию (веб-интерфейс
прячет закрытые рестораны за переключателем), а владелец или администратор может открыть его обратно.

    python scripts/hide-test-restaurants.py            # только показать, что будет закрыто
    python scripts/hide-test-restaurants.py --apply    # закрыть

Нужны APP_ADMIN_EMAIL и APP_ADMIN_PASSWORD в .env (закрывать чужие рестораны может только администратор).
Подходят рестораны, чьё название начинается с одного из префиксов TEST_PREFIXES.
"""

import argparse
import json
import pathlib
import sys
import urllib.error
import urllib.request

TEST_PREFIXES = ("Тестовая", "Автотест", "E2E", "Media ", "Trace ", "Soap ", "Agent Cafe", "Тёплая кухня ")


def env_values():
    path = pathlib.Path(__file__).resolve().parent.parent / ".env"
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def call(base, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--apply", action="store_true", help="действительно закрыть (по умолчанию только показать)")
    args = parser.parse_args()

    status, restaurants = call(args.base, "GET", "/api/v1/restaurants")
    if status != 200:
        print(f"Платформа не отвечает (HTTP {status}).", file=sys.stderr)
        return 1

    candidates = [r for r in restaurants if r["active"] and r["name"].startswith(TEST_PREFIXES)]
    if not candidates:
        print("Тестовых открытых ресторанов нет.")
        return 0

    for r in candidates:
        print(f"  №{r['id']:<4} {r['name']}")
    if not args.apply:
        print(f"\nБудет закрыто: {len(candidates)}. Чтобы закрыть: python scripts/hide-test-restaurants.py --apply")
        return 0

    env = env_values()
    if not env.get("APP_ADMIN_EMAIL") or not env.get("APP_ADMIN_PASSWORD"):
        print("В .env нет APP_ADMIN_EMAIL и APP_ADMIN_PASSWORD.", file=sys.stderr)
        return 1
    status, login = call(args.base, "POST", "/api/v1/auth/login", {"email": env["APP_ADMIN_EMAIL"], "password": env["APP_ADMIN_PASSWORD"]})
    if status != 200:
        print(f"Вход администратора не удался (HTTP {status}).", file=sys.stderr)
        return 1

    closed = 0
    for r in candidates:
        status, _ = call(args.base, "POST", f"/api/v1/restaurants/{r['id']}/close", None, login["accessToken"])
        closed += status in (200, 204)
    print(f"\nЗакрыто: {closed} из {len(candidates)}. Вернуть ресторан: открыть его в разделе владельца или POST /api/v1/restaurants/{{id}}/open.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
