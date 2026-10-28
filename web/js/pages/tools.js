// Инструменты разработчика и аналитика: где что смотреть, чем входить и работает ли сейчас.

import { html, delegate } from "../ui.js";

/** [название, адрес, что смотреть, вход, как включить] */
const GROUPS = [
    ["Пользоваться и проверять API", [
        ["🍜 Вкусно", "http://localhost:8070", "Веб-интерфейс для всех ролей", "демо-кнопки на странице входа", ""],
        ["🚪 Шлюз (REST)", "http://localhost:8080/api/v1/restaurants", "Все REST-запросы идут сюда: curl, Postman", "токен из POST /api/v1/auth/login", ""],
        ["📘 Swagger UI", "http://localhost:8080/swagger-ui.html", "Описание и вызов API insights-service", "кнопка Authorize, токен без Bearer", ""],
        ["🕸️ GraphiQL", "http://localhost:8080/graphiql", "GraphQL: рейтинг, отзывы, рекомендации, продажи", "без токена; для продаж — заголовок Authorization", ""],
        ["🧼 SOAP: WSDL меню", "http://localhost:8080/ws/menu.wsdl", "Импортировать в SoapUI; WSDL заказов — /ws/orders.wsdl", "запросы — токен ресторатора", ""],
        ["🤖 ИИ-помощник", "http://127.0.0.1:8001/docs", "Swagger агента, POST /api/v1/chat", "токен клиента платформы", "start.ps1 -Agent"],
    ]],
    ["Наблюдаемость", [
        ["📊 Grafana", "http://localhost:3000", "Дашборды «Food Delivery Platform» и «Бизнес», Explore: Tempo (трассы), Loki (логи), алерты", "admin / GRAFANA_PASSWORD из .env", ""],
        ["📈 Prometheus", "http://localhost:9090", "Сырые метрики и цели сбора", "без входа", ""],
        ["✉️ Mailpit", "http://localhost:8025", "Все письма платформы и алерты Grafana", "без входа", ""],
    ]],
    ["Брокеры и файлы", [
        ["🧵 Kafka UI", "http://localhost:8090", "Топики food-delivery.*, сообщения, ключи, заголовки, группы потребителей", "без входа", "start.ps1 -Tools"],
        ["🐇 RabbitMQ", "http://localhost:15672", "Exchange и очереди notifications.*, повторы, dead", "RABBITMQ_USER / RABBITMQ_PASSWORD из .env", ""],
        ["🪣 MinIO", "http://localhost:9001", "Бакет menu-images с фото блюд", "MINIO_ROOT_USER / MINIO_ROOT_PASSWORD из .env", ""],
        ["🔐 Keycloak", "http://localhost:8180", "Realm food-delivery: пользователи, клиент food-delivery-web", "admin / KEYCLOAK_ADMIN_PASSWORD из .env", "start.ps1 -Auth"],
    ]],
    ["Хранилища", [
        ["🐘 PostgreSQL", "", "localhost:5432 — базы identity, catalog, orders, payments, notification. DataGrip: проект DataGripProjects\\food_delivery", "food_user / POSTGRES_PASSWORD; только чтение — analytics_reader / ANALYTICS_DB_PASSWORD", ""],
        ["🔎 Kibana", "http://localhost:5601", "Elasticsearch: индексы food-delivery-restaurants и food-delivery-menu-items, Dev Tools", "без входа", "start.ps1 -Tools"],
        ["🔎 Elasticsearch", "http://localhost:9200/_cat/indices?v", "Индексы поиска напрямую", "без входа", ""],
        ["🟡 ClickHouse", "http://localhost:8123/play", "Аналитика (order_events) и сводный аудит (audit_log, с FINAL)", "CLICKHOUSE_USER / CLICKHOUSE_PASSWORD из .env", ""],
        ["🕸️ Neo4j", "http://localhost:7474", "Граф «клиент — заказ — блюдо» для рекомендаций", "neo4j / NEO4J_PASSWORD из .env", ""],
        ["🍃 MongoDB", "", "localhost:27017 — отзывы; DataGrip или mongosh", "без пароля (локально)", ""],
        ["👁️ Cassandra", "", "localhost:9042 — трек курьера; DataGrip", "без пароля", ""],
        ["⚡ Redis", "", "localhost:6379 — кэш меню и счётчики rate limit; DataGrip", "без пароля", ""],
    ]],
];

