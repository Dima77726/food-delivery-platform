// Рестораны владельца: список управляемых им заведений и создание нового.

import { get, post, restaurants } from "../api.js";
import { html, delegate, modal, formValues, emojiFor, coverStyle, empty, skeleton, toast, reportError } from "../ui.js";
import { navigate } from "../nav.js";

/** Ресторанов владельца платформа списком не отдаёт: управляемые определяются по доступу к их заказам. */
export async function managedRestaurants(me, { fresh = false } = {}) {
    const all = await restaurants();
    if (me.role === "ADMIN") return all;

    // Проверка идёт запросом на каждый ресторан, поэтому результат запоминается на минуту.
    const key = `fd.managed.${me.user.id}`;
    try {
        const cached = JSON.parse(sessionStorage.getItem(key) || "null");
        if (!fresh && cached && Date.now() - cached.at < 60000) {
            const ids = new Set(cached.ids);
            return all.filter((r) => ids.has(r.id));
        }
    } catch { /* без кэша работаем как обычно */ }

    const checks = await Promise.all(all.map((r) =>
        get(`/api/v1/restaurants/${r.id}/orders?page=0&size=1`).then(() => r).catch(() => null)));
    const mine = checks.filter(Boolean);
    try { sessionStorage.setItem(key, JSON.stringify({ at: Date.now(), ids: mine.map((r) => r.id) })); } catch { /* не критично */ }
    return mine;
}

export default async function ownerPage({ root, me }) {
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Мои рестораны</h1></div>${skeleton(3)}</div>`.value;
    let list = [];

    function draw() {
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>${me.role === "ADMIN" ? "Все рестораны" : "Мои рестораны"}</h1>
            <button class="btn primary right" data-act="create">＋ Новый ресторан</button></div>
            ${list.length ? html`<div class="grid">${list.map((r) => html`<a class="card rest-card ${r.active ? "" : "closed"}" href="#/owner/${r.id}">
                <div class="rest-cover" style="${coverStyle(r.name)}">${emojiFor(r.name, r.description)}<span class="tag badge white">${r.active ? "Открыто" : "Закрыто"}</span></div>
                <div class="rest-body"><h3>${r.name}</h3><div class="muted small">📍 ${r.city}</div><div class="muted small">${r.description || ""}</div>
                <span class="btn sm" style="margin-top:6px;align-self:flex-start">Управлять →</span></div></a>`)}</div>`
                : empty("🏪", "У вас пока нет ресторанов", "Создайте первый: добавьте меню, и клиенты смогут заказывать.")}</div>`.value;
    }

    delegate(root, "click", {
        create: () => modal({
            title: "Новый ресторан",
            body: html`<form class="stack" id="new-rest"><div class="field"><label>Название</label><input class="input" name="name" required minlength="2" placeholder="Траттория «Милано»"></div>
                <div class="field"><label>Город</label><input class="input" name="city" required value="Москва"></div>
                <div class="field"><label>Описание</label><textarea class="input" name="description" placeholder="Чем вы особенные"></textarea></div></form>`,
            actions: [
                { label: "Отмена" },
                {
                    label: "Создать", kind: "primary", close: false,
                    onClick: async ({ close, root: dialog }) => {
                        const form = dialog.querySelector("#new-rest");
                        if (!form.reportValidity()) return false;
                        try {
                            const created = await post("/api/v1/restaurants", formValues(form));
                            sessionStorage.removeItem(`fd.managed.${me.user.id}`);
                            toast("Ресторан создан. Добавьте меню!", "ok");
                            close();
                            navigate(`/owner/${created.id}?tab=menu`);
                        } catch (error) { reportError(error); }
                        return false;
                    },
                },
            ],
        }),
    });

    list = await managedRestaurants(me);
    draw();
}
