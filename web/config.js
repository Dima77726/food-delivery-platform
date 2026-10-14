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
    assistant: true
};
