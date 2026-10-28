// ИИ-помощник: окно чата поверх страниц для всех ролей. Говорит с отдельным сервисом food_delivery_agent
// через /agent/ (nginx проксирует на компьютер, где он запущен). Токен пользователя уходит агенту как есть:
// агент сам проверяет его, выбирает инструменты по роли и читает платформу от имени этого пользователя.
//
// Ответ приходит потоком SSE (POST /agent/api/v1/chat/stream): сначала вызовы инструментов, потом текст по
// кускам. Действия, которые меняют данные (отмена заказа, принять предложение…), помощник только готовит —
// они приходят карточкой «Подтвердить / Отмена», и выполняет их кнопка, а не модель.
// Клиенту помощник может написать и сам (оплата не прошла, заказ задерживается): long polling /inbox.

import { api, get } from "./api.js";
import { session } from "./store.js";
import { html, $, toast } from "./ui.js";

const INTRO = {
    CUSTOMER: "Здравствуйте! Спросите, где ваш заказ, как работают возвраты и промокоды, что есть в меню. Могу подготовить отмену заказа или добавить блюдо в корзину — выполнится после вашего подтверждения.",
    RESTAURANT_OWNER: "Здравствуйте! Спросите о заказах ресторана, заказах ко времени, выручке или отзывах. Могу подготовить перевод заказа на следующий этап.",
    COURIER: "Привет! Спросите о предложениях доставок, своих доставках и заработке. Могу подготовить принятие предложения.",
    ADMIN: "Здравствуйте! Спросите о заявках на возврат, промокодах, цене доставки и продажах платформы.",
};
const SUGGEST = {
    RESTAURANT_OWNER: ["Какие заказы ждут принятия?", "Что пишут в последних отзывах?", "Выручка за неделю"],
    COURIER: ["Есть предложения рядом?", "Сколько я заработал?", "Куда везти текущую доставку?"],
    ADMIN: ["Какие возвраты ждут решения?", "Сколько сейчас стоит доставка?", "Продажи за 7 дней"],
};
const SUBTITLE = { CUSTOMER: "Заказы, меню, правила", RESTAURANT_OWNER: "Заказы и выручка ресторана", COURIER: "Доставки и заработок", ADMIN: "Возвраты, промокоды, продажи" };
const TOOL_LABELS = {
    get_order: "заказ", list_my_orders: "мои заказы", search_knowledge: "база знаний", get_menu: "меню",
    get_delivery_fee: "цена доставки", get_support_policy: "правила помощника", list_my_restaurants: "рестораны",
    list_restaurant_orders: "заказы ресторана", get_scheduled_orders: "ко времени", get_reviews_summary: "отзывы",
    get_restaurant_sales: "выручка", get_my_offers: "предложения", get_my_deliveries: "мои доставки",
    get_earnings: "заработок", get_pending_refunds: "возвраты", get_promo_codes: "промокоды", get_platform_sales: "продажи",
    propose_cancel_order: "подготовка отмены", propose_add_to_cart: "подготовка корзины", propose_order_step: "подготовка этапа",
    propose_accept_offer: "подготовка доставки", propose_refund_decision: "подготовка решения",
};

/** Читает поток SSE из ответа fetch: вызывает onEvent(имя, данные) на каждое событие. */
async function readSse(response, onEvent) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let cut;
        while ((cut = buffer.indexOf("\n\n")) >= 0) {
            const block = buffer.slice(0, cut);
            buffer = buffer.slice(cut + 2);
            let name = "message";
            let data = "";
            for (const line of block.split("\n")) {
                if (line.startsWith("event: ")) name = line.slice(7);
                else if (line.startsWith("data: ")) data += line.slice(6);
            }
            if (data) onEvent(name, JSON.parse(data));
        }
    }
}

