// Страница заказа: этапы, хронология, оплата, трек курьера, отмена и отзыв.

import { get, post, restaurantName } from "../api.js";
import { orderMap, loadLeaflet, formatAddress } from "../geo.js";
import { html, raw, delegate, money, emojiFor, statusBadge, dateTime, timeOnly, empty, skeleton, toast, reportError, modal, confirmDialog, DELIVERY_STATUS } from "../ui.js";

const STEPS = [["CREATED", "Создан"], ["PAID", "Оплачен"], ["ACCEPTED", "Принят"], ["COOKING", "Готовится"], ["READY_FOR_DELIVERY", "Ждёт курьера"], ["IN_DELIVERY", "В пути"], ["DELIVERED", "Доставлен"]];
const STEP_INDEX = { CREATED: 0, PAYMENT_PENDING: 0, PAID: 1, ACCEPTED: 2, COOKING: 3, READY_FOR_DELIVERY: 4, IN_DELIVERY: 5, DELIVERED: 6 };
const FINAL = new Set(["DELIVERED", "CANCELED"]);
const TAGS = ["быстро", "вкусно", "горячо", "вежливый курьер", "красиво упаковано"];

export default async function orderPage({ root, params, me, onLeave }) {
    const uid = me.user.id;
    const oid = params.id;
    root.innerHTML = html`<div class="container">${skeleton(2)}</div>`.value;

    let order; let events = []; let payments = []; let track = null; let name = "";
    // Карта создаётся один раз и переносится между перерисовками; если Leaflet не загрузился, остаётся схема.
    let leaflet = null;
    let map = null;
    loadLeaflet().then(() => { leaflet = true; }, () => { leaflet = false; }).finally(() => order && draw());

    async function load() {
        order = await get(`/api/v1/customers/${uid}/orders/${oid}`);
        [events, payments, name] = await Promise.all([
            get(`/api/v1/customers/${uid}/orders/${oid}/events`).catch(() => []),
            get(`/api/v1/customers/${uid}/orders/${oid}/payments`).catch(() => []),
            restaurantName(order.restaurantId),
        ]);
        track = ["IN_DELIVERY", "DELIVERED"].includes(order.status)
            ? await get(`/api/v1/customers/${uid}/orders/${oid}/track?limit=200`).catch(() => null)
            : null;
        // Трек отдаётся от новых точек к старым, а маршрут рисуется от начала к курьеру.
        track?.positions?.sort((a, b) => new Date(a.recordedAt ?? a.createdAt) - new Date(b.recordedAt ?? b.createdAt));
    }

    const trackPoints = (positions) => positions
        .map((p) => [p.latitude ?? p.lat, p.longitude ?? p.lon ?? p.lng])
        .filter(([lat, lon]) => Number.isFinite(lat) && Number.isFinite(lon));

    function destination() {
        const a = order.deliveryAddress;
        return a?.latitude != null ? [Number(a.latitude), Number(a.longitude)] : null;
    }

    const mapSlot = () => html`<div class="map" data-order-map><div class="map-fallback muted small">Загружаем карту…</div></div>`;

    function mountMap() {
        const slot = root.querySelector("[data-order-map]");
        if (!slot || leaflet === false) return;
        map ??= orderMap();
        map.mount(slot);
        map.update({ destination: destination(), path: trackPoints(track?.positions || []) });
    }

    function mapSvg(positions) {
        const pts = positions.map((p) => [p.longitude ?? p.lon ?? p.lng, p.latitude ?? p.lat]).filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y));
        if (!pts.length) return html`<div class="map center muted" style="display:grid;place-content:center">Курьер ещё не отправил координаты</div>`;
        const xs = pts.map((p) => p[0]); const ys = pts.map((p) => p[1]);
        const minX = Math.min(...xs); const maxX = Math.max(...xs); const minY = Math.min(...ys); const maxY = Math.max(...ys);
        const spanX = Math.max(maxX - minX, 1e-4); const spanY = Math.max(maxY - minY, 1e-4);
        const sx = (x) => 30 + ((x - minX) / spanX) * 340;
        const sy = (y) => 190 - ((y - minY) / spanY) * 160;
        const path = pts.map(([x, y], i) => `${i ? "L" : "M"}${sx(x).toFixed(1)},${sy(y).toFixed(1)}`).join(" ");
        const [lx, ly] = pts[pts.length - 1];
        return html`<svg class="map" viewBox="0 0 400 220" role="img" aria-label="Маршрут курьера">
            <path d="${path}" fill="none" stroke="var(--primary)" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="1 9" />
            <circle cx="${sx(pts[0][0])}" cy="${sy(pts[0][1])}" r="7" fill="var(--accent)"/>
            <text x="${sx(lx)}" y="${sy(ly) - 12}" font-size="26" text-anchor="middle">🛵</text></svg>`;
    }

    function draw() {
        const canceled = order.status === "CANCELED";
        const idx = STEP_INDEX[order.status] ?? 0;
        const canCancel = ["CREATED", "PAYMENT_PENDING", "PAID"].includes(order.status);
        const showTrack = order.status === "IN_DELIVERY" || (order.status === "DELIVERED" && track?.positions?.length);
        const address = order.deliveryAddress;
        root.innerHTML = html`<div class="container">
            <div class="page-head"><a class="btn sm" href="#/orders">← Заказы</a><h1>Заказ №${order.id}</h1>${statusBadge(order.status)}<span class="muted right">${emojiFor(name)} ${name}</span></div>
            <div class="split"><div class="stack lg">
                <div class="card">
                    ${canceled ? html`<div class="banner" style="background:color-mix(in srgb,var(--danger) 14%,transparent)">Заказ отменён${payments.some((p) => p.status === "SUCCEEDED") ? ". Деньги будут возвращены." : "."}</div>`
                        : html`<div class="stepper">${STEPS.map(([key, label], i) => html`<div class="step ${i < idx || order.status === "DELIVERED" ? "done" : i === idx ? "now" : ""}">${label}</div>`)}</div>`}
                    ${order.status === "PAYMENT_PENDING" ? html`<p class="muted small center">Платёж проводится, это занимает несколько секунд…</p>` : ""}
                </div>
                ${showTrack ? html`<div class="card stack"><h3>🛵 Курьер ${order.status === "IN_DELIVERY" ? "в пути" : "доставил заказ"}</h3>${leaflet !== false ? mapSlot() : mapSvg(track?.positions || [])}
                    ${address ? html`<div class="addr-line muted small"><span>📍</span><span>${formatAddress(address)}</span></div>` : ""}
                    <span class="muted small">${track?.positions?.length ? `Обновлено в ${timeOnly(track.positions[track.positions.length - 1].recordedAt ?? track.positions[track.positions.length - 1].createdAt)}` : ""}</span></div>` : ""}
                ${address && !showTrack ? html`<div class="card stack"><h3>📍 Адрес доставки</h3><div><b>${address.address}</b>${address.details ? html`<div class="muted small">${address.details}</div>` : ""}</div>
                    ${destination() && leaflet !== false && !canceled ? mapSlot() : ""}</div>` : ""}
                <div class="card stack"><h3>Состав заказа</h3>
                    ${order.items.map((i) => html`<div class="cart-line"><span style="font-size:26px">${emojiFor(i.menuItemName)}</span><div style="flex:1"><b>${i.menuItemName}</b><div class="muted small">${money(i.price)} × ${i.quantity}</div></div><b>${money(i.lineTotal)}</b></div>`)}
                    <div class="total"><span>Итого</span><span>${money(order.totalAmount)}</span></div></div>
                <div class="card stack"><h3>Хронология</h3><ul class="timeline">${[...events].reverse().map((e) => html`<li><b>${e.description}</b><div class="muted small">${dateTime(e.createAt)}</div></li>`)}</ul></div>
            </div>
            <aside class="stack sticky">
                <div class="card stack"><h3>Оплата</h3>${payments.length ? payments.map((p) => html`<div class="row between"><span>${p.method === "CARD" ? "💳 Картой" : "💵 Наличными"}</span>
                    <span class="badge ${p.status === "SUCCEEDED" ? "green" : p.status === "FAILED" ? "red" : "yellow"}">${{ SUCCEEDED: "Оплачено", FAILED: "Отказ", REFUNDED: "Возврат", PENDING: "Ожидает" }[p.status] || p.status}</span></div>${p.failureReason ? html`<div class="err small">${p.failureReason}</div>` : ""}`) : html`<p class="muted">Платежей пока нет</p>`}</div>
                ${order.status === "DELIVERED" ? html`<button class="btn primary block" data-act="review">⭐ Оставить отзыв</button>` : ""}
                ${canCancel ? html`<button class="btn danger block" data-act="cancel">Отменить заказ</button>` : ""}
                ${["CREATED"].includes(order.status) ? html`<button class="btn primary block" data-act="pay">Оплатить</button>` : ""}
            </aside></div></div>`.value;
        mountMap();
    }

    function reviewDialog() {
        let rating = 5;
        const dishRating = Object.fromEntries(order.items.map((i) => [i.menuItemId, 5]));
        const tags = new Set();
        const stars = () => [1, 2, 3, 4, 5].map((n) => `<span class="${n <= rating ? "on" : ""}" data-star="${n}">★</span>`).join("");
        modal({
            title: "Как всё прошло?",
            body: html`<div class="stack"><div class="star-pick" id="stars">${raw(stars())}</div>
                <div class="field"><label>Комментарий</label><textarea class="input" id="comment" placeholder="Что понравилось или что можно улучшить"></textarea></div>
                <div class="row wrap">${TAGS.map((t) => html`<button type="button" class="chip" data-tag="${t}">#${t}</button>`)}</div>
                <div class="stack"><b class="small muted">Оценка блюд</b>${order.items.map((i) => html`<div class="row between"><span>${emojiFor(i.menuItemName)} ${i.menuItemName}</span>
                    <select class="input" style="width:90px" data-dish="${i.menuItemId}">${[5, 4, 3, 2, 1].map((n) => html`<option value="${n}">${n} ★</option>`)}</select></div>`)}</div></div>`,
            onMount: (el) => {
                el.addEventListener("click", (e) => {
                    const star = e.target.closest("[data-star]");
                    if (star) { rating = Number(star.dataset.star); el.querySelector("#stars").innerHTML = stars(); }
                    const tag = e.target.closest("[data-tag]");
                    if (tag) { tags.has(tag.dataset.tag) ? tags.delete(tag.dataset.tag) : tags.add(tag.dataset.tag); tag.classList.toggle("on"); }
                });
                el.addEventListener("change", (e) => { if (e.target.dataset.dish) dishRating[e.target.dataset.dish] = Number(e.target.value); });
            },
            actions: [
                { label: "Позже" },
                {
                    label: "Отправить", kind: "primary", close: false,
                    onClick: async ({ close, root: dialog }) => {
                        try {
                            await post(`/api/v1/customers/${uid}/orders/${oid}/review`, {
                                rating,
                                comment: dialog.querySelector("#comment").value.trim() || null,
                                tags: [...tags],
                                dishes: order.items.map((i) => ({ menuItemId: i.menuItemId, menuItemName: i.menuItemName, rating: dishRating[i.menuItemId] })),
                            });
                            toast("Спасибо за отзыв!", "ok");
                            close();
                        } catch (error) {
                            reportError(error.status === 409 ? new Error("Отзыв на этот заказ уже оставлен") : error);
                        }
                        return false;
                    },
                },
            ],
        });
    }

    delegate(root, "click", {
        cancel: async () => {
            if (!(await confirmDialog("Отменить заказ?", "Если он уже оплачен, деньги вернутся.", "Отменить заказ", "danger"))) return;
            order = await post(`/api/v1/customers/${uid}/orders/${oid}/cancel`);
            toast("Заказ отменён");
            await load();
            draw();
        },
        pay: async () => {
            await post(`/api/v1/customers/${uid}/orders/${oid}/payments`, { method: "CARD", idempotencyKey: crypto.randomUUID() });
            toast("Оплата запущена");
            await load();
            draw();
        },
        review: reviewDialog,
    });

    try {
        await load();
    } catch (error) {
        root.innerHTML = html`<div class="container">${empty("😕", "Заказ не найден", error.message)}</div>`.value;
        return;
    }
    draw();

    const timer = setInterval(async () => {
        if (FINAL.has(order.status) && !document.querySelector(".overlay")) return;
        if (document.querySelector(".overlay")) return;
        try { await load(); draw(); } catch { /* повторим */ }
    }, 4000);
    onLeave(() => { clearInterval(timer); map?.destroy(); });
}
