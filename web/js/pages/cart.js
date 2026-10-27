// Корзины клиента (по одной на ресторан), оформление заказа и оплата.

import { get, post, patch, del, restaurant, restaurantName } from "../api.js";
import { addressPicker, rememberAddress } from "../geo.js";
import { cartRestaurants, untrackCart } from "../store.js";
import { html, $, delegate, money, emojiFor, modal, toast, reportError, empty, skeleton, sleep } from "../ui.js";
import { navigate } from "../nav.js";

export default async function cartPage({ root, me, refreshChrome }) {
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Корзина</h1></div>${skeleton(2)}</div>`.value;
    const uid = me.user.id;
    let carts = [];

    async function load() {
        const ids = cartRestaurants(uid);
        const loaded = await Promise.all(ids.map(async (id) => {
            const cart = await get(`/api/v1/customers/${uid}/restaurants/${id}/cart`).catch(() => null);
            if (!cart || !cart.items?.length) { untrackCart(uid, id); return null; }
            return { id, name: await restaurantName(id), cart };
        }));
        carts = loaded.filter(Boolean);
    }

    function draw() {
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Корзина</h1><span class="muted">Заказ оформляется отдельно по каждому ресторану</span></div>
            ${carts.length ? html`<div class="grid two">${carts.map(({ id, name, cart }) => html`<section class="card stack">
                <div class="row between"><h3><a href="#/r/${id}">${emojiFor(name)} ${name}</a></h3><button class="btn sm ghost danger" data-act="clear" data-rid="${id}">Очистить</button></div>
                ${cart.items.map((i) => html`<div class="cart-line"><div style="flex:1"><b>${i.menuItemName}</b><div class="muted small">${money(i.price)}</div></div>
                    <div class="qty"><button data-act="dec" data-rid="${id}" data-line="${i.id}" data-q="${i.quantity}">−</button><span>${i.quantity}</span><button data-act="inc" data-rid="${id}" data-line="${i.id}" data-q="${i.quantity}">+</button></div>
                    <b style="min-width:80px;text-align:right">${money(i.lineTotal)}</b></div>`)}
                <div class="total"><span>Итого</span><span>${money(cart.totalAmount)}</span></div>
                <button class="btn primary block" data-act="checkout" data-rid="${id}">Оформить заказ</button></section>`)}</div>`
                : empty("🛒", "Корзина пуста", html`<a class="btn primary" href="#/">Выбрать ресторан</a>`)}</div>`.value;
    }

    async function update(rid, fn) {
        try { await fn(); } catch (error) { reportError(error); }
        await load();
        draw();
        refreshChrome();
    }

    function checkout(rid) {
        const entry = carts.find((c) => c.id === rid);
        let method = "CARD";
        let picker;
        // Условия заказа: промокод (проверенный сервером), чаевые, время и доставка по текущему спросу.
        const terms = { promo: null, discount: 0, tip: 0, scheduledFor: null, fee: null };
        const food = Number(entry.cart.totalAmount);
        const drawSummary = (el) => {
            const fee = terms.fee ? Number(terms.fee.fee) : 0;
            const surge = terms.fee && Number(terms.fee.multiplier) > 1
                ? html` <span class="badge yellow" title="Заказов больше, чем свободных курьеров: доставка дороже, пока спрос не спадёт">×${terms.fee.multiplier} спрос</span>` : "";
            el.querySelector("[data-summary]").innerHTML = html`<div class="cart-line"><span style="flex:1">Еда</span><b>${money(food)}</b></div>
                ${terms.discount ? html`<div class="cart-line"><span style="flex:1">Промокод ${terms.promo}</span><b class="ok-text">−${money(terms.discount)}</b></div>` : ""}
                <div class="cart-line"><span style="flex:1">Доставка${surge}</span><b>${terms.fee ? money(fee) : "…"}</b></div>
                ${terms.tip ? html`<div class="cart-line"><span style="flex:1">Чаевые курьеру</span><b>${money(terms.tip)}</b></div>` : ""}
                <div class="total"><span>К оплате</span><span>${money(food - terms.discount + fee + terms.tip)}</span></div>`.value;
        };
        const minTime = () => {
            const at = new Date(Date.now() + 50 * 60_000);
            at.setMinutes(Math.ceil(at.getMinutes() / 5) * 5, 0, 0);
            return at;
        };
        const localInput = (date) => new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
        modal({
            title: "Оформление заказа",
            wide: true,
            body: html`<div class="stack"><p>${entry.name}: <b>${money(entry.cart.totalAmount)}</b></p>
                <div data-address></div>
                <div class="grid two">
                    <label class="field"><span>Промокод</span><div class="row"><input data-promo maxlength="40" placeholder="WELCOME10" style="flex:1;text-transform:uppercase">
                        <button type="button" class="btn sm" data-promo-apply>Применить</button></div><small class="muted" data-promo-note></small></label>
                    <label class="field"><span>Когда доставить</span><select data-when><option value="">Как можно скорее</option><option value="at">Ко времени…</option></select>
                        <input type="datetime-local" data-at hidden></label></div>
                <div class="field"><span>Чаевые курьеру</span><div class="row wrap" data-tips>${[0, 50, 100, 200].map((t) => html`<button type="button" class="btn sm ${t ? "ghost" : "primary"}" data-tip="${t}">${t ? money(t) : "Без чаевых"}</button>`)}</div></div>
                <div class="card subtle" data-summary></div>
                <div class="role-pick" style="grid-template-columns:1fr 1fr" id="pay-methods">
                    <label><input type="radio" name="m" value="CARD" checked><span style="font-size:26px">💳</span>Картой онлайн</label>
                    <label><input type="radio" name="m" value="CASH_ON_DELIVERY"><span style="font-size:26px">💵</span>Наличными курьеру</label></div>
                <p class="muted small">Оплата проходит в учебном режиме: деньги никуда не списываются, пока не подключён настоящий платёжный провайдер.</p></div>`,
            onMount: (el, close) => {
                el.addEventListener("change", (e) => { if (e.target.name === "m") method = e.target.value; });
                picker = addressPicker(el.querySelector("[data-address]"), { uid });
                restaurant(rid).then((r) => picker.setCity(r.city)).catch(() => {});
                drawSummary(el);
                get("/api/v1/delivery-fee").then((quote) => { terms.fee = quote; drawSummary(el); }).catch(() => {});
                el.querySelector("[data-promo-apply]").addEventListener("click", async () => {
                    const code = el.querySelector("[data-promo]").value.trim().toUpperCase();
                    const note = el.querySelector("[data-promo-note]");
                    if (!code) { Object.assign(terms, { promo: null, discount: 0 }); note.textContent = ""; drawSummary(el); return; }
                    try {
                        const check = await post(`/api/v1/customers/${uid}/restaurants/${rid}/promo-check`, { code });
                        Object.assign(terms, { promo: check.code, discount: Number(check.discount) });
                        note.textContent = `Скидка ${money(check.discount)}`;
                        note.className = "ok-text";
                    } catch (error) {
                        Object.assign(terms, { promo: null, discount: 0 });
                        note.textContent = error.message;
                        note.className = "bad-text";
                    }
                    drawSummary(el);
                });
                el.querySelector("[data-tips]").addEventListener("click", (e) => {
                    const button = e.target.closest("[data-tip]");
                    if (!button) return;
                    terms.tip = Number(button.dataset.tip);
                    el.querySelectorAll("[data-tip]").forEach((b) => b.classList.toggle("primary", b === button) || b.classList.toggle("ghost", b !== button));
                    drawSummary(el);
                });
                const at = el.querySelector("[data-at]");
                el.querySelector("[data-when]").addEventListener("change", (e) => {
                    at.hidden = e.target.value !== "at";
                    if (!at.hidden) {
                        at.min = localInput(minTime());
                        at.max = localInput(new Date(Date.now() + 7 * 86_400_000));
                        at.value ||= at.min;
                    }
                });
                // Окно закрывается и кнопками, и щелчком мимо: карту освобождаем в любом случае.
                new MutationObserver((_, observer) => { if (!el.isConnected) { picker.destroy(); observer.disconnect(); } })
                    .observe(document.getElementById("modal-root"), { childList: true });
            },
            actions: [
                { label: "Отмена" },
                {
                    label: "Заказать и оплатить", kind: "primary", close: false,
                    onClick: async ({ close, button, root: el }) => {
                        const problem = picker.validate();
                        if (problem) { toast(problem, "bad"); return false; }
                        const deliveryAddress = picker.value();
                        const scheduled = el.querySelector("[data-when]").value === "at" ? el.querySelector("[data-at]").value : "";
                        if (scheduled && new Date(scheduled) < new Date(Date.now() + 45 * 60_000)) { toast("Ко времени — не раньше чем через 45 минут", "bad"); return false; }
                        button.disabled = true;
                        button.textContent = "Оформляем…";
                        try {
                            const order = await post(`/api/v1/customers/${uid}/restaurants/${rid}/orders`, {
                                deliveryAddress,
                                promoCode: terms.promo,
                                tipAmount: terms.tip || null,
                                scheduledFor: scheduled ? new Date(scheduled).toISOString() : null,
                            });
                            untrackCart(uid, rid);
                            rememberAddress(uid, deliveryAddress);
                            await post(`/api/v1/customers/${uid}/orders/${order.id}/payments`, { method, idempotencyKey: crypto.randomUUID() });
                            // Оплата идёт через очереди: платёж проводится за секунды. Ждём итог, но не бесконечно.
                            for (let i = 0; i < 20; i++) {
                                const current = await get(`/api/v1/customers/${uid}/orders/${order.id}`);
                                if (current.status !== "PAYMENT_PENDING") break;
                                await sleep(700);
                            }
                            close();
                            toast("Заказ оформлен!", "ok");
                            refreshChrome();
                            navigate(`/orders/${order.id}`);
                        } catch (error) {
                            button.disabled = false;
                            button.textContent = "Заказать и оплатить";
                            reportError(error);
                        }
                        return false;
                    },
                },
            ],
        });
    }

    delegate(root, "click", {
        inc: (el) => update(el.dataset.rid, () => patch(`/api/v1/customers/${uid}/restaurants/${el.dataset.rid}/cart/items/${el.dataset.line}`, { quantity: Number(el.dataset.q) + 1 })),
        dec: (el) => update(el.dataset.rid, () => Number(el.dataset.q) <= 1
            ? del(`/api/v1/customers/${uid}/restaurants/${el.dataset.rid}/cart/items/${el.dataset.line}`)
            : patch(`/api/v1/customers/${uid}/restaurants/${el.dataset.rid}/cart/items/${el.dataset.line}`, { quantity: Number(el.dataset.q) - 1 })),
        clear: (el) => update(el.dataset.rid, async () => {
            const entry = carts.find((c) => String(c.id) === el.dataset.rid);
            for (const line of entry.cart.items) await del(`/api/v1/customers/${uid}/restaurants/${el.dataset.rid}/cart/items/${line.id}`);
        }),
        checkout: (el) => checkout(Number(el.dataset.rid)),
    });

    await load();
    draw();
}
