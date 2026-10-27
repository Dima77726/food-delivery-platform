// Профиль: данные аккаунта, уведомления, привязка Telegram и Web Push.

import { get, post } from "../api.js";
import { html, delegate, initials, ago, empty, skeleton, ROLE_LABELS, ROLE_ICONS, toast, reportError } from "../ui.js";
import { pushSupported, currentPushSubscription, enablePush, disablePush } from "../push.js";

const CHANNEL = { EMAIL: ["✉️", "Почта"], TELEGRAM: ["✈️", "Telegram"], SMS: ["💬", "SMS"], PUSH: ["🔔", "Push"] };

export default async function profilePage({ root, me }) {
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Профиль</h1></div>${skeleton(2)}</div>`.value;
    let items = [];
    let link = null;
    let pushOn = false;

    const load = async () => {
        items = await get("/api/v1/notifications/me").catch(() => []);
        pushOn = Boolean(await currentPushSubscription().catch(() => null));
    };

    function draw() {
        const u = me.user;
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Профиль</h1></div>
            <div class="split"><div class="stack lg">
                <div class="card"><div class="row"><span class="avatar lg">${initials(u.fullName)}</span>
                    <div class="stack" style="gap:2px"><h2>${u.fullName}</h2><span class="muted">${u.email}${u.phone ? " · " + u.phone : ""}</span>
                    <div class="row wrap">${u.roles.map((r) => html`<span class="badge orange">${ROLE_ICONS[r]} ${ROLE_LABELS[r]}</span>`)}</div></div></div></div>
                <div class="card stack"><div class="row between"><h3>🔔 Уведомления</h3><span class="muted small">${items.length ? "последние " + items.length : ""}</span></div>
                    ${items.length ? items.map((n) => html`<div class="row" style="align-items:flex-start;padding:10px 0;border-bottom:1px dashed var(--border)">
                        <span style="font-size:22px">${(CHANNEL[n.channel] || ["🔔"])[0]}</span>
                        <div style="flex:1"><b>${n.subject}</b><div class="muted">${n.body}</div><div class="muted small">${(CHANNEL[n.channel] || [0, n.channel])[1]} · ${ago(n.createdAt)}</div></div>
                        ${n.orderId && me.role === "CUSTOMER" ? html`<a class="btn sm" href="#/orders/${n.orderId}">Заказ №${n.orderId}</a>` : ""}
                        <span class="badge ${n.status === "SENT" ? "green" : n.status === "FAILED" ? "red" : "yellow"}">${{ SENT: "доставлено", FAILED: "ошибка", PENDING: "ждёт" }[n.status] || n.status}</span></div>`)
                        : empty("🔕", "Уведомлений пока нет", "Они появятся, когда в заказах что-то изменится.")}</div>
            </div>
            <aside class="stack sticky"><div class="card stack"><h3>✈️ Telegram</h3>
                <p class="muted small">Подключите бота, и статусы заказов будут приходить прямо в мессенджер.</p>
                ${link ? html`<a class="btn primary block" href="${link.deepLink}" target="_blank" rel="noopener">Открыть Telegram</a><p class="muted small">Ссылка действует до ${new Date(link.expiresAt).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}. В открывшемся чате нажмите «Start».</p>`
                    : html`<button class="btn primary block" data-act="link">Подключить Telegram</button>`}</div>
                <div class="card stack"><h3>🔔 Уведомления в браузере</h3>
                    <p class="muted small">Web Push: статусы заказов приходят, даже когда вкладка закрыта. Сообщение шифруется ключом этого браузера — push-сервис (Google, Mozilla, Apple) его не читает.</p>
                    ${!pushSupported() ? html`<p class="muted small">Этот браузер Web Push не поддерживает.</p>`
                        : pushOn ? html`<button class="btn block" data-act="push-off">Выключить в этом браузере</button>`
                            : html`<button class="btn primary block" data-act="push-on">Включить в этом браузере</button>`}</div></aside></div></div>`.value;
    }

    delegate(root, "click", {
        "push-on": async () => {
            try { await enablePush(); pushOn = true; toast("Уведомления в браузере включены", "ok"); draw(); } catch (error) {
                reportError(error.status === 503 ? new Error("Web Push не настроен на платформе (нет ключей VAPID)") : error);
            }
        },
        "push-off": async () => { await disablePush(); pushOn = false; toast("Уведомления в браузере выключены"); draw(); },
        link: async () => {
            try { link = await post("/api/v1/notifications/telegram/link"); draw(); } catch (error) {
                reportError(error.status === 404 || error.status === 503 ? new Error("Telegram-бот не включён в этой установке") : error);
            }
        },
    });

    await load();
    draw();
}
