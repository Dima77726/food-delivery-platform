// Администратор: обзор платформы, продажи и служебные операции.

import { get, post, restaurants } from "../api.js";
import { html, delegate, money, ago, empty, skeleton, toast, reportError, ROLE_ICONS, ROLE_LABELS, confirmDialog } from "../ui.js";

const isoDay = (offset = 0) => new Date(Date.now() + offset * 864e5).toISOString().slice(0, 10);

export default async function adminPage({ root }) {
    let days = 30;
    let data = null;
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Обзор платформы</h1></div>${skeleton(3)}</div>`.value;

    async function load() {
        const [sales, users, rests, audit] = await Promise.all([
            get(`/api/v1/admin/analytics/sales?from=${isoDay(-days)}&to=${isoDay(1)}`).catch(() => null),
            get("/api/v1/admin/users").catch(() => []),
            restaurants().catch(() => []),
            get("/api/v1/admin/audit?limit=8").catch(() => []),
        ]);
        data = { sales, users, rests, audit };
    }

    function chart(list) {
        if (!list?.length) return empty("📊", "За период заказов не было");
        const max = Math.max(...list.map((d) => d.revenue), 1);
        return html`<div class="bars">${list.map((d) => html`<div class="bar" style="height:${Math.max(6, (d.revenue / max) * 100)}%" title="${d.day}: ${money(d.revenue)}, заказов ${d.orders}"><span>${money(d.revenue)}</span></div>`)}</div>
            <div class="row" style="gap:8px">${list.map((d) => html`<div class="bar-label" style="flex:1">${d.day.slice(5)}</div>`)}</div>`;
    }

    function draw() {
        const { sales, users, rests, audit } = data;
        const byRole = (r) => users.filter((u) => u.roles.includes(r)).length;
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Обзор платформы</h1>
            <div class="row wrap right">${[7, 30, 90, 365].map((n) => html`<button class="chip ${days === n ? "on" : ""}" data-act="range" data-days="${n}">${n} дн.</button>`)}</div></div>
            <div class="kpis"><div class="kpi"><div class="v">${sales?.totalOrders ?? 0}</div><div class="l">заказов за период</div></div>
                <div class="kpi"><div class="v">${money(sales?.totalRevenue ?? 0)}</div><div class="l">выручка</div></div>
                <div class="kpi"><div class="v">${users.length}</div><div class="l">пользователей</div></div>
                <div class="kpi"><div class="v">${rests.filter((r) => r.active).length}<span class="muted" style="font-size:16px"> / ${rests.length}</span></div><div class="l">ресторанов открыто</div></div></div>
            <div class="grid two"><div class="card"><h3 class="card-title">Выручка по дням</h3>${chart(sales?.days)}</div>
                <div class="card stack"><h3 class="card-title">Кто на платформе</h3>${["CUSTOMER", "RESTAURANT_OWNER", "COURIER", "ADMIN"].map((r) => html`<div class="row between"><span>${ROLE_ICONS[r]} ${ROLE_LABELS[r]}</span><b>${byRole(r)}</b></div>`)}
                    <a class="btn block" href="#/admin/users">Управлять пользователями →</a></div>
                <div class="card stack"><div class="row between"><h3>Последние события</h3><a class="muted small" href="#/admin/audit">весь журнал →</a></div>
                    ${audit.length ? audit.map((e) => html`<div class="row" style="align-items:flex-start;border-bottom:1px dashed var(--border);padding:6px 0"><span class="badge ${e.outcome === "SUCCESS" ? "green" : "red"}">${e.action}</span>
                        <div style="flex:1" class="small">${e.details || ""}<div class="muted">${e.actorEmail || "система"} · ${ago(e.occurredAt)}</div></div></div>`) : empty("📜", "Событий нет")}</div>
                <div class="card stack"><h3>Служебные операции</h3>
                    <button class="btn" data-act="reindex">🔎 Перестроить поисковый индекс</button>
                    <button class="btn" data-act="rebuild">🧠 Пересобрать рекомендации</button>
                    <p class="muted small">Операции безопасные и повторяемые: индекс и граф рекомендаций строятся из основной базы.</p></div></div></div>`.value;
    }

    delegate(root, "click", {
        range: async (el) => { days = Number(el.dataset.days); await load(); draw(); },
        reindex: async () => {
            if (!(await confirmDialog("Перестроить поиск?", "Индекс строится заново из каталога, поиск на минуту может отвечать неполно.", "Перестроить"))) return;
            try { await post("/api/v1/admin/search/reindex"); toast("Индекс перестроен", "ok"); } catch (error) { reportError(error); }
        },
        rebuild: async () => {
            try { await post("/api/v1/admin/recommendations/rebuild"); toast("Рекомендации пересобраны", "ok"); } catch (error) { reportError(error); }
        },
    });

    await load();
    draw();
}
