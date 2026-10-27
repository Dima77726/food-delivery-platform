// Чат заказа: клиент ↔ курьер (и поддержка). История и отправка — по HTTP, новые сообщения, «печатает…» и «прочитано»
// — через общий WebSocket (STOMP). Отправка идемпотентна: clientMessageId создаётся один раз на сообщение и
// повторяется, если первая попытка оборвалась, — сервер не создаст дубль.

import { get, post } from "./api.js";
import { stomp } from "./live.js";
import { html, esc, timeOnly, reportError } from "./ui.js";

const ROLE_NAMES = { CUSTOMER: "Клиент", COURIER: "Курьер", SUPPORT: "Поддержка" };
const TYPING_TTL = 4000;

/**
 * Монтирует чат в контейнер.
 *
 * @param slot     элемент, в который рисуется чат (перерисовки страницы его не трогают — см. keep)
 * @param orderId  заказ
 * @param me       { id } — текущий пользователь
 * @param closed   заказ завершён: только чтение
 * @returns { destroy, setClosed }
 */
export function mountChat(slot, { orderId, me, closed = false }) {
    let messages = []; // от старых к новым
    let reads = {};
    let typingUntil = 0;
    let typingRole = null;
    let typingTimer = null;
    let lastTypingSent = 0;
    let isClosed = closed;
    const pending = new Map(); // clientMessageId -> текст, ещё не подтверждённый сервером

    slot.innerHTML = html`<div class="oc">
        <div class="oc-log" data-log><div class="muted small center">Загружаем переписку…</div></div>
        <div class="oc-typing muted small" data-typing></div>
        <form class="oc-form" data-form>
            <input class="input" name="text" maxlength="1000" autocomplete="off" placeholder="Сообщение…">
            <button class="btn primary" type="submit">➤</button>
        </form></div>`.value;
    const log = slot.querySelector("[data-log]");
    const typingLine = slot.querySelector("[data-typing]");
    const form = slot.querySelector("[data-form]");
    const input = form.elements.text;

    function render() {
        const peerRead = Object.entries(reads).filter(([uid]) => Number(uid) !== me.id).map(([, id]) => id);
        const lastMineRead = messages.filter((m) => m.senderId === me.id && peerRead.includes(m.messageId)).pop()?.messageId;
        const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
        log.innerHTML = messages.length || pending.size
            ? messages.map((m) => {
                const mine = m.senderId === me.id;
                return `<div class="oc-msg ${mine ? "mine" : ""}"><div class="oc-bubble">${!mine ? `<div class="oc-who">${esc(ROLE_NAMES[m.senderRole] || "")}</div>` : ""}${esc(m.text)}
                    <div class="oc-meta">${esc(timeOnly(m.sentAt))}${mine && m.messageId === lastMineRead ? " · прочитано" : ""}</div></div></div>`;
            }).join("") + [...pending.values()].map((text) => `<div class="oc-msg mine pending"><div class="oc-bubble">${esc(text)}<div class="oc-meta">отправляется…</div></div></div>`).join("")
            : `<div class="muted small center">Сообщений пока нет. Напишите первым — например, код домофона.</div>`;
        if (atBottom || messages.length < 30) log.scrollTop = log.scrollHeight;
        typingLine.textContent = Date.now() < typingUntil ? `${ROLE_NAMES[typingRole] || "Собеседник"} печатает…` : "";
        input.disabled = isClosed;
        form.querySelector("button").disabled = isClosed;
        input.placeholder = isClosed ? "Заказ завершён — чат только для чтения" : "Сообщение…";
    }

    function add(message) {
        if (messages.some((m) => m.messageId === message.messageId)) return;
        pending.delete(message.clientMessageId);
        messages.push(message);
        messages.sort((a, b) => new Date(a.sentAt) - new Date(b.sentAt));
    }

    function markRead() {
        const last = messages.filter((m) => m.senderId !== me.id).pop();
        if (last && document.visibilityState === "visible" && reads[me.id] !== last.messageId) {
            reads[me.id] = last.messageId;
            stomp().send(`/app/order.${orderId}.chat.read`, { upTo: last.messageId });
        }
    }

    async function load() {
        try {
            const history = await get(`/api/v1/orders/${orderId}/chat/messages?limit=100`);
            history.messages.forEach(add);
            reads = { ...history.reads, ...reads };
            render();
            markRead();
        } catch (error) {
            log.innerHTML = `<div class="muted small center">${esc(error.message)}</div>`;
        }
    }

    const unsubscribe = stomp().subscribe(`/topic/order.${orderId}.chat`, (event) => {
        if (event.type === "MESSAGE") {
            add(event);
            if (event.senderId !== me.id) typingUntil = 0;
            render();
            markRead();
        } else if (event.type === "TYPING" && event.senderId !== me.id) {
            typingUntil = Date.now() + TYPING_TTL;
            typingRole = event.senderRole;
            render();
            clearTimeout(typingTimer);
            typingTimer = setTimeout(render, TYPING_TTL + 50);
        } else if (event.type === "READ") {
            reads[event.senderId] = event.upTo;
            render();
        }
    });

    async function send(text, clientMessageId) {
        pending.set(clientMessageId, text);
        render();
        for (let attempt = 0; attempt < 3; attempt++) {
            try {
                add(await post(`/api/v1/orders/${orderId}/chat/messages`, { text, clientMessageId }));
                render();
                return;
            } catch (error) {
                // Сеть оборвалась — повторяем с тем же clientMessageId; ответ сервера с кодом — окончательный.
                if (error.status) { pending.delete(clientMessageId); render(); reportError(error); return; }
                await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)));
            }
        }
        pending.delete(clientMessageId);
        render();
        reportError(new Error("Сообщение не отправлено: нет связи"));
    }

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        const text = input.value.trim();
        if (!text || isClosed) return;
        input.value = "";
        send(text, crypto.randomUUID());
    });
    input.addEventListener("input", () => {
        // «Печатаю» не чаще раза в 2 секунды: собеседнику хватит, а соединение не засоряется.
        if (Date.now() - lastTypingSent > 2000) {
            lastTypingSent = Date.now();
            stomp().send(`/app/order.${orderId}.chat.typing`);
        }
    });
    const onVisible = () => markRead();
    document.addEventListener("visibilitychange", onVisible);

    load();

    return {
        destroy() {
            unsubscribe();
            clearTimeout(typingTimer);
            document.removeEventListener("visibilitychange", onVisible);
        },
        setClosed(value) { isClosed = value; render(); },
    };
}
