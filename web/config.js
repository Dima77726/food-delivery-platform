// Настройки веб-интерфейса. Файл отдаётся как есть: меняйте значения и обновляйте страницу.
window.APP_CONFIG = {
    // Быстрый вход под демо-пользователями на странице входа. Пользователи создаются скриптом
    // scripts/seed-demo.py. Пароль у них одинаковый и известен, поэтому на общедоступном сервере
    // эту возможность нужно выключить (false).
    demoAccounts: true,
    demoPassword: "demo12345",
    demo: [
        { role: "CUSTOMER", email: "anna@demo.local", name: "Анна", title: "Клиент", icon: "🛍️" },
        { role: "RESTAURANT_OWNER", email: "oleg@demo.local", name: "Олег", title: "Ресторатор", icon: "👨‍🍳" },
        { role: "COURIER", email: "kirill@demo.local", name: "Кирилл", title: "Курьер", icon: "🛵" },
        // Демо-администратор создаётся скриптом seed-demo.py (если в .env задан администратор платформы).
        { role: "ADMIN", email: "admin@demo.local", name: "Админ", title: "Админ", icon: "🛡️" }
    ],
    // ИИ-помощник (отдельный сервис food_delivery_agent на этом компьютере; nginx проксирует /agent/).
    assistant: true,
    // Адрес доставки: плитки карты и геокодер (подсказки при вводе и адрес по точке). По умолчанию —
    // OpenStreetMap и Photon: бесплатно и без ключа, но с ограничением на нагрузку, так что на общедоступном
    // сервере их стоит заменить на свои или платные. center и zoom — вид карты, пока клиент ничего не выбрал.
    geo: {
        tiles: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        geocoder: "https://photon.komoot.io",
        center: [55.7558, 37.6173],
        zoom: 12
    }
};
