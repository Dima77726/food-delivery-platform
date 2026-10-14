// Курьер: доступные доставки, свои доставки, движение по маршруту и завершение.

import { get, post } from "../api.js";
import { html, delegate, ago, dateTime, empty, skeleton, toast, reportError, statusBadge, DELIVERY_STATUS } from "../ui.js";

// Учебный маршрут по центру Москвы: курьер «едет» от ресторана к клиенту, пока включена отправка позиции.
const FROM = [55.7539, 37.6208];
const TO = [55.7712, 37.6502];
const STEPS = 24;

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
            ${mode === "available" ? html`<button class="btn primary" data-act="assign" data-id="${d.id}">Взять доставку</button>` : ""}
            ${d.status === "ASSIGNED" ? html`<button class="btn primary" data-act="pickup" data-id="${d.id}">📦 Забрал заказ в ресторане</button>` : ""}
            ${d.status === "PICKED_UP" ? html`<div class="row wrap"><button class="btn ${isRiding ? "danger" : ""}" data-act="ride" data-id="${d.id}">${isRiding ? "⏸ Остановить отправку позиции" : "🛵 Поехали (симуляция GPS)"}</button>
                <button class="btn green" data-act="deliver" data-id="${d.id}">✓ Доставил</button></div>
                ${isRiding ? html`<div class="muted small">Позиция уходит каждые 2 секунды: клиент видит курьера на своей карте.</div>` : ""}` : ""}</div>`;
    }

    function draw() {
        const done = mine.filter((d) => d.status === "DELIVERED");
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Доставки</h1><span class="muted right">Обновляется автоматически</span></div>
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
        const send = async () => {
            const t = Math.min(state.step / STEPS, 1);
            const latitude = FROM[0] + (TO[0] - FROM[0]) * t + Math.sin(state.step) * 0.0003;
            const longitude = FROM[1] + (TO[1] - FROM[1]) * t + Math.cos(state.step) * 0.0003;
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
    });

    await load();
    draw();

    const timer = setInterval(async () => { try { await load(); draw(); } catch { /* повторим */ } }, 6000);
    onLeave(() => { clearInterval(timer); [...riding.keys()].forEach(stopRide); });
}
