// Живые обновления: SSE, long polling и STOMP поверх WebSocket — три транспорта, все без сторонних библиотек.
//
// SSE читается через fetch, а не через EventSource: EventSource не умеет заголовок Authorization, а токен в адресе
// оседает в логах nginx и шлюза. Формат потока тот же (text/event-stream), разбирается здесь. Переподключение — с
// Last-Event-ID, как сделал бы EventSource, и с нарастающей паузой.
//
// Long polling — цикл запросов: ответ пришёл — обработали — сразу спрашиваем снова, передав курсор или ETag.
//
// STOMP — минимальный клиент: CONNECT с токеном в заголовке кадра, SUBSCRIBE, SEND, переподключение с
// восстановлением подписок. Протокол текстовый: КОМАНДА\nзаголовки\n\nтело\0.
//
// Все три помнят, что токен живёт 15 минут: на 401 (или ERROR при CONNECT) обмениваем refresh-токен и повторяем.

import { accessToken, refreshSession } from "./api.js";

const backoff = (attempt) => Math.min(30000, 1000 * 2 ** attempt) * (0.5 + Math.random() / 2);
const sleep = (ms, signal) => new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
});

/**
 * Поток SSE с авторизацией и переподключением.
 *
 * @param path     путь вида /api/v1/...
 * @param onEvent  (имя события, данные JSON, id) => void
 * @param onState  ("live" | "reconnecting" | "closed") => void — для индикатора в интерфейсе
 * @returns функция, закрывающая поток
 */
export function sseStream(path, { onEvent, onState = () => {} }) {
    const controller = new AbortController();
    let lastEventId = null;

    (async () => {
        let attempt = 0;
        while (!controller.signal.aborted) {
            try {
                const headers = { Accept: "text/event-stream, application/json", Authorization: `Bearer ${accessToken()}` };
                if (lastEventId) headers["Last-Event-ID"] = lastEventId;
                const response = await fetch(path, { headers, signal: controller.signal, cache: "no-store" });

                if (response.status === 401 && await refreshSession()) continue;
                if (response.status === 404 || response.status === 403 || response.status === 401) { onState("closed"); return; }
                if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

                attempt = 0;
                onState("live");
                const ended = await readEvents(response.body, (event) => {
                    if (event.id) lastEventId = event.id;
                    if (event.name === "end") return true;
                    onEvent(event.name, event.data, event.id);
                    return false;
                });
                if (ended) { onState("closed"); return; }
            } catch (error) {
                if (controller.signal.aborted) return;
            }
            onState("reconnecting");
            await sleep(backoff(attempt++), controller.signal);
        }
    })();

    return () => controller.abort();
}

/** Разбор text/event-stream: кадры разделены пустой строкой, строки «поле: значение», «:» — комментарий (пульс). */
async function readEvents(body, handle) {
    const reader = body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";
    for (;;) {
        const { value, done } = await reader.read();
        if (done) return false;
        buffer += value.replace(/\r\n/g, "\n");
        let split;
        while ((split = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, split);
            buffer = buffer.slice(split + 2);
            const event = { name: "message", data: "", id: null };
            const data = [];
            for (const line of frame.split("\n")) {
                if (!line || line.startsWith(":")) continue;
                const colon = line.indexOf(":");
                const field = colon < 0 ? line : line.slice(0, colon);
                const val = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
                if (field === "event") event.name = val;
                else if (field === "data") data.push(val);
                else if (field === "id") event.id = val;
            }
            if (!data.length) continue;
            try { event.data = JSON.parse(data.join("\n")); } catch { event.data = data.join("\n"); }
            if (handle(event)) { reader.cancel(); return true; }
        }
    }
}

/**
 * Цикл long polling.
 *
 * @param request  (signal) => Promise<{ next }> — один запрос; next — значение для следующего (курсор, ETag)
 * @returns функция остановки
 */
export function longPoll(request) {
    const controller = new AbortController();
    (async () => {
        let attempt = 0;
        while (!controller.signal.aborted) {
            try {
                await request(controller.signal);
                attempt = 0;
            } catch {
                if (controller.signal.aborted) return;
                await sleep(backoff(attempt++), controller.signal);
            }
        }
    })();
    return () => controller.abort();
}

