// Администратор: сводный журнал аудита всех сервисов.

import { get } from "../api.js";
import { html, delegate, dateTime, empty, skeleton, formValues } from "../ui.js";

export default async function adminAuditPage({ root, onLeave }) {
    let events = [];
    let filters = { resourceType: "", resourceId: "" };
    let live = true;
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Журнал аудита</h1></div>${skeleton(2)}</div>`.value;

    async function load() {
        const q = new URLSearchParams({ limit: "100" });
        if (filters.resourceType) q.set("resourceType", filters.resourceType);
        if (filters.resourceId) q.set("resourceId", filters.resourceId);
        events = await get(`/api/v1/admin/audit?${q}`);
    }

    function draw() {
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Журнал аудита</h1><span class="muted">события всех сервисов, новые сверху</span></div>
            <form class="row wrap card" style="margin-bottom:16px" data-act="filter">
                <div class="field"><label>Тип ресурса</label><select class="input" name="resourceType">${["", "ORDER", "USER", "PAYMENT", "RESTAURANT"].map((t) => html`<option value="${t}" ${filters.resourceType === t ? "selected" : ""}>${t || "любой"}</option>`)}</select></div>
                <div class="field"><label>Номер ресурса</label><input class="input" name="resourceId" value="${filters.resourceId}" placeholder="например, 93"></div>
                <button class="btn primary" style="align-self:flex-end">Применить</button>
                <label class="row small muted right" style="align-self:flex-end"><input type="checkbox" data-act="live" ${live ? "checked" : ""}> обновлять каждые 5 с</label></form>
            <div class="card pad0"><div class="table-wrap"><table class="tbl"><thead><tr><th>Время</th><th>Действие</th><th>Кто</th><th>Ресурс</th><th>Подробности</th><th>Сервис</th></tr></thead><tbody>
            ${events.map((e) => html`<tr><td class="nowrap muted small">${dateTime(e.occurredAt)}</td><td><span class="badge ${e.outcome === "SUCCESS" ? "green" : "red"}">${e.action}</span></td>
                <td class="small">${e.actorEmail || html`<span class="muted">система</span>`}</td><td class="small nowrap">${e.resourceType} ${e.resourceId || ""}</td>
                <td class="small">${e.details || ""}</td><td class="muted small">${e.sourceService}</td></tr>`)}</tbody></table></div>
            ${events.length ? "" : empty("📜", "Событий нет")}</div></div>`.value;
    }

    delegate(root, "submit", { filter: async (form) => { filters = formValues(form); await load(); draw(); } });
    root.addEventListener("change", (event) => { if (event.target.dataset.act === "live") live = event.target.checked; });

    await load();
    draw();

    const timer = setInterval(async () => {
        if (!live || document.querySelector(".overlay")) return;
        try { await load(); draw(); } catch { /* повторим */ }
    }, 5000);
    onLeave(() => clearInterval(timer));
}
