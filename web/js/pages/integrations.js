// Вкладка «Интеграции» в кабинете ресторана: ключи API партнёров и исходящие вебхуки с журналом доставок.
// Ключи живут в identity-service (/api/v1/auth/api-keys), вебхуки — в notification-service
// (/api/v1/restaurants/{id}/webhooks). Полный ключ и секрет подписи показываются один раз — сразу после выпуска.

import { get, post, del } from "../api.js";
import { html, modal, confirmDialog, ago, dateTime, empty, skeleton, toast, reportError } from "../ui.js";

const EVENTS = [
    ["order.paid", "Оплачен"], ["order.accepted", "Принят"], ["order.cooking", "Готовится"],
    ["order.ready_for_delivery", "Готов к выдаче"], ["order.in_delivery", "В пути"], ["order.delivered", "Доставлен"],
    ["order.canceled", "Отменён"], ["order.created", "Создан"], ["order.payment_pending", "Ждёт оплаты"],
];
const SINK_URL = "http://webhook-sink:8000/hooks/ok";
const STATUS_BADGE = { SUCCEEDED: ["green", "доставлено"], PENDING: ["yellow", "ждёт повтора"], FAILED: ["red", "не доставлено"] };

export function integrationsTab({ rid, getState, redraw }) {
    async function load() {
        const [keys, hooks] = await Promise.all([
            get("/api/v1/auth/api-keys").catch((error) => ({ error })),
            get(`/api/v1/restaurants/${rid}/webhooks`).catch((error) => ({ error })),
        ]);
        getState().integrations = { keys, hooks };
    }

    const reload = async () => { await load(); redraw(); };

    function keysCard(keys) {
        if (keys.error) return html`<div class="card"><h3>🔑 Ключи API</h3><p class="err">${keys.error.message}</p></div>`;
        return html`<div class="card stack"><div class="row between"><h3>🔑 Ключи API</h3><button class="btn sm primary" data-act="key-new">Выпустить ключ</button></div>
            <p class="muted small">Ключ для кассы или вашей системы: обменивается на 15-минутный токен
                (<code>POST /api/v1/auth/api-keys/token</code> с заголовком <code>X-Api-Key</code>) и действует от вашего имени.</p>
            ${keys.length ? keys.map((k) => html`<div class="row between" style="padding:6px 0;border-top:1px solid var(--border)">
                <div><b>${k.name}</b> <code class="muted">${k.prefix}…</code><div class="muted small">выпущен ${ago(k.createdAt)} · ${k.lastUsedAt ? "использован " + ago(k.lastUsedAt) : "ещё не использовался"}</div></div>
                ${k.revokedAt ? html`<span class="badge red">отозван</span>` : html`<button class="btn sm danger" data-act="key-revoke" data-id="${k.id}">Отозвать</button>`}</div>`)
                : html`<p class="muted">Ключей пока нет.</p>`}</div>`;
    }

    function hooksCard(hooks) {
        if (hooks.error) {
            const off = hooks.error.status === 503;
            return html`<div class="card"><h3>🪝 Вебхуки</h3><p class="${off ? "muted" : "err"}">${off ? "Вебхуки на платформе выключены: администратору нужно задать WEBHOOK_SECRET_KEY." : hooks.error.message}</p></div>`;
        }
        return html`<div class="card stack"><div class="row between"><h3>🪝 Вебхуки</h3><button class="btn sm primary" data-act="hook-new">Добавить вебхук</button></div>
            <p class="muted small">Платформа шлёт POST на ваш адрес при каждом событии заказа, подписывая его по
                <a href="https://www.standardwebhooks.com" target="_blank" rel="noopener">Standard Webhooks</a>. Неудачные доставки повторяются
                (10 с, 1 мин, 5 мин, 30 мин, 2 ч), после 20 провалов подряд подписка выключается.
                Для проверки есть приёмник <a href="http://localhost:8095" target="_blank" rel="noopener">localhost:8095</a>.</p>
            ${hooks.length ? hooks.map((h) => html`<div class="stack" style="gap:6px;padding:10px 0;border-top:1px solid var(--border)">
                <div class="row between"><code style="overflow-wrap:anywhere">${h.url}</code>${h.active ? html`<span class="badge green">включён</span>` : html`<span class="badge red">выключен</span>`}</div>
                ${h.description ? html`<div class="muted small">${h.description}</div>` : ""}
                <div class="row wrap">${h.eventTypes.map((t) => html`<span class="chip">${t}</span>`)}</div>
                ${h.disabledReason ? html`<div class="err small">${h.disabledReason}</div>` : ""}
                ${h.consecutiveFailures ? html`<div class="muted small">Провалов подряд: ${h.consecutiveFailures}</div>` : ""}
                <div class="row wrap"><button class="btn sm" data-act="hook-test" data-id="${h.id}">Пробное событие</button>
                    <button class="btn sm" data-act="hook-log" data-id="${h.id}">Журнал</button>
                    <button class="btn sm ghost" data-act="hook-rotate" data-id="${h.id}">Новый секрет</button>
                    ${h.active ? "" : html`<button class="btn sm green" data-act="hook-enable" data-id="${h.id}">Включить</button>`}
                    <button class="btn sm danger" data-act="hook-delete" data-id="${h.id}">Удалить</button></div></div>`)
                : html`<p class="muted">Подписок пока нет.</p>`}</div>`;
    }

    function view() {
        const data = getState().integrations;
        if (!data) return skeleton(2);
        return html`<div class="grid two">${keysCard(data.keys)}${hooksCard(data.hooks)}</div>`;
    }

    /** Секрет показывается один раз: окно с кнопкой «Скопировать». */
    function showOnce(title, label, value, note) {
        modal({
            title,
            body: html`<div class="stack"><p class="muted">${note}</p><div class="field"><label>${label}</label>
                <input class="input" readonly value="${value}" onfocus="this.select()"></div></div>`,
            actions: [
                { label: "Скопировать", onClick: async () => { await navigator.clipboard?.writeText(value); toast("Скопировано", "ok"); return false; } },
                { label: "Я сохранил", kind: "primary" },
            ],
        });
    }

    async function journal(id) {
        const render = (items) => items.length ? html`<div class="stack" style="max-height:60vh;overflow:auto">${items.map((d) => {
            const [color, label] = STATUS_BADGE[d.status] || ["", d.status];
            return html`<div class="card stack" style="gap:4px;padding:10px">
                <div class="row between"><b>${d.eventType}</b><span class="badge ${color}">${label}</span></div>
                <div class="muted small">${dateTime(d.createdAt)} · попыток: ${d.attempts}${d.lastStatusCode ? " · код " + d.lastStatusCode : ""}${d.nextAttemptAt ? " · повтор " + dateTime(d.nextAttemptAt) : ""}</div>
                ${d.lastError ? html`<div class="err small">${d.lastError}</div>` : ""}
                <div class="muted small">webhook-id: <code>${d.webhookId}</code></div>
                ${d.status !== "PENDING" ? html`<button class="btn sm" data-redeliver="${d.id}">Доставить заново</button>` : ""}</div>`;
        })}</div>` : empty("📭", "Доставок пока не было", "Отправьте пробное событие или дождитесь заказа.");

        let items = await get(`/api/v1/restaurants/${rid}/webhooks/${id}/deliveries?limit=50`);
        const dialog = modal({
            title: "Журнал доставок", wide: true, body: html`<div data-journal>${render(items)}</div>`,
            onMount: (root) => root.addEventListener("click", async (event) => {
                const button = event.target.closest("[data-redeliver]");
                if (!button) return;
                try {
                    await post(`/api/v1/restaurants/${rid}/webhooks/${id}/deliveries/${button.dataset.redeliver}/redeliver`);
                    toast("Доставка поставлена заново", "ok");
                    items = await get(`/api/v1/restaurants/${rid}/webhooks/${id}/deliveries?limit=50`);
                    root.querySelector("[data-journal]").innerHTML = render(items).value;
                } catch (error) { reportError(error); }
            }),
            actions: [{
                label: "Обновить", close: false,
                onClick: async ({ root }) => {
                    items = await get(`/api/v1/restaurants/${rid}/webhooks/${id}/deliveries?limit=50`);
                    root.querySelector("[data-journal]").innerHTML = render(items).value;
                    return false;
                },
            }, { label: "Закрыть", kind: "primary" }],
        });
        return dialog;
    }

    const handlers = {
        "key-new": () => modal({
            title: "Новый ключ API",
            body: html`<div class="field"><label>Название</label><input class="input" id="key-name" placeholder="Касса на Тверской" maxlength="100"></div>`,
            actions: [{ label: "Отмена" }, {
                label: "Выпустить", kind: "primary", close: false,
                onClick: async ({ close, root }) => {
                    try {
                        const created = await post("/api/v1/auth/api-keys", { name: root.querySelector("#key-name").value.trim() || "Ключ" });
                        close();
                        showOnce("Ключ выпущен", "Ключ API", created.key, "Сохраните ключ сейчас: в платформе хранится только его хеш, показать его снова нельзя.");
                        await reload();
                    } catch (error) { reportError(error); }
                    return false;
                },
            }],
        }),
        "key-revoke": async (el) => {
            if (!(await confirmDialog("Отозвать ключ?", "Интеграция с этим ключом перестанет получать новые токены (уже выданный доживёт до 15 минут).", "Отозвать", "danger"))) return;
            await del(`/api/v1/auth/api-keys/${el.dataset.id}`);
            toast("Ключ отозван");
            await reload();
        },
        "hook-new": () => modal({
            title: "Новый вебхук",
            body: html`<div class="stack"><div class="field"><label>Адрес (https; в локальном стеке можно приёмник)</label>
                <input class="input" id="hook-url" value="${SINK_URL}"></div>
                <div class="field"><label>Описание</label><input class="input" id="hook-desc" maxlength="200" placeholder="Например, CRM"></div>
                <div class="field"><label>События</label><div class="row wrap">
                    <label class="chip"><input type="checkbox" value="*" data-ev> все</label>
                    ${EVENTS.map(([type, label]) => html`<label class="chip"><input type="checkbox" value="${type}" data-ev ${["order.paid", "order.delivered", "order.canceled"].includes(type) ? "checked" : ""}> ${label}</label>`)}</div></div></div>`,
            actions: [{ label: "Отмена" }, {
                label: "Создать", kind: "primary", close: false,
                onClick: async ({ close, root }) => {
                    const eventTypes = [...root.querySelectorAll("[data-ev]:checked")].map((c) => c.value);
                    try {
                        const created = await post(`/api/v1/restaurants/${rid}/webhooks`, {
                            url: root.querySelector("#hook-url").value.trim(),
                            description: root.querySelector("#hook-desc").value.trim() || null,
                            eventTypes: eventTypes.includes("*") ? ["*"] : eventTypes,
                        });
                        close();
                        showOnce("Вебхук создан", "Секрет подписи", created.secret, "Им получатель проверяет заголовок webhook-signature. Сохраните его сейчас — показать снова нельзя (можно выпустить новый).");
                        await reload();
                    } catch (error) { reportError(error); }
                    return false;
                },
            }],
        }),
        "hook-test": async (el) => {
            await post(`/api/v1/restaurants/${rid}/webhooks/${el.dataset.id}/test`);
            toast("Пробное событие отправлено — смотрите журнал", "ok");
        },
        "hook-log": (el) => journal(el.dataset.id).catch(reportError),
        "hook-rotate": async (el) => {
            if (!(await confirmDialog("Выпустить новый секрет?", "Сутки запросы будут подписаны и старым, и новым — успейте обновить получателя.", "Выпустить"))) return;
            const rotated = await post(`/api/v1/restaurants/${rid}/webhooks/${el.dataset.id}/rotate-secret`);
            showOnce("Новый секрет", "Секрет подписи", rotated.secret, "Старый секрет действует ещё 24 часа.");
        },
        "hook-enable": async (el) => {
            await post(`/api/v1/restaurants/${rid}/webhooks/${el.dataset.id}/enable`);
            toast("Подписка включена", "ok");
            await reload();
        },
        "hook-delete": async (el) => {
            if (!(await confirmDialog("Удалить вебхук?", "Журнал доставок удалится вместе с ним.", "Удалить", "danger"))) return;
            await del(`/api/v1/restaurants/${rid}/webhooks/${el.dataset.id}`);
            toast("Вебхук удалён");
            await reload();
        },
    };

    return { load, view, handlers };
}
