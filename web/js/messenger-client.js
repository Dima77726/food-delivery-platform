// Клиент мессенджера: одно WebSocket-соединение на вкладку для живых событий и сигнализации звонков.
//
// Токен уходит первым кадром (не в адресе). Соединение переподключается с нарастающей паузой, шлёт сердцебиение
// каждые 25 секунд и обновляет токен до истечения. Всё, что меняет данные, идёт по REST — сокет только сообщает.
// После переподключения страницы догоняют пропущенное по REST (номера сообщений и изменений идут подряд).

import { accessToken, refreshSession, get } from "./api.js";
import { session } from "./store.js";

const DEVICE_KEY = "fd.messenger.device";
const listeners = new Set();
let socket = null;
let state = "offline"; // offline | connecting | online
let attempt = 0;
let pingTimer = null;
let renewTimer = null;
let reconnectTimer = null;
let currentEmail = null;
let unread = 0;
const unreadListeners = new Set();

/** Устройство — эта вкладка браузера: звонок принимается на одном устройстве, остальные перестают звонить. */
export function deviceId() {
    let id = sessionStorage.getItem(DEVICE_KEY);
    if (!id) {
        id = "web-" + Math.random().toString(36).slice(2, 10);
        try { sessionStorage.setItem(DEVICE_KEY, id); } catch { /* приватный режим */ }
    }
    return id;
}

export const connectionState = () => state;

/** Подписка на события сокета: fn(frame). Возвращает отписку. */
export function onEvent(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

function emit(frame) {
    listeners.forEach((fn) => { try { fn(frame); } catch (error) { console.error(error); } });
}

export function send(frame) {
    if (socket && socket.readyState === WebSocket.OPEN && state === "online") {
        socket.send(JSON.stringify(frame));
        return true;
    }
    return false;
}

/** Подключиться для текущего аккаунта (или переподключиться, если аккаунт сменился). */
export function ensureMessenger() {
    const me = session();
    const email = me?.email ?? null;
    if (email === currentEmail && (state !== "offline" || !email)) return;
    disconnect();
    currentEmail = email;
    if (email) {
        connect();
        refreshUnread();
    }
}

function disconnect() {
    clearInterval(pingTimer);
    clearTimeout(renewTimer);
    clearTimeout(reconnectTimer);
    if (socket) {
        socket.onclose = null;
        try { socket.close(1000); } catch { /* уже закрыт */ }
    }
    socket = null;
    setState("offline");
}

function setState(next) {
    state = next;
    emit({ type: "connection", state: next });
}

function connect() {
    const token = accessToken();
    if (!token) return;
    setState("connecting");
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${scheme}://${location.host}/ws/messenger`);
    socket = ws;

    ws.onopen = () => ws.send(JSON.stringify({ type: "auth", token: accessToken(), deviceId: deviceId() }));
    ws.onmessage = (event) => {
        let frame;
        try { frame = JSON.parse(event.data); } catch { return; }
        if (frame.type === "ready") {
            attempt = 0;
            setState("online");
            pingTimer = setInterval(() => send({ type: "ping" }), 25_000);
            scheduleRenew();
            emit({ type: "resync" });
        }
        if (frame.type === "message.new" || frame.type === "read.self" || frame.type === "chat.added" || frame.type === "chat.removed") {
            refreshUnread();
        }
        emit(frame);
    };
    ws.onclose = async (event) => {
        clearInterval(pingTimer);
        clearTimeout(renewTimer);
        if (socket !== ws) return;
        setState("offline");
        if (event.code === 4401) {
            // Токен истёк: обменять refresh и подключиться снова; не вышло — сессия кончилась.
            if (!(await refreshSession())) return;
        }
        const delay = Math.min(30_000, 1000 * 2 ** attempt++) + Math.random() * 500;
        reconnectTimer = setTimeout(() => { if (currentEmail) connect(); }, delay);
    };
}

/** Обновить токен на живом соединении за минуту до истечения — без разрыва и без потери событий. */
function scheduleRenew() {
    clearTimeout(renewTimer);
    const token = accessToken();
    let expiresAt = Date.now() + 10 * 60_000;
    try { expiresAt = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).exp * 1000; } catch { /* не JWT */ }
    renewTimer = setTimeout(async () => {
        if (await refreshSession()) send({ type: "auth", token: accessToken(), deviceId: deviceId() });
        scheduleRenew();
    }, Math.max(5_000, expiresAt - Date.now() - 60_000));
}

window.addEventListener("online", () => { if (state === "offline" && currentEmail) { clearTimeout(reconnectTimer); connect(); } });

// ---------- Счётчик непрочитанных для верхней панели ----------

export const unreadTotal = () => unread;
export function onUnread(fn) {
    unreadListeners.add(fn);
    return () => unreadListeners.delete(fn);
}

let unreadTimer = null;
export function refreshUnread() {
    clearTimeout(unreadTimer);
    unreadTimer = setTimeout(async () => {
        try {
            const chats = await get("/api/v1/messenger/chats");
            unread = chats.reduce((sum, c) => sum + (c.mutedUntil ? 0 : c.unread), 0);
        } catch { unread = 0; }
        unreadListeners.forEach((fn) => fn(unread));
    }, 300);
}
