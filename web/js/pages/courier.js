// Курьер: доступные доставки, свои доставки, движение по маршруту и завершение.

import { get, post } from "../api.js";
import { longPoll, pollFetch } from "../live.js";
import { mountChat } from "../chat.js";
import { mapsLink } from "../geo.js";
import { html, delegate, ago, dateTime, empty, skeleton, toast, reportError, statusBadge, modal, DELIVERY_STATUS } from "../ui.js";

// Учебный маршрут: курьер «едет» к точке клиента, пока включена отправка позиции. Координат ресторана в каталоге
// нет, поэтому старт — в паре километров к юго-западу от клиента. Без точки клиента — маршрут по центру Москвы.
const FROM = [55.7539, 37.6208];
const TO = [55.7712, 37.6502];
const START_OFFSET = [-0.012, -0.02];
const STEPS = 24;

function route(delivery) {
    const a = delivery?.deliveryAddress;
    if (a?.latitude == null) return [FROM, TO];
    const to = [Number(a.latitude), Number(a.longitude)];
    return [[to[0] + START_OFFSET[0], to[1] + START_OFFSET[1]], to];
}

export default async function courierPage({ root, me, onLeave }) {
    const uid = me.user.id;
    let tab = "available";
    let available = [];
    let mine = [];
    const riding = new Map(); // deliveryId -> { step, timer }

    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Доставки</h1></div>${skeleton(3)}</div>`.value;

    async function load() {
        [available, mine] = await Promise.all([get("/api/v1/deliveries/available").catch(() => []), get(`/api/v1/couriers/${uid}/deliveries`).catch(() => [])]);
    }

    const active = () => mine.filter((d) => d.status !== "DELIVERED");

    function card(d, mode) {
        const isRiding = riding.has(d.id);
        return html`<div class="card stack"><div class="row between"><h3>Заказ №${d.orderId}</h3>${statusBadge(d.status, DELIVERY_STATUS)}</div>
            <div class="muted small">Доставка №${d.id} · создана ${ago(d.createdAt)}${d.deliveredAt ? " · доставлена " + dateTime(d.deliveredAt) : ""}</div>
            ${d.deliveryAddress ? html`<div class="addr-line"><span>📍</span><div style="flex:1"><b>${d.deliveryAddress.address}</b>${d.deliveryAddress.details ? html`<div class="muted small">${d.deliveryAddress.details}</div>` : ""}</div>
                ${mode !== "done" ? html`<a class="btn sm ghost" href="${mapsLink(d.deliveryAddress)}" target="_blank" rel="noopener">🗺️ На карте</a>` : ""}</div>`
                : html`<div class="muted small">Адрес не указан: заказ оформлен без адреса</div>`}
            ${mode === "available" ? html`<button class="btn primary" data-act="assign" data-id="${d.id}">Взять доставку</button>` : ""}
            ${mode === "mine" ? html`<button class="btn ghost" data-act="chat" data-order="${d.orderId}">💬 Чат с клиентом</button>` : ""}
            ${d.status === "ASSIGNED" ? html`<button class="btn primary" data-act="pickup" data-id="${d.id}">📦 Забрал заказ в ресторане</button>` : ""}
            ${d.status === "PICKED_UP" ? html`<div class="row wrap"><button class="btn ${isRiding ? "danger" : ""}" data-act="ride" data-id="${d.id}">${isRiding ? "⏸ Остановить отправку позиции" : "🛵 Поехали (симуляция GPS)"}</button>
                <button class="btn green" data-act="deliver" data-id="${d.id}">✓ Доставил</button></div>
                ${isRiding ? html`<div class="muted small">Позиция уходит каждые 2 секунды: клиент видит курьера на своей карте.</div>` : ""}` : ""}</div>`;
    }

    function draw() {
        const done = mine.filter((d) => d.status === "DELIVERED");
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Доставки</h1><span class="muted right">Новые доставки приходят сразу (long polling)</span></div>
            <div class="kpis"><div class="kpi"><div class="v">${available.length}</div><div class="l">ждут курьера</div></div><div class="kpi"><div class="v">${active().length}</div><div class="l">у меня в работе</div></div><div class="kpi"><div class="v">${done.length}</div><div class="l">доставлено всего</div></div></div>
            <div class="tabs"><button class="tab ${tab === "available" ? "on" : ""}" data-act="tab" data-tab="available">Доступные (${available.length})</button><button class="tab ${tab === "mine" ? "on" : ""}" data-act="tab" data-tab="mine">Мои (${active().length})</button><button class="tab ${tab === "done" ? "on" : ""}" data-act="tab" data-tab="done">История (${done.length})</button></div>
            <div class="grid">${tab === "available" ? (available.length ? available.map((d) => card(d, "available")) : "") : tab === "mine" ? active().map((d) => card(d, "mine")) : done.map((d) => card(d, "done"))}</div>
            ${(tab === "available" && !available.length) ? empty("🛵", "Свободных доставок нет", "Они появятся, когда ресторан передаст готовый заказ.") : ""}
            ${(tab === "mine" && !active().length) ? empty("📭", "Нет активных доставок", "Возьмите доставку на вкладке «Доступные».") : ""}
            ${(tab === "done" && !done.length) ? empty("🏁", "История пуста") : ""}</div>`.value;
    }

    async function act(fn, message) {
        try { await fn(); if (message) toast(message, "ok"); } catch (error) { reportError(error); }
        await load();
        draw();
    }

    function stopRide(id) {
        const r = riding.get(id);
        if (r) { clearInterval(r.timer); riding.delete(id); }
    }

    function startRide(id) {
        const state = { step: 0, timer: null };
        const [from, to] = route(mine.find((d) => d.id === id));
        const send = async () => {
            const t = Math.min(state.step / STEPS, 1);
            // Последняя точка — ровно у клиента, без «дрожания».
            const wobble = t < 1 ? 0.0003 : 0;
            const latitude = from[0] + (to[0] - from[0]) * t + Math.sin(state.step) * wobble;
            const longitude = from[1] + (to[1] - from[1]) * t + Math.cos(state.step) * wobble;
            try {
                await post(`/api/v1/couriers/${uid}/deliveries/${id}/positions`, { latitude, longitude, speedKmh: 18 + Math.round(Math.random() * 12) });
                state.step++;
                if (state.step > STEPS) { stopRide(id); toast("Вы на месте! Нажмите «Доставил»", "ok"); draw(); }
            } catch (error) {
                stopRide(id);
                reportError(error);
                draw();
            }
        };
        state.timer = setInterval(send, 2000);
        riding.set(id, state);
        send();
    }

    delegate(root, "click", {
        tab: (el) => { tab = el.dataset.tab; draw(); },
        assign: (el) => act(() => post(`/api/v1/couriers/${uid}/deliveries/${el.dataset.id}/assign`), "Доставка ваша"),
        pickup: (el) => act(() => post(`/api/v1/couriers/${uid}/deliveries/${el.dataset.id}/pick-up`), "Заказ у вас, клиент получил уведомление"),
        deliver: (el) => { stopRide(Number(el.dataset.id)); return act(() => post(`/api/v1/couriers/${uid}/deliveries/${el.dataset.id}/deliver`), "Доставка завершена"); },
        ride: (el) => { const id = Number(el.dataset.id); riding.has(id) ? stopRide(id) : startRide(id); draw(); },
        chat: (el) => {
            let chat = null;
            modal({
                title: `Чат по заказу №${el.dataset.order}`,
                body: html`<div data-chat></div>`,
                onMount: (dialog) => {
                    chat = mountChat(dialog.querySelector("[data-chat]"), { orderId: Number(el.dataset.order), me: { id: uid } });
                    // Окно закрывают и кликом мимо него: подписку чата снимаем, как только окно исчезло со страницы.
                    const watch = new MutationObserver(() => { if (!dialog.isConnected) { chat.destroy(); watch.disconnect(); } });
                    watch.observe(document.body, { childList: true });
                },
                actions: [{ label: "Закрыть" }],
            });
        },
    });

    await load();
    draw();

    // Свободные доставки — long polling: сервер держит запрос, пока список не изменится (до 25 с), и сразу отвечает,
    // когда ресторан передаст заказ или его заберёт другой курьер. Версия списка — ETag, клиент шлёт её в If-None-Match.
    let etag = null;
    const stopPoll = longPoll(async (signal) => {
        const response = await pollFetch("/api/v1/deliveries/available/poll?timeoutSeconds=25", {
            headers: etag ? { "If-None-Match": etag } : {}, signal,
        });
        if (response.status === 304) return;
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const fresh = await response.json();
        const isFirst = etag === null;
        etag = response.headers.get("ETag");
        if (!isFirst && fresh.length > available.length) toast("Появилась новая доставка", "ok");
        available = fresh;
        if (!document.querySelector(".overlay")) draw();
    });
    // Свои доставки меняются только от действий самого курьера — их редкий опрос остаётся страховкой.
    const timer = setInterval(async () => {
        if (document.querySelector(".overlay")) return;
        try { mine = await get(`/api/v1/couriers/${uid}/deliveries`); draw(); } catch { /* повторим */ }
    }, 15000);
    onLeave(() => { clearInterval(timer); stopPoll(); [...riding.keys()].forEach(stopRide); });
}
