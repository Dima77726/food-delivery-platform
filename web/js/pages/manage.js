// Управление рестораном: заказы (канбан), меню, аналитика, отзывы, интеграции и настройки.

import { get, post, patch, del, api, restaurant } from "../api.js";
import { html, delegate, modal, formValues, confirmDialog, money, emojiFor, coverStyle, ago, empty, skeleton, toast, reportError, statusBadge, ORDER_STATUS } from "../ui.js";
import { navigate } from "../nav.js";
import { sseStream } from "../live.js";
import { integrationsTab } from "./integrations.js";

const COLUMNS = [
    ["PAID", "Новые", "Принять", "accept"],
    ["ACCEPTED", "Приняты", "Начать готовить", "start-cooking"],
    ["COOKING", "Готовятся", "Готово", "ready-for-delivery"],
    ["READY_FOR_DELIVERY", "Ждут курьера", "Вызвать курьера", "deliveries"],
];

const isoDay = (offset = 0) => new Date(Date.now() + offset * 864e5).toISOString().slice(0, 10);

export default async function managePage({ root, params, query, onLeave }) {
    const rid = Number(params.id);
    let tab = query.tab || "orders";
    let info;
    let state = {};
    root.innerHTML = html`<div class="container">${skeleton(2)}</div>`.value;

    try {
        info = await restaurant(rid);
        // Проверка доступа: чужой ресторан сервис не отдаст.
        await get(`/api/v1/restaurants/${rid}/orders?page=0&size=1`);
    } catch (error) {
        root.innerHTML = html`<div class="container">${empty("🔒", "Нет доступа к этому ресторану", error.message)}</div>`.value;
        return;
    }

    // ---------- Заказы ----------

    async function loadOrders() {
        const columns = await Promise.all(COLUMNS.map(([status]) => get(`/api/v1/restaurants/${rid}/orders?status=${status}&page=0&size=50`).catch(() => ({ content: [], totalElements: 0 }))));
        const [inDelivery, delivered] = await Promise.all(["IN_DELIVERY", "DELIVERED"].map((s) => get(`/api/v1/restaurants/${rid}/orders?status=${s}&page=0&size=1`).catch(() => ({ totalElements: 0 }))));
        state.orders = { columns, inDelivery: inDelivery.totalElements, delivered: delivered.totalElements };
    }

    function ordersView() {
        if (!state.orders) return skeleton(4);
        const { columns, inDelivery, delivered } = state.orders;
        return html`<div class="row wrap" style="margin-bottom:14px"><span class="badge blue">🛵 В пути: ${inDelivery}</span><span class="badge green">✓ Доставлено: ${delivered}</span>
            <span class="muted small right">Обновляется автоматически</span></div>
            <div class="kanban">${COLUMNS.map(([status, title, action, verb], i) => html`<div class="col"><h4>${title}<span class="badge ${columns[i].content.length ? "orange" : ""}">${columns[i].content.length}</span></h4>
                ${columns[i].content.map((o) => html`<div class="ticket"><div class="row between"><b>№${o.id}</b><span class="muted small">${ago(o.createdAt)}</span></div>
                    <div class="muted small">${o.itemsCount} поз. · <b style="color:var(--text)">${money(o.totalAmount)}</b></div>
                    <button class="btn sm ${status === "PAID" ? "primary" : ""}" data-act="advance" data-verb="${verb}" data-id="${o.id}">${action}</button></div>`)}
                ${columns[i].content.length ? "" : html`<div class="muted small center" style="padding:24px 0">Пусто</div>`}</div>`)}</div>`;
    }

    // ---------- Меню ----------

    async function loadMenu() { state.menu = await get(`/api/v1/restaurants/${rid}/menu/manage`); }

    function menuView() {
        if (!state.menu) return skeleton(3);
        const cats = state.menu.categories.filter((c) => !c.archived);
        return html`<div class="row" style="margin-bottom:16px"><h3>Категории и блюда</h3><button class="btn primary right" data-act="add-cat">＋ Категория</button></div>
            ${cats.length ? cats.map((c) => html`<section class="card stack" style="margin-bottom:16px">
                <div class="row"><h3>${c.name}</h3><div class="row right"><button class="btn sm" data-act="add-item" data-cat="${c.id}">＋ Блюдо</button>
                    <button class="btn sm ghost" data-act="edit-cat" data-cat="${c.id}">✎</button><button class="btn sm ghost danger" data-act="del-cat" data-cat="${c.id}">🗑</button></div></div>
                ${c.items.filter((i) => !i.archived).map((i) => html`<div class="menu-item ${i.available ? "" : "off"}">
                    <div class="thumb">${i.imageUrl ? html`<img src="${i.imageUrl}?v=${i.id}" alt="${i.name}">` : emojiFor(i.name, c.name)}</div>
                    <div class="stack" style="gap:3px"><b>${i.name}</b><span class="muted small">${i.description || ""}</span><span class="price">${money(i.price)}</span></div>
                    <div class="stack item-actions act">
                        <div class="row wrap"><button class="btn sm" data-act="edit-item" data-id="${i.id}">Изменить</button>
                            <button class="btn sm ${i.available ? "" : "green"}" data-act="toggle-item" data-id="${i.id}" data-on="${i.available}">${i.available ? "Скрыть (нет в наличии)" : "Вернуть в меню"}</button></div>
                        <div class="row wrap"><label class="btn sm ghost" style="cursor:pointer">📷 ${i.imageUrl ? "Заменить фото" : "Добавить фото"}<input type="file" accept="image/jpeg,image/png,image/webp" data-photo="${i.id}" hidden></label>
                            ${i.imageUrl ? html`<button class="btn sm ghost danger" data-act="del-photo" data-id="${i.id}">Убрать фото</button>` : ""}
                            <button class="btn sm ghost danger" data-act="del-item" data-id="${i.id}">🗑</button></div></div></div>`)}
                ${c.items.filter((i) => !i.archived).length ? "" : html`<p class="muted">В категории пока нет блюд.</p>`}</section>`)
                : empty("📋", "Меню пустое", "Создайте категорию, например «Пицца», и добавьте блюда.")}`;
    }

    const itemOf = (id) => state.menu.categories.flatMap((c) => c.items).find((i) => i.id === Number(id));

    function itemDialog(item, categoryId) {
        modal({
            title: item ? "Изменить блюдо" : "Новое блюдо",
            body: html`<form class="stack" id="item-form"><div class="field"><label>Название</label><input class="input" name="name" required value="${item?.name || ""}"></div>
                <div class="field"><label>Описание</label><input class="input" name="description" maxlength="100" value="${item?.description || ""}"></div>
                <div class="field"><label>Цена, ₽</label><input class="input" name="price" type="number" min="0" step="0.01" required value="${item?.price ?? ""}"></div></form>`,
            actions: [
                { label: "Отмена" },
                {
                    label: "Сохранить", kind: "primary", close: false,
                    onClick: async ({ close, root: dialog }) => {
                        const form = dialog.querySelector("#item-form");
                        if (!form.reportValidity()) return false;
                        const v = formValues(form);
                        const body = { name: v.name, description: v.description || null, price: Number(v.price) };
                        try {
                            if (item) await patch(`/api/v1/restaurants/${rid}/menu/items/${item.id}`, body);
                            else await post(`/api/v1/restaurants/${rid}/menu/categories/${categoryId}/items`, { ...body, sortOrder: 0 });
                            close();
                            await loadMenu();
                            draw();
                        } catch (error) { reportError(error); }
                        return false;
                    },
                },
            ],
        });
    }

    function categoryDialog(category) {
        modal({
            title: category ? "Переименовать категорию" : "Новая категория",
            body: html`<form class="stack" id="cat-form"><div class="field"><label>Название</label><input class="input" name="name" required value="${category?.name || ""}" placeholder="Например, Пицца"></div></form>`,
            actions: [
                { label: "Отмена" },
                {
                    label: "Сохранить", kind: "primary", close: false,
                    onClick: async ({ close, root: dialog }) => {
                        const form = dialog.querySelector("#cat-form");
                        if (!form.reportValidity()) return false;
                        const { name } = formValues(form);
                        try {
                            if (category) await patch(`/api/v1/restaurants/${rid}/menu/categories/${category.id}`, { name });
                            else await post(`/api/v1/restaurants/${rid}/menu/categories`, { name, sortOrder: state.menu.categories.length });
                            close();
                            await loadMenu();
                            draw();
                        } catch (error) { reportError(error); }
                        return false;
                    },
                },
            ],
        });
    }

    // ---------- Аналитика ----------

    async function loadAnalytics() {
        const days = state.days || 30;
        const q = `from=${isoDay(-days)}&to=${isoDay(1)}`;
        const [sales, funnel] = await Promise.all([
            get(`/api/v1/restaurants/${rid}/analytics/sales?${q}`).catch(() => null),
            get(`/api/v1/restaurants/${rid}/analytics/funnel?${q}`).catch(() => null),
        ]);
        state.analytics = { sales, funnel };
    }

    function barChart(days) {
        if (!days?.length) return empty("📊", "Данных пока нет", "Они появятся после первых заказов.");
        const max = Math.max(...days.map((d) => d.revenue), 1);
        return html`<div class="bars">${days.map((d) => html`<div class="bar" style="height:${Math.max(6, (d.revenue / max) * 100)}%" title="${d.day}: ${money(d.revenue)}, заказов ${d.orders}"><span>${money(d.revenue)}</span></div>`)}</div>
            <div class="row" style="gap:8px">${days.map((d) => html`<div class="bar-label" style="flex:1">${d.day.slice(5)}</div>`)}</div>`;
    }

    function analyticsView() {
        if (!state.analytics) return skeleton(2);
        const { sales, funnel } = state.analytics;
        const maxF = Math.max(...(funnel?.statuses || []).map((s) => s.orders), 1);
        return html`<div class="row wrap" style="margin-bottom:16px">${[7, 30, 90].map((n) => html`<button class="chip ${(state.days || 30) === n ? "on" : ""}" data-act="range" data-days="${n}">${n} дней</button>`)}</div>
            <div class="kpis"><div class="kpi"><div class="v">${sales?.totalOrders ?? 0}</div><div class="l">заказов</div></div>
                <div class="kpi"><div class="v">${money(sales?.totalRevenue ?? 0)}</div><div class="l">выручка</div></div>
                <div class="kpi"><div class="v">${money(sales?.totalOrders ? sales.totalRevenue / sales.totalOrders : 0)}</div><div class="l">средний чек</div></div></div>
            <div class="grid two"><div class="card"><h3 class="card-title">Выручка по дням</h3>${barChart(sales?.days)}</div>
                <div class="card"><h3 class="card-title">Воронка заказов</h3>${funnel?.statuses?.length ? funnel.statuses.map((s) => html`<div class="funnel-row"><span>${(ORDER_STATUS[s.status] || [s.status])[0]}</span><div class="funnel-bar"><i style="width:${(s.orders / maxF) * 100}%"></i></div><b>${s.orders}</b></div>`) : empty("🧮", "Пока пусто")}</div></div>`;
    }

    // ---------- Отзывы ----------

    async function loadReviews() {
        const [summary, page] = await Promise.all([get(`/api/v1/restaurants/${rid}/reviews/summary`, { auth: false }).catch(() => null), get(`/api/v1/restaurants/${rid}/reviews?page=0&size=30`, { auth: false }).catch(() => ({ content: [] }))]);
        state.reviews = { summary, page };
    }

    function reviewsView() {
        if (!state.reviews) return skeleton(2);
        const { summary, page } = state.reviews;
        return html`${summary?.total ? html`<div class="kpis"><div class="kpi"><div class="v stars">★ ${summary.average.toFixed(1)}</div><div class="l">средняя оценка</div></div><div class="kpi"><div class="v">${summary.total}</div><div class="l">отзывов</div></div></div>` : ""}
            ${page.content.length ? page.content.map((r) => html`<div class="card" style="margin-bottom:12px"><div class="row between"><span class="stars">${"★".repeat(r.rating)}${"☆".repeat(5 - r.rating)}</span><span class="muted small">${ago(r.createdAt)} · заказ №${r.orderId}</span></div>
                ${r.comment ? html`<p style="margin-top:8px">${r.comment}</p>` : ""}${r.tags?.length ? html`<div class="row wrap" style="margin-top:8px">${r.tags.map((t) => html`<span class="chip">#${t}</span>`)}</div>` : ""}</div>`) : empty("💬", "Отзывов пока нет")}`;
    }

    // ---------- Настройки ----------

    function settingsView() {
        return html`<div class="grid two"><form class="card stack" data-act="save-info"><h3>Данные ресторана</h3>
            <div class="field"><label>Название</label><input class="input" name="name" required value="${info.name}"></div>
            <div class="field"><label>Город</label><input class="input" name="city" required value="${info.city}"></div>
            <div class="field"><label>Описание</label><textarea class="input" name="description">${info.description || ""}</textarea></div>
            <button class="btn primary">Сохранить</button></form>
            <div class="card stack"><h3>Приём заказов</h3><p class="muted">Сейчас ресторан <b>${info.active ? "открыт" : "закрыт"}</b>. Пока он закрыт, клиенты видят меню, но заказать не могут.</p>
            <button class="btn ${info.active ? "danger" : "green"}" data-act="toggle-open">${info.active ? "Закрыть ресторан" : "Открыть ресторан"}</button></div></div>`;
    }

    // ---------- Каркас страницы ----------

    const integrations = integrationsTab({ rid, getState: () => state, redraw: () => tab === "integrations" && redrawBody() });

    const TABS = [["orders", "🍳 Заказы"], ["menu", "📋 Меню"], ["analytics", "📊 Аналитика"], ["reviews", "💬 Отзывы"], ["integrations", "🔌 Интеграции"], ["settings", "⚙️ Настройки"]];
    const loaders = { orders: loadOrders, menu: loadMenu, analytics: loadAnalytics, reviews: loadReviews, integrations: integrations.load, settings: async () => {} };
    const views = { orders: ordersView, menu: menuView, analytics: analyticsView, reviews: reviewsView, integrations: integrations.view, settings: settingsView };

    function draw() {
        root.innerHTML = html`<div class="container">
            <div class="page-head"><a class="btn sm" href="#/owner">← Рестораны</a><div class="thumb" style="width:52px;height:52px;font-size:26px;${coverStyle(info.name)}">${emojiFor(info.name)}</div>
                <div class="stack" style="gap:0"><h1>${info.name}</h1><span class="muted">📍 ${info.city}</span></div>
                <span class="badge ${info.active ? "green" : "red"} right">${info.active ? "Открыто" : "Закрыто"}</span><a class="btn sm" href="#/r/${rid}">Как видят клиенты</a></div>
            <div class="tabs">${TABS.map(([key, label]) => html`<button class="tab ${tab === key ? "on" : ""}" data-act="tab" data-tab="${key}">${label}</button>`)}</div>
            <div id="tab-body">${views[tab]()}</div></div>`.value;
    }

    async function openTab(next) {
        tab = next;
        draw();
        try { await loaders[tab](); } catch (error) { reportError(error); }
        if (tab === next) draw();
    }

    // Перерисовывается только тело вкладки: так обновление заказов не сбрасывает прокрутку и фокус.
    function redrawBody() {
        const body = root.querySelector("#tab-body");
        if (body) body.innerHTML = views[tab]().value;
    }

    const advance = async (el) => {
        const verb = el.dataset.verb;
        try {
            await post(`/api/v1/restaurants/${rid}/orders/${el.dataset.id}/${verb}`);
            toast(verb === "deliveries" ? "Курьера вызвали" : "Статус обновлён", "ok");
        } catch (error) {
            reportError(error.status === 409 && verb === "deliveries" ? new Error("Доставка для этого заказа уже создана") : error);
        }
        await loadOrders();
        if (tab === "orders") redrawBody();
    };

    delegate(root, "click", {
        ...integrations.handlers,
        tab: (el) => openTab(el.dataset.tab),
        advance,
        range: async (el) => { state.days = Number(el.dataset.days); state.analytics = null; await openTab("analytics"); },
        "add-cat": () => categoryDialog(null),
        "edit-cat": (el) => categoryDialog(state.menu.categories.find((c) => c.id === Number(el.dataset.cat))),
        "del-cat": async (el) => {
            if (!(await confirmDialog("Удалить категорию?", "Блюда из неё тоже уберутся из меню.", "Удалить", "danger"))) return;
            await del(`/api/v1/restaurants/${rid}/menu/categories/${el.dataset.cat}`);
            await loadMenu(); draw();
        },
        "add-item": (el) => itemDialog(null, el.dataset.cat),
        "edit-item": (el) => itemDialog(itemOf(el.dataset.id)),
        "toggle-item": async (el) => {
            await post(`/api/v1/restaurants/${rid}/menu/items/${el.dataset.id}/${el.dataset.on === "true" ? "out-of-stock" : "in-stock"}`);
            await loadMenu(); draw();
        },
        "del-item": async (el) => {
            if (!(await confirmDialog("Убрать блюдо из меню?", itemOf(el.dataset.id).name, "Убрать", "danger"))) return;
            await del(`/api/v1/restaurants/${rid}/menu/items/${el.dataset.id}`);
            await loadMenu(); draw();
        },
        "del-photo": async (el) => {
            await del(`/api/v1/restaurants/${rid}/menu/items/${el.dataset.id}/image`);
            await loadMenu(); draw();
        },
        "toggle-open": async () => {
            await post(`/api/v1/restaurants/${rid}/${info.active ? "close" : "open"}`);
            info = await restaurant(rid);
            toast(info.active ? "Ресторан открыт" : "Ресторан закрыт", "ok");
            draw();
        },
    });

    delegate(root, "submit", {
        "save-info": async (form) => {
            const v = formValues(form);
            info = await patch(`/api/v1/restaurants/${rid}`, { name: v.name, city: v.city, description: v.description || null });
            toast("Сохранено", "ok");
            draw();
        },
    });

    root.addEventListener("change", async (event) => {
        const input = event.target.closest("[data-photo]");
        if (!input || !input.files.length) return;
        const form = new FormData();
        form.append("file", input.files[0]);
        try {
            await api("PUT", `/api/v1/restaurants/${rid}/menu/items/${input.dataset.photo}/image`, { form });
            toast("Фото загружено", "ok");
            await loadMenu();
            draw();
        } catch (error) {
            reportError(error);
        }
    });

    draw();
    await openTab(tab);

    // Доска живёт на потоке SSE: каждый кадр «заказ N теперь в статусе S» — перечитать колонки. Кадры, пришедшие
    // пачкой, схлопываются в одно чтение. Новый оплаченный заказ — ещё и всплывающее уведомление.
    let reloadQueued = false;
    let live = false;
    const reload = () => {
        if (reloadQueued) return;
        reloadQueued = true;
        setTimeout(async () => {
            reloadQueued = false;
            if (tab !== "orders" || document.querySelector(".overlay")) return;
            try { await loadOrders(); if (tab === "orders") redrawBody(); } catch { /* следующий кадр повторит */ }
        }, 200);
    };
    const stopStream = sseStream(`/api/v1/restaurants/${rid}/orders/stream`, {
        onEvent: (name, data) => {
            if (name === "order" && data.status === "PAID") toast(`Новый заказ №${data.orderId}`, "ok");
            if (name === "order" || name === "ready") reload();
        },
        onState: (state) => { live = state === "live"; },
    });
    // Пока поток недоступен (переподключение), доска опрашивается по-старому.
    const timer = setInterval(async () => {
        if (live || tab !== "orders" || document.querySelector(".overlay")) return;
        try { await loadOrders(); if (tab === "orders") redrawBody(); } catch { /* повторим */ }
    }, 5000);
    onLeave(() => { clearInterval(timer); stopStream(); });
}
