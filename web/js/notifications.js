// Лента уведомлений вживую: long polling к notification-service. Сервер держит запрос до 25 секунд и отвечает, как
// только появится уведомление новее курсора. Пришло новое — всплывашка и счётчик на колокольчике.

import { get } from "./api.js";
import { longPoll, pollFetch } from "./live.js";
import { toast } from "./ui.js";

let running = null; // { email, stop }
let unread = 0;
const listeners = new Set();

export const unreadCount = () => unread;
export const onUnreadChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };

export function markAllSeen() {
    unread = 0;
    listeners.forEach((fn) => fn(unread));
}

/** Запускает ленту для аккаунта; для другого аккаунта перезапускает, без аккаунта — останавливает. */
export function ensureNotificationFeed(email) {
    if (running?.email === email) return;
    running?.stop();
    running = null;
    unread = 0;
    if (!email) return;

    let cursor = null;
    const stop = longPoll(async (signal) => {
        // Курсор старта — самое свежее уже существующее уведомление: старые всплывать не должны.
        if (cursor === null) {
            const existing = await get("/api/v1/notifications/me");
            cursor = existing.reduce((max, n) => Math.max(max, n.id), 0);
        }
        const response = await pollFetch(`/api/v1/notifications/me/poll?after=${cursor}&timeoutSeconds=25`, { signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const page = await response.json();
        cursor = page.nextAfter;
        // У одного события бывает несколько каналов (письмо и Telegram): показываем по одному на тему.
        const seen = new Set();
        for (const item of page.items) {
            const key = `${item.orderId}:${item.type}`;
            if (seen.has(key)) continue;
            seen.add(key);
            unread++;
            toast(`🔔 ${item.subject}`, "ok");
        }
        if (page.items.length) listeners.forEach((fn) => fn(unread));
    });
    running = { email, stop };
}
