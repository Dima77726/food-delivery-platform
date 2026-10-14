// Администратор: пользователи, роли, блокировка и назначение управляющих ресторанами.

import { get, post, restaurants } from "../api.js";
import { html, delegate, modal, initials, ago, empty, skeleton, toast, reportError, ROLE_ICONS, ROLE_LABELS, debounce, confirmDialog } from "../ui.js";

const ROLES = ["CUSTOMER", "RESTAURANT_OWNER", "COURIER", "ADMIN"];

export default async function adminUsersPage({ root, me }) {
    let users = [];
    let rests = [];
    let filter = "";
    let role = "";
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Пользователи</h1></div>${skeleton(3)}</div>`.value;

    const load = async () => { [users, rests] = await Promise.all([get("/api/v1/admin/users"), restaurants().catch(() => [])]); };

    function rows() {
        return users
            .filter((u) => (!role || u.roles.includes(role)) && (!filter || (u.email + " " + u.fullName).toLowerCase().includes(filter.toLowerCase())))
            .sort((a, b) => b.id - a.id);
    }

    function draw() {
        const list = rows();
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Пользователи</h1><span class="muted">${list.length} из ${users.length}</span></div>
            <div class="row wrap" style="margin-bottom:16px"><input class="input" id="filter" style="max-width:320px" placeholder="Поиск по имени или почте" value="${filter}">
                <button class="chip ${role ? "" : "on"}" data-act="role" data-role="">Все</button>${ROLES.map((r) => html`<button class="chip ${role === r ? "on" : ""}" data-act="role" data-role="${r}">${ROLE_ICONS[r]} ${ROLE_LABELS[r]}</button>`)}</div>
            <div class="card pad0"><div class="table-wrap"><table class="tbl"><thead><tr><th>Пользователь</th><th>Роли</th><th>Создан</th><th>Статус</th><th></th></tr></thead><tbody>
            ${list.map((u) => html`<tr><td><div class="row"><span class="avatar">${initials(u.fullName)}</span><div><b>${u.fullName}</b><div class="muted small">${u.email} · №${u.id}</div></div></div></td>
                <td><div class="row wrap" style="gap:4px">${u.roles.map((r) => html`<span class="badge orange">${ROLE_ICONS[r]} ${ROLE_LABELS[r]}</span>`)}</div></td>
                <td class="muted small nowrap">${ago(u.createdAt)}</td>
                <td><span class="badge ${u.enabled ? "green" : "red"}">${u.enabled ? "активен" : "заблокирован"}</span></td>
                <td class="nowrap"><button class="btn sm" data-act="roles" data-id="${u.id}">Роли</button>
                    ${u.roles.includes("RESTAURANT_OWNER") ? html`<button class="btn sm" data-act="assign" data-id="${u.id}">Ресторан</button>` : ""}
                    ${u.id === me.user.id ? "" : html`<button class="btn sm ${u.enabled ? "danger" : "green"}" data-act="toggle" data-id="${u.id}" data-on="${u.enabled}">${u.enabled ? "Заблокировать" : "Разблокировать"}</button>`}</td></tr>`)}
            </tbody></table></div>${list.length ? "" : empty("🔍", "Никого не нашли")}</div></div>`.value;
        const input = root.querySelector("#filter");
        input.addEventListener("input", debounce(() => {
            filter = input.value;
            draw();
            const next = root.querySelector("#filter");
            next.focus();
            next.setSelectionRange(filter.length, filter.length);
        }, 250));
    }

    const userOf = (id) => users.find((u) => u.id === Number(id));

    delegate(root, "click", {
        role: (el) => { role = el.dataset.role; draw(); },
        toggle: async (el) => {
            const enable = el.dataset.on !== "true";
            const u = userOf(el.dataset.id);
            if (!enable && !(await confirmDialog("Заблокировать пользователя?", `${u.fullName} потеряет доступ: все его сессии завершатся.`, "Заблокировать", "danger"))) return;
            await post(`/api/v1/admin/users/${u.id}/${enable ? "enable" : "disable"}`);
            toast(enable ? "Пользователь разблокирован" : "Пользователь заблокирован", "ok");
            await load();
            draw();
        },
        roles: (el) => {
            const u = userOf(el.dataset.id);
            modal({
                title: `Роли: ${u.fullName}`,
                body: html`<div class="stack"><p class="muted small">Роль выдаётся добавлением; снять роль можно только в базе. Выдавайте ADMIN осознанно.</p>
                    ${ROLES.map((r) => html`<div class="row between"><span>${ROLE_ICONS[r]} ${ROLE_LABELS[r]}</span>
                        ${u.roles.includes(r) ? html`<span class="badge green">есть</span>` : html`<button class="btn sm" data-modal-grant="${r}">Выдать</button>`}</div>`)}</div>`,
                onMount: (dialog, close) => dialog.addEventListener("click", async (e) => {
                    const btn = e.target.closest("[data-modal-grant]");
                    if (!btn) return;
                    try {
                        await post(`/api/v1/admin/users/${u.id}/roles/${btn.dataset.modalGrant}`);
                        toast("Роль выдана", "ok");
                        close();
                        await load();
                        draw();
                    } catch (error) { reportError(error); }
                }),
                actions: [{ label: "Закрыть" }],
            });
        },
        assign: (el) => {
            const u = userOf(el.dataset.id);
            modal({
                title: `Ресторан для ${u.fullName}`,
                body: html`<div class="field"><label>Сделать управляющим ресторана</label><select class="input" id="rest">${rests.map((r) => html`<option value="${r.id}">${r.name} · ${r.city}</option>`)}</select></div>`,
                actions: [
                    { label: "Отмена" },
                    {
                        label: "Назначить", kind: "primary", close: false,
                        onClick: async ({ close, root: dialog }) => {
                            try {
                                await post(`/api/v1/admin/users/${u.id}/restaurants/${dialog.querySelector("#rest").value}`);
                                toast("Управляющий назначен", "ok");
                                close();
                            } catch (error) { reportError(error); }
                            return false;
                        },
                    },
                ],
            });
        },
    });

    await load();
    draw();
}
