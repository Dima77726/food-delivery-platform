// Каталог: поиск, фильтр по городу, рекомендации и карточки ресторанов.

import { get, restaurants } from "../api.js";
import { html, $, delegate, emojiFor, coverStyle, money, skeleton, empty } from "../ui.js";
import { navigate } from "../nav.js";

const QUICK = [["🍕", "пицца"], ["🍔", "бургер"], ["🍣", "суши"], ["🥟", "пельмени"], ["🍝", "паста"], ["🍰", "десерт"], ["🥤", "напитки"]];

export default async function home({ root, query, me }) {
    const q = (query.q || "").trim();
    let city = "";
    let showClosed = false;

    root.innerHTML = html`<div class="container">
        <section class="hero">
            <div class="emoji">🍜</div>
            <h1>Горячая еда — за&nbsp;30&nbsp;минут</h1>
            <p>Рестораны вашего города, живое отслеживание заказа и помощник, который всегда на связи.</p>
            <form class="searchbar" data-act="search"><input name="q" value="${q}" placeholder="Найти блюдо или ресторан" autocomplete="off"><button class="btn primary">Найти</button></form>
        </section>
        <div class="row wrap" style="margin-bottom:18px">${QUICK.map(([e, t]) => html`<button class="chip ${q === t ? "on" : ""}" data-act="quick" data-q="${t}">${e} ${t}</button>`)}${q ? html`<button class="chip" data-act="quick" data-q="">✕ сбросить</button>` : ""}</div>
        <div id="content">${skeleton()}</div>
    </div>`.value;

    const content = $("#content", root);

    delegate(root, "submit", { search: (form) => navigate(`/?q=${encodeURIComponent(new FormData(form).get("q") || "")}`) });
    delegate(root, "click", {
        quick: (el) => navigate(el.dataset.q ? `/?q=${encodeURIComponent(el.dataset.q)}` : "/"),
        city: (el) => { city = el.dataset.city; draw(); },
        closed: () => { showClosed = !showClosed; draw(); },
        open: (el) => navigate(`/r/${el.dataset.id}`),
    });

    let list = [];
    let recommended = [];

    const card = (r) => html`<article class="card rest-card ${r.active ? "" : "closed"}" data-act="open" data-id="${r.id}">
        <div class="rest-cover" style="${coverStyle(r.name)}">${emojiFor(r.name, r.description)}
            <span class="tag badge white">${r.active ? "Открыто" : "Закрыто"}</span></div>
        <div class="rest-body">
            <div class="row between"><h3>${r.name}</h3><span class="small nowrap stars" data-rating="${r.id}"></span></div>
            <div class="muted small">📍 ${r.city}</div>
            <div class="muted small" style="min-height:2.6em">${r.description || ""}</div>
        </div></article>`;

    function draw() {
        const cities = [...new Set(list.map((r) => r.city))].sort();
        // Закрытые рестораны заказать нельзя, поэтому по умолчанию они не мешают выбору.
        const closedCount = list.filter((r) => !r.active).length;
        const shown = list.filter((r) => (!city || r.city === city) && (showClosed || r.active)).sort((a, b) => Number(b.active) - Number(a.active));
        content.innerHTML = html`
            ${recommended.length ? html`<h2 style="margin-bottom:14px">Вам может понравиться</h2>
                <div class="row wrap" style="margin-bottom:26px">${recommended.slice(0, 6).map((x) => html`<a class="chip" href="#/r/${x.restaurantId ?? x.id ?? ""}">${emojiFor(x.menuItemName || x.name)} ${x.menuItemName || x.name}</a>`)}</div>` : ""}
            <div class="row wrap" style="margin-bottom:16px"><h2>Рестораны</h2><span class="muted">${shown.length}</span>
                <div class="row wrap right"><button class="chip ${city ? "" : "on"}" data-act="city" data-city="">Все города</button>${cities.map((c) => html`<button class="chip ${c === city ? "on" : ""}" data-act="city" data-city="${c}">${c}</button>`)}</div></div>
            ${shown.length ? html`<div class="grid">${shown.map(card)}</div>` : empty("🍽️", "Ничего не нашлось", "Попробуйте другой город.")}
            ${closedCount ? html`<div class="center" style="margin-top:22px"><button class="chip ${showClosed ? "on" : ""}" data-act="closed">${showClosed ? "Скрыть закрытые" : `Показать закрытые (${closedCount})`}</button></div>` : ""}`.value;
        loadRatings(shown.filter((r) => r.active).slice(0, 24));
    }

    async function loadRatings(items) {
        await Promise.all(items.map(async (r) => {
            try {
                const s = await get(`/api/v1/restaurants/${r.id}/reviews/summary`, { auth: false });
                const el = root.querySelector(`[data-rating="${r.id}"]`);
                if (el && s.total > 0) el.innerHTML = `★ ${s.average.toFixed(1)} <span class="muted">(${s.total})</span>`;
            } catch { /* рейтинг - украшение карточки */ }
        }));
    }

    async function drawSearch() {
        const result = await get(`/api/v1/search?q=${encodeURIComponent(q)}&size=24`, { auth: false });
        content.innerHTML = html`
            <h2 style="margin-bottom:14px">Рестораны по запросу «${q}»</h2>
            ${result.restaurants.length ? html`<div class="grid">${result.restaurants.map((r) => card({ ...r, id: r.restaurantId }))}</div>` : empty("🔎", "Ресторанов не нашлось")}
            <h2 style="margin:28px 0 14px">Блюда</h2>
            ${result.items.length ? html`<div class="grid">${result.items.map((i) => html`<a class="menu-item" href="#/r/${i.restaurantId}" style="grid-template-columns:72px 1fr auto">
                <div class="thumb" style="width:72px;height:72px;font-size:32px">${emojiFor(i.name)}</div>
                <div><b>${i.name}</b><div class="muted small">${i.restaurantName} · ${i.city}</div><div class="muted small">${i.description || ""}</div></div>
                <div class="price">${money(i.price)}</div></a>`)}</div>` : empty("🍴", "Блюд не нашлось", "Поиск работает по названию и описанию.")}`.value;
    }

    try {
        if (q) {
            await drawSearch();
        } else {
            list = await restaurants();
            if (me?.role === "CUSTOMER") {
                get(`/api/v1/customers/${me.user.id}/recommendations?limit=6`).then((r) => { recommended = Array.isArray(r) ? r : []; draw(); }).catch(() => {});
            }
            draw();
        }
    } catch (error) {
        content.innerHTML = html`<div class="empty"><div class="big">🔌</div><h3>Не удалось загрузить каталог</h3><p>${error.message}</p></div>`.value;
    }
}
