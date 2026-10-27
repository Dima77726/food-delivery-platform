// Клиент REST API платформы. Все запросы идут на тот же адрес (nginx проксирует их на шлюз),
// поэтому CORS не нужен. Access-токен живёт 15 минут; при 401 клиент обменивает refresh-токен и повторяет запрос.

import { session, signIn, updateTokens, forget } from "./store.js";

export class ApiError extends Error {
    constructor(status, message, body) {
        super(message);
        this.status = status;
        this.body = body;
    }
}

const FRIENDLY = {
    401: "Нужно войти в аккаунт",
    403: "Недостаточно прав для этого действия",
    404: "Не найдено",
    409: "Действие сейчас невозможно",
    413: "Файл слишком большой",
    415: "Этот тип файла не поддерживается",
    429: "Слишком много запросов, подождите несколько секунд",
    503: "Сервис временно недоступен",
};

function messageOf(status, body) {
    if (body && typeof body === "object" && body.message) return body.message;
    // Сервис агента отвечает полем detail.
    if (body && typeof body === "object" && typeof body.detail === "string") return body.detail;
    if (typeof body === "string" && body.trim() && body.length < 200) return body;
    return FRIENDLY[status] || `Ошибка ${status}`;
}

let refreshing = null;

/** Обмен refresh-токена. Токен одноразовый, поэтому обмен один на все вкладки (Web Locks) и один на вкладку. */
async function refresh(email, usedRefresh) {
    if (refreshing) return refreshing;
    refreshing = (async () => {
        const run = async () => {
            // Другая вкладка могла уже обновить токены: тогда берём свежие и не тратим refresh второй раз.
            const current = session();
            if (current && current.email === email && current.refresh !== usedRefresh) return true;
            const response = await fetch("/api/v1/auth/refresh", {
                method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refreshToken: usedRefresh }),
            });
            if (!response.ok) return false;
            const body = await response.json();
            updateTokens(email, body);
            return true;
        };
        try {
            return navigator.locks ? await navigator.locks.request("fd-refresh", run) : await run();
        } finally {
            refreshing = null;
        }
    })();
    return refreshing;
}

/**
 * @param method  HTTP-метод
 * @param path    путь вида /api/v1/...
 * @param options { body, auth = true, form (FormData), base = "" }
 */
export async function api(method, path, { body, auth = true, form, base = "" } = {}) {
    for (let attempt = 0; attempt < 2; attempt++) {
        const current = auth ? session() : null;
        const headers = {};
        if (current) headers.Authorization = `Bearer ${current.access}`;
        let payload;
        if (form) payload = form;
        else if (body !== undefined) { headers["Content-Type"] = "application/json"; payload = JSON.stringify(body); }

        let response;
        try {
            response = await fetch(base + path, { method, headers, body: payload });
        } catch {
            throw new ApiError(0, "Нет связи с платформой. Проверьте, что она запущена.");
        }

        if (response.status === 401 && current && attempt === 0) {
            if (await refresh(current.email, current.refresh)) continue;
            forget(current.email);
            location.hash = "#/login";
            throw new ApiError(401, "Сессия истекла, войдите снова");
        }

        const text = await response.text();
        let data = null;
        if (text) { try { data = JSON.parse(text); } catch { data = text; } }
        if (!response.ok) throw new ApiError(response.status, messageOf(response.status, data), data);
        return data;
    }
    throw new ApiError(401, "Нужно войти в аккаунт");
}

/** Текущий access-токен — для транспортов, которые ходят мимо api(): SSE, long polling, WebSocket. */
export const accessToken = () => session()?.access ?? null;

/** Обменять refresh-токен (после 401 в потоке). false — сессия кончилась, нужно войти заново. */
export async function refreshSession() {
    const current = session();
    if (!current) return false;
    return refresh(current.email, current.refresh);
}

export const get = (path, options) => api("GET", path, options);
export const post = (path, body, options) => api("POST", path, { ...options, body });
export const patch = (path, body, options) => api("PATCH", path, { ...options, body });
export const del = (path, options) => api("DELETE", path, options);

// ---------- Аутентификация ----------

export async function login(email, password) {
    const response = await api("POST", "/api/v1/auth/login", { body: { email, password }, auth: false });
    return signIn(response);
}

export async function register({ email, password, fullName, phone, role }) {
    const response = await api("POST", "/api/v1/auth/register", { body: { email, password, fullName, phone, role }, auth: false });
    return signIn(response);
}

export async function logout(email) {
    const current = session();
    if (current && current.email === email) {
        try { await api("POST", "/api/v1/auth/logout", { body: { refreshToken: current.refresh }, auth: false }); } catch { /* сессия и так закончится */ }
    }
    forget(email);
}

// ---------- Короткие обёртки над ресурсами ----------

export const restaurants = () => get("/api/v1/restaurants", { auth: false });
export const restaurant = (id) => get(`/api/v1/restaurants/${id}`, { auth: false });
export const menu = (id) => get(`/api/v1/restaurants/${id}/menu`, { auth: false });

// Названия ресторанов нужны в списках заказов; запрашиваем один раз.
const nameCache = new Map();
export async function restaurantName(id) {
    if (!nameCache.has(id)) nameCache.set(id, restaurant(id).then((r) => r.name).catch(() => `Ресторан №${id}`));
    return nameCache.get(id);
}