export function mountAssistant(email, role = "CUSTOMER", userId = null) {
    const host = document.createElement("div");
    host.id = "assistant-root";
    document.body.append(host);

    let open = false;
    let conversationId = null;
    let busy = false;
    let unread = 0;
    let inboxCursor = 0;
    let stopped = false;
    const messages = [];
    let suggestions = SUGGEST[role] || [];

    const toolChips = (tools) => tools?.length
        ? html`<div class="tools">${tools.map((t) => html`<span class="badge ${t.outcome === "ok" ? "green" : "yellow"}" title="${t.name}">🔧 ${TOOL_LABELS[t.name] || t.name}</span>`)}</div>` : "";

    const actionCard = (a) => html`<div class="action-card ${a.state || ""}">
        <div class="small muted">Подготовлено действие</div><b>${a.summary}</b>
        ${a.state === "done" ? html`<div class="ok-text small">✓ Выполнено</div>`
            : a.state === "canceled" ? html`<div class="muted small">Отменено</div>`
            : a.state === "failed" ? html`<div class="bad-text small">${a.error}</div>`
            : html`<div class="row"><button class="btn sm primary" data-assistant="confirm" data-id="${a.id}">Подтвердить</button><button class="btn sm ghost" data-assistant="dismiss" data-id="${a.id}">Отмена</button></div>`}</div>`;

    const bubble = (m) => html`<div class="msg ${m.role === "me" ? "me" : "bot"} ${m.proactive ? "proactive" : ""}">${m.proactive ? html`<div class="small muted">🔔 Помощник пишет первым</div>` : ""}${m.text || (m.streaming ? "" : "…")}${m.streaming && !m.text ? html`<span class="typing"><span></span><span></span><span></span></span>` : ""}${toolChips(m.tools)}${(m.actions || []).map(actionCard)}</div>`;

    const view = () => {
        host.innerHTML = html`
            <button class="fab" data-assistant="toggle" aria-label="ИИ-помощник">🤖 <span>Помощник</span>${unread ? html`<span class="fab-badge">${unread}</span>` : ""}</button>
            ${open ? html`<section class="chat" aria-label="Чат с помощником">
                <div class="chat-head">
                    <span style="font-size:26px">🤖</span>
                    <div style="flex:1"><b>Помощник</b><div class="small" style="opacity:.85">${SUBTITLE[role] || ""}</div></div>
                    <button class="icon-btn" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.4)" data-assistant="reset" title="Новый диалог">↺</button>
                    <button class="icon-btn" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.4)" data-assistant="toggle" title="Закрыть">✕</button>
                </div>
                <div class="chat-body" id="chat-body">
                    ${messages.length === 0 ? html`<div class="msg bot">${INTRO[role] || INTRO.CUSTOMER}</div>
                        ${suggestions.length ? html`<div class="row wrap">${suggestions.map((s) => html`<button class="chip" data-assistant="suggest" data-text="${s}">${s}</button>`)}</div>` : ""}` : ""}
                    ${messages.map(bubble)}
                </div>
                <form class="chat-foot" data-assistant="send">
                    <input class="input" name="text" placeholder="Спросите что-нибудь" autocomplete="off" ${busy ? "disabled" : ""}>
                    <button class="btn primary" ${busy ? "disabled" : ""}>➤</button>
                </form>
            </section>` : ""}`.value;
        const body = $("#chat-body", host);
        if (body) body.scrollTop = body.scrollHeight;
        if (!busy) $("input[name=text]", host)?.focus();
    };

    async function loadSuggestions() {
        if (role !== "CUSTOMER") return;
        try {
            const page = await get(`/api/v1/customers/${userId}/orders?page=0&size=2`);
            suggestions = [...page.content.map((o) => `Где заказ ${o.id}?`), "Как вернуть деньги за блюдо?"];
        } catch {
            suggestions = ["Как вернуть деньги за блюдо?", "Сколько стоит доставка?"];
        }
        if (open) view();
    }

    const offlineText = "Помощник сейчас недоступен. Запустите агента и Ollama (scripts\\start.ps1 -Agent) и повторите вопрос.";

    async function send(text) {
        text = text.trim();
        if (!text || busy) return;
        messages.push({ role: "me", text });
        const reply = { role: "bot", text: "", tools: [], actions: [], streaming: true };
        messages.push(reply);
        busy = true;
        view();
        const body = { message: text, ...(conversationId ? { conversation_id: conversationId } : {}) };
        try {
            const response = await fetch("/agent/api/v1/chat/stream", {
                method: "POST",
                headers: { "Content-Type": "application/json", Authorization: `Bearer ${session()?.access}` },
                body: JSON.stringify(body),
            });
            if (response.status === 401) {
                // Токен истёк: обычный запрос обновит его сам, ответ придёт целиком.
                const full = await api("POST", "/agent/api/v1/chat", { body });
                finish(reply, full);
            } else if (!response.ok) {
                throw Object.assign(new Error(`HTTP ${response.status}`), { status: response.status });
            } else {
                await readSse(response, (name, data) => {
                    if (name === "tool") reply.tools.push(data);
                    else if (name === "token") reply.text += data.text;
                    else if (name === "reset") reply.text = "";
                    else if (name === "done") finish(reply, data);
                    else if (name === "error") { reply.text = data.detail; if (data.status === 404) conversationId = null; }
                    view();
                });
            }
        } catch (error) {
            reply.text = [0, 502, 503, 504].includes(error.status ?? 0) || error instanceof TypeError ? offlineText : error.message;
        } finally {
            reply.streaming = false;
            if (!reply.text) reply.text = offlineText;
            busy = false;
            view();
        }
    }

    /** Итог хода главнее потока: проверки ответа на сервере могли заменить текст. */
    function finish(reply, data) {
        conversationId = data.conversation_id;
        reply.text = data.answer;
        reply.tools = data.tool_calls;
        reply.actions = data.actions || [];
    }

    function findAction(id) {
        for (const m of messages) for (const a of m.actions || []) if (a.id === id) return a;
        return null;
    }

    async function confirm(id) {
        const action = findAction(id);
        if (!action) return;
        try {
            const result = await api("POST", `/agent/api/v1/actions/${id}/confirm`);
            action.state = "done";
            toast(result.summary, "ok");
        } catch (error) {
            action.state = "failed";
            action.error = error.status === 404 ? "Карточка устарела — попросите помощника ещё раз" : error.message;
        }
        view();
    }

    async function dismiss(id) {
        const action = findAction(id);
        if (!action) return;
        action.state = "canceled";
        view();
        await api("DELETE", `/agent/api/v1/actions/${id}`).catch(() => {});
    }

    /** Сообщения, которые помощник пишет сам: long polling, пока виджет на странице. */
    async function pollInbox() {
        while (!stopped && role === "CUSTOMER") {
            try {
                const { messages: fresh } = await api("GET", `/agent/api/v1/inbox?after=${inboxCursor}&wait=25`);
                for (const m of fresh) {
                    inboxCursor = Math.max(inboxCursor, m.id);
                    messages.push({ role: "bot", text: m.text, proactive: true });
                    if (!open) unread++;
                }
                if (fresh.length) view();
            } catch {
                // Агент не запущен: проверим позже, без шума.
                await new Promise((resolve) => setTimeout(resolve, 30000));
            }
        }
    }

    host.addEventListener("click", (event) => {
        const el = event.target.closest("[data-assistant]");
        if (!el) return;
        const act = el.dataset.assistant;
        if (act === "toggle") { open = !open; if (open) unread = 0; view(); if (open && role === "CUSTOMER") loadSuggestions(); }
        if (act === "reset") { messages.length = 0; conversationId = null; view(); toast("Начат новый диалог"); }
        if (act === "suggest") send(el.dataset.text);
        if (act === "confirm") confirm(el.dataset.id);
        if (act === "dismiss") dismiss(el.dataset.id);
    });
    host.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-assistant=send]");
        if (!form) return;
        event.preventDefault();
        const input = form.elements.text;
        const text = input.value;
        input.value = "";
        send(text);
    });

    view();
    pollInbox();
    return { email, role, destroy: () => { stopped = true; host.remove(); } };
}
