// ИИ-помощник поддержки: окно чата поверх страниц клиента. Говорит с отдельным сервисом food_delivery_agent
// через /agent/ (nginx проксирует на компьютер, где он запущен). Токен клиента уходит агенту как есть:
// агент сам проверяет его и читает заказы платформы от имени этого клиента.

import { api, get } from "./api.js";
import { session } from "./store.js";
import { html, $, toast } from "./ui.js";

export function mountAssistant(email) {
    const host = document.createElement("div");
    host.id = "assistant-root";
    document.body.append(host);

    let open = false;
    let conversationId = null;
    let busy = false;
    const messages = [];
    let suggestions = [];

    const view = () => {
        host.innerHTML = html`
            <button class="fab" data-assistant="toggle" aria-label="ИИ-помощник">🤖 <span>Помощник</span></button>
            ${open ? html`<section class="chat" aria-label="Чат с помощником">
                <div class="chat-head">
                    <span style="font-size:26px">🤖</span>
                    <div style="flex:1"><b>Помощник доставки</b><div class="small" style="opacity:.85">Отвечает по вашим заказам</div></div>
                    <button class="icon-btn" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.4)" data-assistant="reset" title="Новый диалог">↺</button>
                    <button class="icon-btn" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.4)" data-assistant="toggle" title="Закрыть">✕</button>
                </div>
                <div class="chat-body" id="chat-body">
                    ${messages.length === 0 ? html`<div class="msg bot">Здравствуйте! Спросите, где ваш заказ, что с ним происходит или почему он задерживается. Отменять заказы и возвращать деньги я не умею — для этого есть поддержка.</div>
                        ${suggestions.length ? html`<div class="row wrap">${suggestions.map((s) => html`<button class="chip" data-assistant="suggest" data-text="${s}">${s}</button>`)}</div>` : ""}` : ""}
                    ${messages.map((m) => html`<div class="msg ${m.role === "me" ? "me" : "bot"}">${m.text}${m.tools?.length ? html`<div class="tools">${m.tools.map((t) => html`<span class="badge ${t.outcome === "ok" ? "green" : "yellow"}">🔧 ${t.name}</span>`)}</div>` : ""}</div>`)}
                    ${busy ? html`<div class="msg bot typing"><span></span><span></span><span></span></div>` : ""}
                </div>
                <form class="chat-foot" data-assistant="send">
                    <input class="input" name="text" placeholder="Например: где заказ 90?" autocomplete="off" ${busy ? "disabled" : ""}>
                    <button class="btn primary" ${busy ? "disabled" : ""}>➤</button>
                </form>
            </section>` : ""}`.value;
        const body = $("#chat-body", host);
        if (body) body.scrollTop = body.scrollHeight;
        $("input[name=text]", host)?.focus();
    };

    async function loadSuggestions() {
        try {
            const me = session();
            const page = await get(`/api/v1/customers/${me.user.id}/orders?page=0&size=3`);
            suggestions = page.content.map((o) => `Где заказ ${o.id}?`);
        } catch {
            suggestions = [];
        }
        if (open) view();
    }

    async function send(text) {
        text = text.trim();
        if (!text || busy) return;
        messages.push({ role: "me", text });
        busy = true;
        view();
        try {
            const reply = await api("POST", "/agent/api/v1/chat", { body: { message: text, ...(conversationId ? { conversation_id: conversationId } : {}) } });
            conversationId = reply.conversation_id;
            messages.push({ role: "bot", text: reply.answer, tools: reply.tool_calls });
        } catch (error) {
            const offline = [0, 502, 503, 504].includes(error.status);
            messages.push({
                role: "bot",
                text: offline
                    ? "Помощник сейчас недоступен. Запустите агента и Ollama (scripts\\start.ps1 -Agent) и повторите вопрос."
                    : error.message,
            });
        } finally {
            busy = false;
            view();
        }
    }

    host.addEventListener("click", (event) => {
        const el = event.target.closest("[data-assistant]");
        if (!el) return;
        const act = el.dataset.assistant;
        if (act === "toggle") { open = !open; view(); if (open && !suggestions.length) loadSuggestions(); }
        if (act === "reset") { messages.length = 0; conversationId = null; view(); toast("Начат новый диалог"); }
        if (act === "suggest") send(el.dataset.text);
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
    return { email, destroy: () => host.remove() };
}