/** Один запрос long polling с авторизацией: fetch + повтор после обмена токена. */
export async function pollFetch(path, { headers = {}, signal } = {}) {
    for (let attempt = 0; attempt < 2; attempt++) {
        const response = await fetch(path, { headers: { ...headers, Authorization: `Bearer ${accessToken()}` }, signal, cache: "no-store" });
        if (response.status === 401 && attempt === 0 && await refreshSession()) continue;
        return response;
    }
    throw new Error("Нужно войти в аккаунт");
}

// ---------- STOMP поверх WebSocket ----------

let shared = null;

/** Одно соединение STOMP на вкладку: трек и чат разных заказов живут в нём как разные подписки. */
export function stomp() {
    shared ??= new StompConnection(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/tracking`);
    return shared;
}

class StompConnection {
    constructor(url) {
        this.url = url;
        this.subscriptions = new Map(); // id -> { destination, handler }
        this.nextId = 1;
        this.socket = null;
        this.connected = false;
        this.queue = [];
        this.attempt = 0;
        this.listeners = new Set();
    }

    /** @returns функция отписки */
    subscribe(destination, handler) {
        const id = `sub-${this.nextId++}`;
        this.subscriptions.set(id, { destination, handler });
        this.ensure();
        if (this.connected) this.frame("SUBSCRIBE", { id, destination });
        return () => {
            this.subscriptions.delete(id);
            if (this.connected) this.frame("UNSUBSCRIBE", { id });
            if (!this.subscriptions.size) this.close();
        };
    }

    send(destination, body = {}) {
        const frame = ["SEND", { destination, "content-type": "application/json" }, JSON.stringify(body)];
        if (this.connected) this.frame(...frame);
        else if (this.queue.length < 20) this.queue.push(frame);
    }

    onState(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }

    ensure() {
        if (this.socket) return;
        const socket = new WebSocket(this.url);
        this.socket = socket;
        let buffer = "";
        socket.onopen = () => this.frame("CONNECT", { "accept-version": "1.2", host: location.host, Authorization: `Bearer ${accessToken()}`, "heart-beat": "0,0" });
        socket.onmessage = (message) => {
            buffer += message.data;
            let end;
            while ((end = buffer.indexOf("\0")) >= 0) {
                this.handle(buffer.slice(0, end).replace(/^\n+/, ""));
                buffer = buffer.slice(end + 1);
            }
        };
        socket.onclose = () => {
            this.socket = null;
            this.connected = false;
            this.emit("reconnecting");
            if (!this.subscriptions.size) return;
            setTimeout(() => this.ensure(), backoff(this.attempt++));
        };
    }

    async handle(raw) {
        if (!raw) return;
        const [head, ...rest] = raw.split("\n\n");
        const [command, ...lines] = head.split("\n");
        const headers = Object.fromEntries(lines.map((l) => [l.slice(0, l.indexOf(":")), l.slice(l.indexOf(":") + 1)]));
        const body = rest.join("\n\n");
        if (command === "CONNECTED") {
            this.connected = true;
            this.attempt = 0;
            this.emit("live");
            for (const [id, { destination }] of this.subscriptions) this.frame("SUBSCRIBE", { id, destination });
            this.queue.splice(0).forEach((frame) => this.frame(...frame));
        } else if (command === "MESSAGE") {
            const sub = this.subscriptions.get(headers.subscription);
            if (sub) { try { sub.handler(JSON.parse(body)); } catch { /* битый кадр пропускаем */ } }
        } else if (command === "ERROR") {
            // Отказ на CONNECT чаще всего значит истёкший токен: обменяем, и onclose переподключится.
            if (!this.connected) await refreshSession();
        }
    }

    frame(command, headers, body = "") {
        if (this.socket?.readyState !== WebSocket.OPEN) return;
        const head = Object.entries(headers).map(([k, v]) => `${k}:${v}`).join("\n");
        this.socket.send(`${command}\n${head}\n\n${body}\0`);
    }

    emit(state) { this.listeners.forEach((fn) => fn(state)); }

    close() {
        const socket = this.socket;
        this.socket = null;
        this.connected = false;
        if (socket) { socket.onclose = null; socket.close(); }
    }
}
