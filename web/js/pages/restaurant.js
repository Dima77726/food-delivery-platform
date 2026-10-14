// Страница ресторана: меню по категориям, добавление в корзину, отзывы.

import { get, post, patch, del, restaurant, menu } from "../api.js";
import { trackCart } from "../store.js";
import { html, delegate, plural, money, emojiFor, coverStyle, skeleton, empty, ago, toast, reportError } from "../ui.js";
import { navigate } from "../nav.js";

export default async function restaurantPage({ root, params, me, path, refreshChrome }) {
    const rid = Number(params.id);
    const isCustomer = me?.role === "CUSTOMER";
    root.innerHTML = html`<div class="container">${skeleton(3)}</div>`.value;

    let info; let data; let summary; let cart = null; let tab = "menu"; let reviews = null;
    try {
        [info, data, summary] = await Promise.all([restaurant(rid), menu(rid), get(`/api/v1/restaurants/${rid}/reviews/summary`, { auth: false }).catch(() => null)]);
    } catch (error) {
        root.innerHTML = html`<div class="container">${empty("😕", "Ресторан не найден", error.message)}</div>`.value;
        return;
    }

    const loadCart = async () => {
        if (!isCustomer) return;
        try { cart = await get(`/api/v1/customers/${me.user.id}/restaurants/${rid}/cart`); } catch { cart = null; }
    };
    await loadCart();

    const lineOf = (itemId) => cart?.items?.find((i) => i.menuItemId === itemId);
    const cartTotal = () => (cart?.items || []).reduce((s, i) => s + i.lineTotal, 0);
    const cartCount = () => (cart?.items || []).reduce((s, i) => s + i.quantity, 0);

    function itemRow(item, categoryName) {
        const line = lineOf(item.id);
        let action;
        if (!item.available) action = html`<span class="badge">Нет в наличии</span>`;
        else if (!info.active) action = "";
        else if (!isCustomer) action = html`<a class="btn sm" href="#/login?next=${encodeURIComponent(path)}">${me ? "Заказывает клиент" : "Войти и заказать"}</a>`;
        else if (!line) action = html`<button class="btn primary sm" data-act="add" data-id="${item.id}">В корзину</button>`;
        else action = html`<div class="qty"><button data-act="dec" data-id="${item.id}">−</button><span>${line.quantity}</span><button data-act="inc" data-id="${item.id}">+</button></div>`;
        return html`<div class="menu-item ${item.available ? "" : "off"}">
            <div class="thumb">${item.imageUrl ? html`<img src="${item.imageUrl}" alt="${item.name}" loading="lazy">` : emojiFor(item.name, categoryName, item.description)}</div>
            <div class="stack" style="gap:4px"><b>${item.name}</b><span class="muted small">${item.description || ""}</span><span class="price">${money(item.price)}</span></div>
            <div class="act">${action}</div></div>`;
    }

    function draw() {
        const categories = data.categories.filter((c) => c.items.length);
        root.innerHTML = html`<div class="container">
            <div class="card pad0" style="margin-bottom:22px">
                <div class="rest-cover" style="${coverStyle(info.name)};height:150px;font-size:80px">${emojiFor(info.name, info.description)}
                    <span class="tag badge white">${info.active ? "Открыто" : "Закрыто"}</span></div>
                <div style="padding:18px 22px" class="row wrap between">
                    <div class="stack" style="gap:4px"><h1>${info.name}</h1><span class="muted">📍 ${info.city}${info.description ? html` · ${info.description}` : ""}</span></div>
                    <div class="stack" style="align-items:flex-end;gap:2px">
                        ${summary && summary.total ? html`<div class="stars" style="font-size:20px">★ ${summary.average.toFixed(1)}</div><span class="muted small">${summary.total} ${plural(summary.total, ["отзыв", "отзыва", "отзывов"])}</span>` : html`<span class="muted small">Пока без отзывов</span>`}
                    </div>
                </div>
            </div>
            ${!info.active ? html`<div class="banner" style="margin-bottom:16px">Ресторан сейчас закрыт — заказы не принимаются.</div>` : ""}
            <div class="tabs"><button class="tab ${tab === "menu" ? "on" : ""}" data-act="tab" data-tab="menu">Меню</button><button class="tab ${tab === "reviews" ? "on" : ""}" data-act="tab" data-tab="reviews">Отзывы${summary?.total ? ` (${summary.total})` : ""}</button></div>
            ${tab === "menu" ? html`<div class="split"><div class="stack lg">
                ${categories.length > 1 ? html`<div class="cat-tabs">${categories.map((c) => html`<button class="chip" data-act="jump" data-cat="${c.id}">${c.name}</button>`)}</div>` : ""}
                ${categories.map((c) => html`<section id="cat-${c.id}" class="stack"><h2>${c.name}</h2>${c.items.map((i) => itemRow(i, c.name))}</section>`)}
                ${categories.length ? "" : empty("🍽️", "Меню пока пустое")}
            </div>${isCustomer ? cartPanel() : html`<div></div>`}</div>` : reviewsTab()}
        </div>`.value;
    }

    function cartPanel() {
        return html`<aside class="card sticky stack">
            <h3>🛒 Ваш заказ</h3>
            ${cartCount() ? html`${cart.items.map((i) => html`<div class="cart-line"><div style="flex:1"><b>${i.menuItemName}</b><div class="muted small">${money(i.price)} × ${i.quantity}</div></div><b>${money(i.lineTotal)}</b></div>`)}
                <div class="total"><span>Итого</span><span>${money(cartTotal())}</span></div>
                <a class="btn primary block" href="#/cart">Перейти к оформлению</a>` : html`<p class="muted">Корзина пуста. Добавьте блюда из меню.</p>`}
        </aside>`;
    }

    function reviewsTab() {
        if (!reviews) return skeleton(2);
        if (!reviews.content.length) return empty("💬", "Отзывов пока нет", "Оставить отзыв можно после доставки заказа.");
        return html`<div class="stack">
            ${summary?.topTags?.length ? html`<div class="row wrap">${summary.topTags.map((t) => html`<span class="chip">#${t.tag ?? t}</span>`)}</div>` : ""}
            ${reviews.content.map((r) => html`<div class="card"><div class="row between"><span class="stars">${"★".repeat(r.rating)}${"☆".repeat(5 - r.rating)}</span><span class="muted small">${ago(r.createdAt)}</span></div>
                ${r.comment ? html`<p style="margin-top:8px">${r.comment}</p>` : ""}
                ${r.dishes?.length ? html`<div class="row wrap" style="margin-top:8px">${r.dishes.map((d) => html`<span class="badge">${emojiFor(d.menuItemName)} ${d.menuItemName} · ${d.rating}★</span>`)}</div>` : ""}</div>`)}</div>`;
    }

    async function change(itemId, op) {
        const item = data.categories.flatMap((c) => c.items).find((i) => i.id === itemId);
        const line = lineOf(itemId);
        try {
            if (op === "add") cart = await post(`/api/v1/customers/${me.user.id}/restaurants/${rid}/cart`, { menuItemId: itemId, quantity: 1 });
            else if (op === "inc") cart = await patch(`/api/v1/customers/${me.user.id}/restaurants/${rid}/cart/items/${line.id}`, { quantity: line.quantity + 1 });
            else if (line.quantity <= 1) cart = await del(`/api/v1/customers/${me.user.id}/restaurants/${rid}/cart/items/${line.id}`);
            else cart = await patch(`/api/v1/customers/${me.user.id}/restaurants/${rid}/cart/items/${line.id}`, { quantity: line.quantity - 1 });
            if (cart && typeof cart === "object") trackCart(me.user.id, rid);
            else await loadCart();
            if (op === "add") toast(`«${item.name}» добавлено в корзину`, "ok");
        } catch (error) {
            reportError(error);
            await loadCart();
        }
        draw();
        refreshChrome();
    }

    delegate(root, "click", {
        add: (el) => change(Number(el.dataset.id), "add"),
        inc: (el) => change(Number(el.dataset.id), "inc"),
        dec: (el) => change(Number(el.dataset.id), "dec"),
        jump: (el) => document.getElementById(`cat-${el.dataset.cat}`)?.scrollIntoView({ behavior: "smooth", block: "start" }),
        tab: async (el) => {
            tab = el.dataset.tab;
            draw();
            if (tab === "reviews" && !reviews) {
                reviews = await get(`/api/v1/restaurants/${rid}/reviews?page=0&size=30`, { auth: false }).catch(() => ({ content: [] }));
                draw();
            }
        },
    });

    draw();
}
