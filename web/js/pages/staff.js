// Сотрудники точки для мессенджера: приглашения, должности, временное отключение, увольнение, перевод, смены, сеть.
//
// Это не кадровый учёт: должность здесь решает только, в какие рабочие чаты сотрудник попадает. Права управлять
// рестораном (меню, заказы) она не даёт.

import { get, post, patch, del, restaurantName } from "../api.js";
import { html, initials, toast, reportError, modal, confirmDialog, delegate, $, empty } from "../ui.js";

const API = "/api/v1/messenger";
const POSITIONS = [["MANAGER", "Руководитель"], ["ADMINISTRATOR", "Администратор"], ["OPERATOR", "Оператор"], ["KITCHEN", "Кухня"], ["PICKUP", "Выдача"]];
const LABEL = Object.fromEntries([["OWNER", "Владелец"], ...POSITIONS]);
const STATUS = { ACTIVE: "работает", SUSPENDED: "временно отключён" };

export default async function staffPage({ root, params, me, navigate }) {
    const restaurantId = Number(params.id);
    const name = await restaurantName(restaurantId);
    let staff = [];
    let shifts = [];
    let workplaces = [];

    root.innerHTML = html`<div class="container stack lg">
        <div class="page-head"><div><h1>Сотрудники · ${name}</h1><p class="sub muted">По должности сотрудник сам попадает в рабочие чаты точки; увольнение или перевод закрывает их сразу.</p></div>
            <a class="btn" href="#/messenger">💬 К сообщениям</a></div>
        <div class="grid two">
            <div class="card stack" data-staff><div class="muted">Загружаем…</div></div>
            <div class="stack">
                <div class="card stack"><h3>Пригласить сотрудника</h3>
                    <p class="muted small">Сотрудник открывает ссылку из своего аккаунта и принимает приглашение — без согласия человека никого не добавить.</p>
                    <form class="row wrap" data-act="invite"><select class="input" name="position" style="max-width:220px">${POSITIONS.map(([v, l]) => html`<option value="${v}">${l}</option>`)}</select><button class="btn primary">Создать приглашение</button></form>
                    <div data-invite></div></div>
                <div class="card stack" data-shifts></div>
                <div class="card stack" data-org></div>
            </div>
        </div></div>`.value;

    async function load() {
        try {
            [staff, workplaces] = await Promise.all([get(`${API}/restaurants/${restaurantId}/staff`), get(`${API}/me/workplaces`)]);
            const from = new Date(Date.now() - 86400_000).toISOString();
            shifts = await get(`${API}/restaurants/${restaurantId}/shifts?from=${encodeURIComponent(from)}`);
            render();
        } catch (error) {
            $("[data-staff]", root).innerHTML = (error.status === 403 ? empty("🔒", "Нет доступа", "Список видят сотрудники точки") : empty("😵", "Не удалось загрузить", error.message)).value;
        }
    }

    function myPosition() {
        return workplaces.find((w) => w.restaurantId === restaurantId)?.position;
    }

    function canManage() {
        return me.role === "ADMIN" || ["OWNER", "MANAGER"].includes(myPosition());
    }

    function render() {
        const manage = canManage();
        $("[data-staff]", root).innerHTML = html`<h3>Сотрудники · ${staff.length}</h3>
            ${staff.map((s) => html`<div class="ms-member">
                <span class="avatar">${initials(s.name)}</span>
                <span style="flex:1">${s.name}${s.userId === me.user.id ? " (вы)" : ""}<br><span class="muted small">${LABEL[s.position]} · ${STATUS[s.status]}</span></span>
                ${s.userId !== me.user.id ? html`<a class="icon-btn" href="#/messenger?dm=${s.userId}" title="Написать">💬</a>` : ""}
                ${manage && s.userId !== me.user.id && s.position !== "OWNER" ? html`<button class="icon-btn" data-act="manage" data-id="${s.assignmentId}">⋯</button>` : ""}
            </div>`)}`.value;
        renderShifts();
        renderOrg();
    }

    function renderShifts() {
        const canPlan = me.role === "ADMIN" || ["OWNER", "MANAGER", "ADMINISTRATOR"].includes(myPosition());
        const byUser = new Map(staff.map((s) => [s.userId, s.name]));
        $("[data-shifts]", root).innerHTML = html`<h3>Смены</h3>
            <p class="muted small">Кто в смене — тот в чате «Текущая смена»; уведомления о работе можно получать только в свою смену.</p>
            ${shifts.length ? shifts.map((s) => html`<div class="row between"><span>${byUser.get(s.userId) || "№" + s.userId}<br><span class="muted small">${new Date(s.startsAt).toLocaleString("ru-RU", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })} — ${new Date(s.endsAt).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}</span></span>
                ${canPlan ? html`<button class="icon-btn" data-act="del-shift" data-id="${s.id}">✕</button>` : ""}</div>`) : html`<p class="muted small">На ближайшую неделю смен нет</p>`}
            ${canPlan ? html`<form class="row wrap" data-act="add-shift">
                <select class="input" name="userId">${staff.filter((s) => s.status === "ACTIVE").map((s) => html`<option value="${s.userId}">${s.name}</option>`)}</select>
                <input class="input" type="datetime-local" name="startsAt" required><input class="input" type="datetime-local" name="endsAt" required>
                <button class="btn">Добавить смену</button></form>` : ""}`.value;
    }

    function renderOrg() {
        const own = workplaces.find((w) => w.restaurantId === restaurantId);
        const el = $("[data-org]", root);
        if (me.role !== "RESTAURANT_OWNER" || own?.position !== "OWNER") { el.classList.add("hidden"); return; }
        el.innerHTML = html`<h3>Сеть ресторанов</h3>
            ${own.organizationId ? html`<p>Точка входит в сеть №${own.organizationId}: сотрудников можно переводить между точками сети, а руководители создают межфилиальные группы.</p>`
                : html`<p class="muted small">Объедините свои точки в сеть, чтобы переводить сотрудников и заводить общие группы. Независимые рестораны друг друга не видят.</p>
                    <form class="row wrap" data-act="org"><input class="input" name="name" placeholder="Название сети" required><button class="btn">Создать сеть и добавить точку</button></form>`}`.value;
    }

    function manageDialog(id) {
        const s = staff.find((x) => x.assignmentId === id);
        const others = workplaces.filter((w) => w.restaurantId !== restaurantId && ["OWNER", "MANAGER"].includes(w.position));
        modal({
            title: s.name,
            body: html`<div class="stack">
                <label class="field"><span>Должность</span><select class="input" data-f="position">${POSITIONS.map(([v, l]) => html`<option value="${v}" ${v === s.position ? "selected" : ""}>${l}</option>`)}</select></label>
                <button class="btn" data-m="position">Сменить должность</button>
                <button class="btn" data-m="toggle">${s.status === "ACTIVE" ? "⏸ Временно отключить" : "▶️ Вернуть доступ"}</button>
                ${others.length ? html`<label class="field"><span>Перевести в точку сети</span><select class="input" data-f="target">${others.map((w) => html`<option value="${w.restaurantId}">Точка №${w.restaurantId}</option>`)}</select></label><button class="btn" data-m="transfer">Перевести</button>` : ""}
                <button class="btn danger" data-m="dismiss">Уволить</button>
                <p class="muted small">Отключение, увольнение и перевод сразу закрывают рабочие чаты точки, их вложения, звонки и уведомления. Личные переписки сотрудника остаются ему, но писать бывшим коллегам он больше не сможет.</p></div>`,
            onMount: (overlay, close) => overlay.addEventListener("click", async (event) => {
                const action = event.target.closest("[data-m]")?.dataset.m;
                if (!action) return;
                try {
                    if (action === "position") await patch(`${API}/staff/${id}`, { position: $("[data-f=position]", overlay).value });
                    if (action === "toggle") await patch(`${API}/staff/${id}`, { status: s.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE" });
                    if (action === "transfer") await post(`${API}/staff/${id}/transfer`, { restaurantId: Number($("[data-f=target]", overlay).value), position: $("[data-f=position]", overlay).value });
                    if (action === "dismiss") {
                        if (!(await confirmDialog("Уволить?", `${s.name} потеряет доступ ко всем рабочим чатам точки.`, "Уволить", "danger"))) return;
                        await del(`${API}/staff/${id}`);
                    }
                    close();
                    toast("Готово", "ok");
                    load();
                } catch (error) { reportError(error); }
            }),
        });
    }

    delegate(root, "submit", {
        invite: async (form) => {
            const invite = await post(`${API}/restaurants/${restaurantId}/staff/invites`, { position: form.elements.position.value });
            const link = `${location.origin}/#/join?code=${invite.code}`;
            $("[data-invite]", root).innerHTML = html`<div class="banner">Ссылка для сотрудника (одноразовая, до ${new Date(invite.expiresAt).toLocaleDateString("ru-RU")}):<br><code style="word-break:break-all">${link}</code><br><button class="btn sm" data-act="copy" data-link="${link}">Скопировать</button></div>`.value;
        },
        "add-shift": async (form) => {
            await post(`${API}/restaurants/${restaurantId}/shifts`, {
                userId: Number(form.elements.userId.value),
                startsAt: new Date(form.elements.startsAt.value).toISOString(),
                endsAt: new Date(form.elements.endsAt.value).toISOString(),
            });
            toast("Смена добавлена", "ok");
            load();
        },
        org: async (form) => {
            const org = await post(`${API}/organizations`, { name: form.elements.name.value });
            await post(`${API}/organizations/${org.id}/restaurants`, { restaurantId });
            toast("Сеть создана", "ok");
            load();
        },
    });
    delegate(root, "click", {
        manage: (el) => manageDialog(Number(el.dataset.id)),
        "del-shift": async (el) => { await del(`${API}/restaurants/${restaurantId}/shifts/${el.dataset.id}`); load(); },
        copy: async (el) => { await navigator.clipboard.writeText(el.dataset.link); toast("Скопировано", "ok"); },
    });

    // Владелец подключается к своей точке при первом заходе (проверка — у identity-service).
    if (me.role === "RESTAURANT_OWNER") await post(`${API}/me/workplaces/claim`, { restaurantIds: [restaurantId] }).catch(() => {});
    await load();
}
