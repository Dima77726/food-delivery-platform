// Список заказов клиента с обновлением статусов.

import { get, post, restaurantName, menu } from "../api.js";
import { trackCart } from "../store.js";
import { html, delegate, money, emojiFor, statusBadge, ago, empty, skeleton, toast, reportError } from "../ui.js";
import { navigate } from "../nav.js";

const ACTIVE = new Set(["CREATED", "PAYMENT_PENDING", "PAID", "ACCEPTED", "COOKING", "READY_FOR_DELIVERY", "IN_DELIVERY"]);

export default async function ordersPage({ root, me, onLeave }) {
    const uid = me.user.id;
    let page = 0;
    let data = null;
    let names = {};
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Мои заказы</h1></div>${skeleton(3)}</div>`.value;

    async function load() {
        data = await get(`/api/v1/customers/${uid}/orders?page=${page}&size=10`);
        const ids = [...new Set(data.content.map((o) => o.restaurantId))];
        await Promise.all(ids.map(async (id) => { names[id] = await restaurantName(id); }));
    }

    function draw() {
        const items = data.content;
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Мои заказы</h1><span class="muted">${data.totalElements} всего</span></div>
            ${items.length ? html`<div class="stack">${items.map((o) => html`<div class="order-row" data-act="open" data-id="${o.id}">
                <div class="thumb" style="width:56px;height:56px;font-size:28px">${emojiFor(names[o.restaurantId])}</div>
                <div class="stack" style="gap:2px"><b>${names[o.restaurantId]}</b><span class="muted small">Заказ №${o.id} · ${o.itemsCount} поз. · ${ago(o.createdAt)}</span></div>
                <div class="stack" style="align-items:flex-end;gap:6px">${statusBadge(o.status)}<b>${money(o.totalAmount)}</b>
                    ${o.status === "DELIVERED" || o.status === "CANCELED" ? html`<button class="btn sm" data-act="repeat" data-id="${o.id}" data-rid="${o.restaurantId}">Повторить</button>` : ""}</div></div>`)}</div>
                ${data.totalPages > 1 ? html`<div class="row" style="justify-content:center;margin-top:20px"><button class="btn sm" data-act="prev" ${page === 0 ? "disabled" : ""}>←</button><span class="muted">${page + 1} / ${data.totalPages}</span><button class="btn sm" data-act="next" ${page + 1 >= data.totalPages ? "disabled" : ""}>→</button></div>` : ""}`
                : empty("🧾", "Заказов пока нет", html`<a class="btn primary" href="#/">Выбрать ресторан</a>`)}</div>`.value;
    }

    async function repeat(orderId, rid) {
        const order = await get(`/api/v1/customers/${uid}/orders/${orderId}`);
        const current = await menu(rid);
        const available = new Map(current.categories.flatMap((c) => c.items).map((i) => [i.id, i]));
        let added = 0;
        for (const line of order.items) {
            if (!available.get(line.menuItemId)?.available) continue;
            for (let i = 0; i < line.quantity; i++) await post(`/api/v1/customers/${uid}/restaurants/${rid}/cart`, { menuItemId: line.menuItemId, quantity: 1 });
            added++;
        }
        trackCart(uid, rid);
        toast(added ? "Блюда добавлены в корзину" : "Этих блюд сейчас нет в меню", added ? "ok" : "");
        if (added) navigate("/cart");
    }

    delegate(root, "click", {
        open: (el) => navigate(`/orders/${el.dataset.id}`),
        repeat: async (el, event) => { event.stopPropagation(); await repeat(el.dataset.id, Number(el.dataset.rid)); },
        prev: async () => { page--; await load(); draw(); },
        next: async () => { page++; await load(); draw(); },
    });

    await load();
    draw();

    // Живые статусы: пока есть незавершённые заказы, список обновляется сам.
    const timer = setInterval(async () => {
        if (!data.content.some((o) => ACTIVE.has(o.status))) return;
        try { await load(); draw(); } catch { /* повторим на следующем такте */ }
    }, 5000);
    onLeave(() => clearInterval(timer));
}
