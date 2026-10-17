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
        modal({
            title: "Оформление заказа",
            wide: true,
            body: html`<div class="stack"><p>${entry.name}: <b>${money(entry.cart.totalAmount)}</b></p>
                <div data-address></div>
                <div class="role-pick" style="grid-template-columns:1fr 1fr" id="pay-methods">
                    <label><input type="radio" name="m" value="CARD" checked><span style="font-size:26px">💳</span>Картой онлайн</label>
                    <label><input type="radio" name="m" value="CASH_ON_DELIVERY"><span style="font-size:26px">💵</span>Наличными курьеру</label></div>
                <p class="muted small">Оплата проходит в учебном режиме: деньги никуда не списываются, пока не подключён настоящий платёжный провайдер.</p></div>`,
            onMount: (el, close) => {
                el.addEventListener("change", (e) => { if (e.target.name === "m") method = e.target.value; });
                picker = addressPicker(el.querySelector("[data-address]"), { uid });
                restaurant(rid).then((r) => picker.setCity(r.city)).catch(() => {});
                // Окно закрывается и кнопками, и щелчком мимо: карту освобождаем в любом случае.
                new MutationObserver((_, observer) => { if (!el.isConnected) { picker.destroy(); observer.disconnect(); } })
                    .observe(document.getElementById("modal-root"), { childList: true });
            },
            actions: [
                { label: "Отмена" },
                {
                    label: "Заказать и оплатить", kind: "primary", close: false,
                    onClick: async ({ close, button }) => {
                        const problem = picker.validate();
                        if (problem) { toast(problem, "bad"); return false; }
                        const deliveryAddress = picker.value();
                        button.disabled = true;
                        button.textContent = "Оформляем…";
                        try {
                            const order = await post(`/api/v1/customers/${uid}/restaurants/${rid}/orders`, { deliveryAddress });
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