/**
 * Доступность спрашивается у nginx (/_status/<имя>): он в сети compose и видит контейнеры. Из браузера ответ
 * чужого порта прочитать нельзя (CORS), поэтому прямой запрос к инструменту ничего бы не сказал.
 */
async function ping(key) {
    if (!key) return true;
    try {
        // Перенаправление (Swagger, GraphiQL) - тоже ответ: следовать за ним незачем, адрес внутри сети compose.
        const response = await fetch(`/_status/${key}`, { cache: "no-store", redirect: "manual" });
        return response.type === "opaqueredirect" || (response.status > 0 && response.status < 500);
    } catch {
        return false;
    }
}

/** Какой проверкой nginx проверяется инструмент: по адресу, который открывается кнопкой. */
const STATUS_KEY = {
    "http://localhost:8070": "", "http://localhost:8080/api/v1/restaurants": "gateway", "http://localhost:8080/swagger-ui.html": "swagger",
    "http://localhost:8080/graphiql": "graphiql", "http://localhost:8080/ws/menu.wsdl": "wsdl", "http://127.0.0.1:8001/docs": "agent",
    "http://localhost:3000": "grafana", "http://localhost:9090": "prometheus", "http://localhost:8025": "mailpit",
    "http://localhost:8090": "kafka-ui", "http://localhost:15672": "rabbitmq", "http://localhost:9001": "minio",
    "http://localhost:8180": "keycloak", "http://localhost:5601": "kibana", "http://localhost:9200/_cat/indices?v": "elasticsearch",
    "http://localhost:8123/play": "clickhouse", "http://localhost:7474": "neo4j",
};

export default async function toolsPage({ root }) {
    root.innerHTML = html`<div class="container">
        <div class="page-head"><h1>🧰 Инструменты</h1><span class="muted">где что смотреть и чем входить</span>
            <button class="btn sm right" data-act="recheck">Проверить доступность</button></div>
        <p class="muted" style="margin-bottom:18px">Пароли не показываются: они в файле <b>.env</b> в корне food-delivery-platform. Серый статус — сервис не запущен
            (для Kafka UI и Kibana нужен <code>start.ps1 -Tools</code>, для Keycloak — <code>-Auth</code>, для агента — <code>-Agent</code>).</p>
        ${GROUPS.map(([title, items]) => html`<h2 style="margin:22px 0 12px">${title}</h2>
            <div class="grid">${items.map(([name, url, what, login, how]) => html`<div class="card stack" style="gap:8px">
                <div class="row between"><h3>${name}</h3><span class="badge" data-status="${url ? STATUS_KEY[url] ?? "" : ""}" data-has="${url ? "1" : ""}">${url ? "…" : "клиент БД"}</span></div>
                <p class="small">${what}</p>
                <p class="muted small">Вход: ${login}${how ? html`<br>Запуск: <code>${how}</code>` : ""}</p>
                ${url ? html`<a class="btn sm" href="${url}" target="_blank" rel="noopener">Открыть ↗</a>` : ""}</div>`)}</div>`)}
    </div>`.value;

    async function check() {
        await Promise.all([...root.querySelectorAll("[data-status]")].filter((el) => el.dataset.has).map(async (el) => {
            el.className = "badge";
            el.textContent = "…";
            const up = await ping(el.dataset.status);
            el.className = `badge ${up ? "green" : ""}`;
            el.textContent = up ? "работает" : "не запущен";
        }));
    }

    delegate(root, "click", { recheck: check });
    check();
}
