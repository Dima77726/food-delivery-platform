// Мессенджер: список чатов с фильтрами, переписка, вложения, информация о чате, поиск, настройки.
//
// Права проверяет сервер на каждом действии; интерфейс лишь не показывает недоступные кнопки. Отправка идемпотентна:
// clientMessageId создаётся один раз на сообщение и повторяется при повторной попытке — дубля не будет.

import { get, post, put, patch, del, accessToken, restaurantName } from "../api.js";
import { html, raw, esc, initials, toast, reportError, modal, confirmDialog, delegate, $, empty, debounce } from "../ui.js";
import { onEvent, send as wsSend, connectionState, deviceId, refreshUnread } from "../messenger-client.js";
import { startCall } from "../calls.js";

const API = "/api/v1/messenger";
const FILTERS = [["ALL", "Все"], ["PERSONAL", "Личные"], ["WORK", "Рабочие"], ["ORDER", "Заказы"], ["SUPPORT", "Поддержка"], ["ARCHIVE", "Архив"]];
const EMOJI = ["👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "👌", "✅", "👀"];
const SIDE_LABEL = { CUSTOMER: "клиент", COURIER: "курьер", RESTAURANT: "ресторан", SUPPORT: "поддержка", MEMBER: "" };
const ROLE_LABEL = { OWNER: "владелец", ADMIN: "админ", MEMBER: "" };
const POSITION = { OWNER: "Владелец", MANAGER: "Руководитель", ADMINISTRATOR: "Администратор", OPERATOR: "Оператор", KITCHEN: "Кухня", PICKUP: "Выдача" };
const KIND_ICON = { DIRECT: "💬", GROUP: "👥", WORK: "🏪", CHANNEL: "📢", ORDER: "🧾", SUPPORT: "🛟" };
const MAX_FILE = { IMAGE: 10, VIDEO: 50, FILE: 25 };

const linkCache = new Map();

/** Короткоживущая ссылка на вложение для <img>/<video>: права проверяются сервером при каждом обращении. */
async function attachmentUrl(id, thumb = false) {
    const key = id + (thumb ? ":t" : "");
    const cached = linkCache.get(key);
    if (cached && cached.until > Date.now()) return cached.url;
    const { url } = await get(`${API}/attachments/${id}/link?thumb=${thumb}`);
    linkCache.set(key, { url, until: Date.now() + 4 * 60_000 });
    return url;
}

const timeOf = (iso) => (iso ? new Date(iso).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) : "");
function shortWhen(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    const today = new Date();
    if (d.toDateString() === today.toDateString()) return timeOf(iso);
    return d.toLocaleDateString("ru-RU", { day: "numeric", month: "short" });
}
const sizeOf = (bytes) => (bytes > 1024 * 1024 ? (bytes / 1024 / 1024).toFixed(1) + " МБ" : Math.max(1, Math.round(bytes / 1024)) + " КБ");

export default async function messengerPage({ root, params, query, me, navigate, onLeave }) {
    const st = {
        chats: [], filter: "ALL", q: "", chatId: params.chatId ? Number(params.chatId) : null,
        details: null, messages: [], lastSeq: 0, eventSeq: 0, lastReadSeq: 0, pins: [],
        reply: null, edit: null, internal: false, important: false, uploads: [], typing: new Map(),
        workplaces: [], queue: [], loadingOlder: false, noMoreOlder: false, revoked: null, presence: null,
    };
    let readTimer = null;

    root.innerHTML = html`<div class="ms" data-ms>
        <aside class="ms-side">
            <div class="ms-side-head">
                <h2>Сообщения</h2>
                <div class="row" style="gap:4px">
                    <button class="icon-btn" data-act="calls" title="История звонков">📞</button>
                    <button class="icon-btn" data-act="settings" title="Настройки и уведомления">⚙️</button>
                    <button class="icon-btn" data-act="new" title="Новый чат">✏️</button>
                </div>
            </div>
            <div class="ms-conn" data-conn></div>
            <input class="input ms-search" data-search placeholder="Поиск по сообщениям…" autocomplete="off">
            <div class="ms-filters" data-filters></div>
            <div class="ms-list" data-list><div class="muted small center" style="padding:20px">Загружаем чаты…</div></div>
        </aside>
        <section class="ms-main" data-main></section>
        <aside class="ms-info hidden" data-info></aside>
    </div>`.value;

    const box = $("[data-ms]", root);
    const listEl = $("[data-list]", root);
    const mainEl = $("[data-main]", root);
    const infoEl = $("[data-info]", root);

    // ---------- Список ----------

    async function loadChats() {
        try {
            const archived = st.filter === "ARCHIVE";
            st.chats = await get(`${API}/chats?archived=${archived}`);
            if (me.role === "ADMIN") st.queue = await get(`${API}/support/queue`).catch(() => []);
            renderList();
        } catch (error) {
            listEl.innerHTML = html`<div class="banner">Не удалось загрузить чаты: ${error.message}</div>`.value;
        }
    }

    function renderFilters() {
        $("[data-filters]", root).innerHTML = FILTERS.map(([key, label]) => {
            const count = key === "ALL" || key === "ARCHIVE" ? 0 : st.chats.filter((c) => c.category === key && c.unread && !c.mutedUntil).length;
            return html`<button class="chip ${st.filter === key ? "on" : ""}" data-act="filter" data-key="${key}">${label}${count ? html` <b>${count}</b>` : ""}</button>`.value;
        }).join("");
    }

    function renderList() {
        renderFilters();
        let rows = st.chats;
        if (st.filter !== "ALL" && st.filter !== "ARCHIVE") rows = rows.filter((c) => c.category === st.filter);
        const queue = me.role === "ADMIN" && (st.filter === "ALL" || st.filter === "SUPPORT") && st.queue.length
            ? html`<div class="ms-list-sep">Очередь поддержки · ${st.queue.length}</div>${st.queue.map((c) => chatRow(c, true))}` : "";
        listEl.innerHTML = rows.length || queue
            ? html`${queue}${rows.map((c) => chatRow(c, false))}`.value
            : empty(st.filter === "ARCHIVE" ? "🗄️" : "💬", st.filter === "ARCHIVE" ? "Архив пуст" : "Чатов пока нет",
                st.filter === "ALL" ? "Напишите коллеге, откройте переписку из заказа или обратитесь в поддержку" : "").value;
        fillRestaurantNames();
    }

    function chatRow(c, queued) {
        const last = c.last;
        const preview = last ? (last.deleted ? "Сообщение удалено" : last.kind === "SYSTEM" || last.kind === "CALL" ? last.text
            : `${last.senderId === me.user.id ? "Вы: " : last.senderName && c.kind !== "DIRECT" ? last.senderName + ": " : ""}${last.text ?? "📎 Вложение"}`) : "Нет сообщений";
        return html`<button class="ms-row ${c.id === st.chatId ? "on" : ""}" data-act="open" data-id="${c.id}">
            <span class="avatar">${KIND_ICON[c.kind] && c.kind !== "DIRECT" ? KIND_ICON[c.kind] : initials(c.title)}</span>
            <span class="ms-row-body">
                <span class="ms-row-top"><b class="ms-ellipsis">${c.title}</b><span class="muted small nowrap">${shortWhen(c.lastMessageAt)}</span></span>
                <span class="ms-row-mid">${c.restaurantId ? html`<span class="ms-place" data-rest="${c.restaurantId}">Точка №${c.restaurantId}</span>` : ""}${c.readOnly ? html`<span class="ms-place">только чтение</span>` : ""}${queued ? html`<span class="badge orange">новое</span>` : ""}</span>
                <span class="ms-row-bottom"><span class="muted small ms-ellipsis">${preview}</span>
                    ${c.mutedUntil ? html`<span title="Уведомления отключены">🔕</span>` : ""}
                    ${c.unreadMentions ? html`<span class="ms-badge mention">@</span>` : ""}
                    ${c.unread ? html`<span class="ms-badge ${c.mutedUntil ? "muted" : ""}">${c.unread}</span>` : ""}</span>
            </span></button>`;
    }

    async function fillRestaurantNames() {
        for (const el of root.querySelectorAll("[data-rest]")) {
            const name = await restaurantName(Number(el.dataset.rest));
            el.textContent = "🏪 " + name;
        }
    }

    // ---------- Открытие чата ----------

    async function openChat(chatId, aroundSeq = null) {
        st.chatId = chatId;
        st.reply = st.edit = null;
        st.internal = st.important = false;
        st.messages = [];
        st.revoked = null;
        st.noMoreOlder = false;
        box.classList.add("chat-open");
        if (location.hash !== `#/messenger/${chatId}`) history.replaceState(null, "", `#/messenger/${chatId}`);
        renderList();
        mainEl.innerHTML = html`<div class="ms-empty muted">Загружаем переписку…</div>`.value;
        try {
            st.details = await get(`${API}/chats/${chatId}`);
            const summary = st.chats.find((c) => c.id === chatId);
            const target = aroundSeq ?? (summary?.unread ? summary.firstUnreadSeq : null);
            const page = await get(`${API}/chats/${chatId}/messages?limit=60${target ? `&around=${target}` : ""}`);
            applyPage(page);
            st.pins = await get(`${API}/chats/${chatId}/pins`);
            renderChat();
            scrollTo(target);
            loadPresence();
        } catch (error) {
            st.details = null;
            mainEl.innerHTML = (error.status === 404
                ? empty("🔒", "Чат недоступен", "Вас нет среди участников: чат удалён, вас исключили или закончился доступ сотрудника")
                : empty("😵", "Не удалось открыть чат", error.message)).value;
        }
    }

    function applyPage(page) {
        const byId = new Map(st.messages.map((m) => [m.id, m]));
        for (const m of page.messages) byId.set(m.id, m);
        st.messages = [...byId.values()].sort((a, b) => a.seq - b.seq);
        st.lastSeq = page.lastSeq;
        st.eventSeq = Math.max(st.eventSeq, page.eventSeq);
        st.lastReadSeq = page.lastReadSeq;
    }

    async function catchUp() {
        if (!st.chatId || !st.details) return;
        try {
            const page = await get(`${API}/chats/${st.chatId}/changes?since=${st.eventSeq}`);
            applyPage(page);
            renderMessages();
        } catch (error) {
            if (error.status === 404) return revoke("Доступ к чату закрыт");
        }
    }

    function revoke(reason) {
        st.revoked = reason;
        renderChat();
        loadChats();
    }

    // ---------- Отрисовка переписки ----------

    function renderChat() {
        const d = st.details;
        if (!d) return;
        const c = d.chat;
        const p = d.permissions;
        const subtitle = st.revoked ? st.revoked
            : c.kind === "DIRECT" ? presenceText()
            : `${d.members.length} ${plural(d.members.length, ["участник", "участника", "участников"])}${c.readOnly ? " · только чтение" : ""}`;
        mainEl.innerHTML = html`
            <header class="ms-head">
                <button class="icon-btn ms-back" data-act="back" title="К списку">←</button>
                <span class="avatar">${c.kind === "DIRECT" ? initials(c.title) : KIND_ICON[c.kind]}</span>
                <div class="ms-head-title"><b class="ms-ellipsis">${c.title}</b><span class="muted small ms-ellipsis" data-subtitle>${subtitle}</span></div>
                ${p.canCall && !st.revoked ? html`<button class="icon-btn" data-act="call" data-kind="AUDIO" title="Аудиозвонок">📞</button><button class="icon-btn" data-act="call" data-kind="VIDEO" title="Видеозвонок">🎥</button>` : ""}
                <button class="icon-btn" data-act="chat-search" title="Поиск в чате">🔍</button>
                <button class="icon-btn" data-act="info" title="О чате">ℹ️</button>
            </header>
            ${st.pins.length ? html`<button class="ms-pin" data-act="jump" data-seq="${st.pins[0].seq}">📌 <span class="ms-ellipsis">${st.pins[0].text || "Вложение"}</span>${st.pins.length > 1 ? html`<span class="muted small">+${st.pins.length - 1}</span>` : ""}</button>` : ""}
            ${st.revoked ? html`<div class="banner" style="margin:10px">🔒 ${st.revoked}. Новые сообщения, вложения и звонки этого чата вам больше недоступны.</div>` : ""}
            <div class="ms-log" data-log></div>
            <button class="ms-down hidden" data-act="down" data-down>↓</button>
            <div class="ms-typing muted small" data-typing></div>
            ${composer(d)}`.value;
        renderMessages();
        const log = $("[data-log]", mainEl);
        log.addEventListener("scroll", onScroll);
    }

    function presenceText() {
        const p = st.presence;
        if (!p || !p.visible) return "личная переписка";
        if (p.online) return "в сети";
        return p.lastSeenAt ? `был(а) ${shortWhen(p.lastSeenAt)} в ${timeOf(p.lastSeenAt)}` : "не в сети";
    }

    async function loadPresence() {
        const c = st.details?.chat;
        if (!c || c.kind !== "DIRECT" || !c.peerId) return;
        try {
            const result = await get(`${API}/presence?userIds=${c.peerId}`);
            st.presence = result[c.peerId];
            const el = $("[data-subtitle]", mainEl);
            if (el && !st.revoked) el.textContent = presenceText();
        } catch { /* присутствие — не главное */ }
    }

    function composer(d) {
        if (st.revoked) return "";
        const p = d.permissions;
        if (!p.canPost) {
            const why = d.chat.kind === "CHANNEL" ? "В канал объявлений пишут уполномоченные сотрудники" : d.chat.readOnly ? "Переписка закрыта: заказ завершён" : "Писать сюда сейчас нельзя";
            return html`<div class="ms-readonly muted small">${why}</div>`;
        }
        const a = d.audience;
        const hint = st.internal ? a.internal : a.everyone;
        return html`<form class="ms-compose" data-act="send" data-form>
            ${st.reply ? html`<div class="ms-reply-bar">↩︎ <b>${st.reply.senderName || "Сообщение"}</b>: <span class="ms-ellipsis">${st.reply.text || "вложение"}</span><button type="button" class="icon-btn" data-act="cancel-reply">✕</button></div>` : ""}
            ${st.edit ? html`<div class="ms-reply-bar">✏️ Редактирование<button type="button" class="icon-btn" data-act="cancel-edit">✕</button></div>` : ""}
            <div class="ms-uploads" data-uploads></div>
            <div class="ms-audience ${st.internal ? "internal" : a.externalParties ? "external" : ""}">
                ${st.internal ? "🔒" : a.externalParties ? "👁️" : "👥"} <span>${hint}</span>
                ${p.canInternal ? html`<label class="ms-toggle"><input type="checkbox" data-act="internal" ${st.internal ? "checked" : ""}> Внутренняя заметка</label>` : ""}
                ${p.canAnnounce ? html`<label class="ms-toggle"><input type="checkbox" data-act="important" ${st.important ? "checked" : ""}> Важное (с ознакомлением)</label>` : ""}
            </div>
            <div class="ms-compose-row">
                ${st.edit ? "" : html`<label class="icon-btn" title="Фото, видео или файл">📎<input type="file" multiple hidden data-file></label>`}
                <textarea class="input" name="text" rows="1" maxlength="4000" placeholder="${st.internal ? "Заметка для своей стороны…" : "Сообщение…"}" data-text>${st.edit ? st.edit.text : ""}</textarea>
                <button class="btn primary" type="submit">${st.edit ? "Сохранить" : "➤"}</button>
            </div>
        </form>`;
    }

    function renderMessages() {
        const log = $("[data-log]", mainEl);
        if (!log || !st.details) return;
        const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 60;
        const receipts = st.details.receipts || {};
        const peers = Object.values(receipts);
        const direct = st.details.chat.kind === "DIRECT";
        let unreadMarked = false;

        const parts = [];
        if (st.messages.length && st.details.me.visibleFromSeq > 1 && st.messages[0].seq <= st.details.me.visibleFromSeq) {
            parts.push(html`<div class="ms-sys muted small">История до вашего вступления скрыта настройками чата</div>`);
        }
        let lastDay = "";
        for (const m of st.messages) {
            const day = new Date(m.createdAt).toLocaleDateString("ru-RU", { day: "numeric", month: "long" });
            if (day !== lastDay) { parts.push(html`<div class="ms-day">${day}</div>`); lastDay = day; }
            if (!unreadMarked && m.seq > st.lastReadSeq && m.senderId !== me.user.id && m.senderId != null) {
                parts.push(html`<div class="ms-unread-line" data-unread-line>Новые сообщения</div>`);
                unreadMarked = true;
            }
            parts.push(bubble(m, peers, direct));
        }
        for (const u of st.pending || []) parts.push(pendingBubble(u));
        log.innerHTML = parts.length ? html`${parts}`.value : empty("👋", "Здесь пока пусто", "Напишите первое сообщение").value;
        hydrateMedia(log);
        if (atBottom) log.scrollTop = log.scrollHeight;
        scheduleRead();
    }

    function bubble(m, peers, direct) {
        const mine = m.senderId === me.user.id;
        if (m.kind === "SYSTEM" || m.kind === "CALL") {
            return html`<div class="ms-sys muted small" data-seq="${m.seq}">${m.kind === "CALL" ? "📞 " : "ℹ️ "}${m.text}</div>`;
        }
        let status = "";
        if (mine && !m.deleted) {
            const read = peers.some((r) => r.readSeq >= m.seq);
            const delivered = peers.some((r) => r.deliveredSeq >= m.seq);
            status = read ? html`<span class="ms-tick read" title="${direct ? "Прочитано" : "Прочитано участниками"}">✓✓</span>`
                : delivered ? html`<span class="ms-tick" title="Доставлено">✓✓</span>` : html`<span class="ms-tick" title="Отправлено">✓</span>`;
        }
        const fwd = m.forwardedFrom ? (() => { try { return JSON.parse(m.forwardedFrom); } catch { return null; } })() : null;
        return html`<div class="ms-msg ${mine ? "mine" : ""} ${m.audience ? "internal" : ""} ${m.important ? "important" : ""}" data-seq="${m.seq}" data-id="${m.id}">
            <div class="ms-bubble">
                ${!mine && !direct ? html`<div class="ms-who">${m.senderName}</div>` : ""}
                ${m.audience ? html`<div class="ms-note">🔒 Видно только: ${m.audience === "RESTAURANT" ? "ресторану" : "поддержке"}</div>` : ""}
                ${m.important ? html`<div class="ms-note">📣 Важное объявление</div>` : ""}
                ${fwd ? html`<div class="ms-fwd">↪︎ Переслано от ${fwd.senderName}${fwd.chatTitle ? ` · ${fwd.chatTitle}` : ""}</div>` : ""}
                ${m.replyTo ? html`<button class="ms-quote" data-act="jump" data-seq="${m.replyTo.seq}"><b>${m.replyTo.senderName || ""}</b> ${m.replyTo.deleted ? "Сообщение удалено" : m.replyTo.text || "вложение"}</button>` : ""}
                ${m.deleted ? html`<i class="muted">Сообщение удалено</i>` : html`${m.text ? html`<div class="ms-text">${highlight(m.text)}</div>` : ""}${attachments(m.attachments)}`}
                ${m.reactions?.length ? html`<div class="ms-reacts">${m.reactions.map((r) => html`<button class="ms-react ${r.mine ? "on" : ""}" data-act="react" data-id="${m.id}" data-emoji="${r.emoji}">${r.emoji} ${r.count}</button>`)}</div>` : ""}
                ${m.important && !m.deleted ? html`<div class="ms-ack">${m.acked ? html`<span class="ok-text small">✓ Вы ознакомились</span>` : html`<button class="btn sm green" data-act="ack" data-id="${m.id}">Ознакомлен(а)</button>`}
                    ${st.details.permissions.canAnnounce ? html`<button class="btn sm ghost" data-act="acks" data-id="${m.id}">Ознакомились: ${m.ackCount}</button>` : ""}</div>` : ""}
                <div class="ms-meta">${m.pinned ? "📌 " : ""}${m.editedAt ? "изменено · " : ""}${timeOf(m.createdAt)} ${status}</div>
            </div>
            ${m.deleted || st.revoked ? "" : html`<button class="ms-more" data-act="menu" data-id="${m.id}" title="Действия">⋯</button>`}
        </div>`;
    }

    function pendingBubble(u) {
        return html`<div class="ms-msg mine pending"><div class="ms-bubble"><div class="ms-text">${u.text}</div>
            <div class="ms-meta">${u.failed ? html`<span class="bad-text">не отправлено</span> <button class="btn sm" data-act="resend" data-key="${u.key}">Повторить</button>` : "отправляется…"}</div></div></div>`;
    }

    function highlight(text) {
        // Упоминания подсвечиваются; остальное экранируется шаблоном html``.
        const parts = String(text).split(/(@[\p{L}\d_]+)/u);
        return parts.map((p) => (p.startsWith("@") ? html`<span class="ms-mention">${p}</span>` : html`${p}`));
    }

    function attachments(list) {
        if (!list?.length) return "";
        return html`<div class="ms-files">${list.map((a) => {
            if (a.kind === "IMAGE") return html`<button class="ms-img" data-act="view" data-att="${a.id}" data-kind="IMAGE"><img alt="${a.fileName}" data-src="${a.id}" data-thumb="${a.hasThumbnail}"></button>`;
            if (a.kind === "VIDEO") return html`<video class="ms-video" controls preload="metadata" data-src="${a.id}"></video>`;
            if (a.kind === "AUDIO") return html`<audio controls preload="none" data-src="${a.id}"></audio>`;
            return html`<button class="ms-file" data-act="download" data-att="${a.id}">📄 <span class="ms-ellipsis">${a.fileName}</span><span class="muted small">${sizeOf(a.size)}</span></button>`;
        })}</div>`;
    }

    async function hydrateMedia(scope) {
        for (const el of scope.querySelectorAll("[data-src]")) {
            const id = el.dataset.src;
            el.removeAttribute("data-src");
            try {
                el.src = await attachmentUrl(id, el.dataset.thumb === "true");
            } catch {
                el.replaceWith(Object.assign(document.createElement("span"), { className: "muted small", textContent: "🚫 Вложение недоступно" }));
            }
        }
    }

    // ---------- Прочтение и прокрутка ----------

    function scheduleRead() {
        clearTimeout(readTimer);
        readTimer = setTimeout(() => {
            const log = $("[data-log]", mainEl);
            if (!log || !st.chatId || st.revoked || document.hidden) return;
            const nearBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
            $("[data-down]", mainEl)?.classList.toggle("hidden", nearBottom);
            if (!nearBottom) return;
            const last = st.messages[st.messages.length - 1];
            if (last && last.seq > st.lastReadSeq) {
                st.lastReadSeq = last.seq;
                post(`${API}/chats/${st.chatId}/read`, { seq: last.seq }).then(() => {
                    const summary = st.chats.find((c) => c.id === st.chatId);
                    if (summary) { summary.unread = 0; summary.unreadMentions = 0; renderList(); }
                    refreshUnread();
                }).catch(() => {});
            }
        }, 400);
    }

    async function onScroll(event) {
        const log = event.target;
        scheduleRead();
        if (log.scrollTop < 80 && !st.loadingOlder && !st.noMoreOlder && st.messages.length) {
            st.loadingOlder = true;
            const before = st.messages[0].seq;
            const height = log.scrollHeight;
            try {
                const page = await get(`${API}/chats/${st.chatId}/messages?before=${before}&limit=50`);
                if (!page.messages.length) st.noMoreOlder = true;
                applyPage(page);
                renderMessages();
                log.scrollTop = log.scrollHeight - height;
            } catch { /* повторит при следующей прокрутке */ }
            st.loadingOlder = false;
        }
    }

    function scrollTo(seq) {
        const log = $("[data-log]", mainEl);
        if (!log) return;
        const target = seq ? (log.querySelector(`[data-seq="${seq}"]`) || log.querySelector("[data-unread-line]")) : null;
        if (target) {
            target.scrollIntoView({ block: "center" });
            target.classList.add("flash");
        } else {
            log.scrollTop = log.scrollHeight;
        }
        scheduleRead();
    }

    async function jump(seq) {
        if (!st.messages.some((m) => m.seq === seq)) {
            const page = await get(`${API}/chats/${st.chatId}/messages?around=${seq}&limit=60`);
            st.messages = [];
            applyPage(page);
            renderMessages();
        }
        scrollTo(seq);
    }

    // ---------- Отправка ----------

    st.pending = [];

    async function submit(form) {
        const textArea = form.elements.text;
        const text = textArea.value.trim();
        if (st.edit) {
            if (!text) return;
            await patch(`${API}/messages/${st.edit.id}`, { text });
            st.edit = null;
            renderChat();
            return catchUp();
        }
        const ready = st.uploads.filter((u) => u.status === "done");
        if (st.uploads.some((u) => u.status === "uploading")) return toast("Дождитесь окончания загрузки файлов");
        if (!text && !ready.length) return;
        const item = {
            key: crypto.randomUUID(), text, chatId: st.chatId, replyToId: st.reply?.id ?? null, internal: st.internal,
            important: st.important, attachmentIds: ready.map((u) => u.id), mentions: mentionsIn(text),
        };
        textArea.value = "";
        st.reply = null;
        st.uploads = [];
        st.important = false;
        renderUploads();
        await deliver(item);
        renderChat();
        $("[data-text]", mainEl)?.focus();
    }

    async function deliver(item) {
        item.failed = false;
        if (!st.pending.includes(item)) st.pending.push(item);
        renderMessages();
        try {
            const message = await post(`${API}/chats/${item.chatId}/messages`, {
                text: item.text, clientMessageId: item.key, replyToId: item.replyToId, internal: item.internal,
                important: item.important, attachmentIds: item.attachmentIds, mentions: item.mentions,
            });
            st.pending = st.pending.filter((p) => p !== item);
            if (item.chatId === st.chatId) {
                applyPage({ messages: [message], lastSeq: Math.max(st.lastSeq, message.seq), eventSeq: message.eventSeq, lastReadSeq: message.seq });
                renderMessages();
                const log = $("[data-log]", mainEl);
                if (log) log.scrollTop = log.scrollHeight;
            }
            loadChats();
        } catch (error) {
            item.failed = true;
            renderMessages();
            // Ошибку сети можно повторить тем же ключом; ошибку прав — нет.
            if (error.status && error.status !== 0 && error.status !== 503) {
                st.pending = st.pending.filter((p) => p !== item);
                renderMessages();
                reportError(error);
            }
        }
    }

    function mentionsIn(text) {
        const names = new Map((st.details?.members || []).map((m) => [m.name.split(" ")[0].toLowerCase(), m.userId]));
        const ids = [];
        for (const match of text.matchAll(/@([\p{L}\d_]+)/gu)) {
            const id = names.get(match[1].toLowerCase());
            if (id) ids.push(id);
        }
        return ids;
    }

    // ---------- Вложения: загрузка с прогрессом, отмена, повтор ----------

    function kindOf(file) {
        if (file.type.startsWith("image/")) return "IMAGE";
        if (file.type.startsWith("video/")) return "VIDEO";
        return "FILE";
    }

    function addFiles(files) {
        for (const file of files) {
            if (st.uploads.length >= 10) { toast("Не больше 10 вложений в сообщении", "bad"); break; }
            const limit = MAX_FILE[kindOf(file)] * 1024 * 1024;
            if (file.size > limit) { toast(`«${file.name}» больше ${MAX_FILE[kindOf(file)]} МБ`, "bad"); continue; }
            const upload = { key: crypto.randomUUID(), file, progress: 0, status: "uploading", id: null, xhr: null, error: null };
            st.uploads.push(upload);
            startUpload(upload);
        }
        renderUploads();
    }

    function startUpload(upload) {
        const form = new FormData();
        form.append("file", upload.file);
        const xhr = new XMLHttpRequest();
        upload.xhr = xhr;
        upload.status = "uploading";
        upload.progress = 0;
        xhr.open("POST", `${API}/chats/${st.chatId}/attachments`);
        xhr.setRequestHeader("Authorization", `Bearer ${accessToken()}`);
        xhr.upload.onprogress = (e) => { if (e.lengthComputable) { upload.progress = Math.round((e.loaded / e.total) * 100); renderUploads(); } };
        xhr.onload = () => {
            if (xhr.status === 201) {
                const body = JSON.parse(xhr.responseText);
                upload.id = body.id;
                upload.status = "done";
            } else {
                upload.status = "failed";
                try { upload.error = JSON.parse(xhr.responseText).message; } catch { upload.error = `Ошибка ${xhr.status}`; }
            }
            renderUploads();
        };
        xhr.onerror = () => { upload.status = "failed"; upload.error = "Нет связи"; renderUploads(); };
        xhr.onabort = () => { st.uploads = st.uploads.filter((u) => u !== upload); renderUploads(); };
        xhr.send(form);
    }

    function renderUploads() {
        const el = $("[data-uploads]", mainEl);
        if (!el) return;
        el.innerHTML = st.uploads.map((u) => html`<div class="ms-upload ${u.status}">
            <span class="ms-ellipsis">${kindOf(u.file) === "IMAGE" ? "🖼️" : kindOf(u.file) === "VIDEO" ? "🎞️" : "📄"} ${u.file.name}</span>
            ${u.status === "uploading" ? html`<span class="ms-progress"><i style="width:${u.progress}%"></i></span><button type="button" class="icon-btn" data-act="cancel-upload" data-key="${u.key}" title="Отменить">✕</button>` : ""}
            ${u.status === "done" ? html`<span class="ok-text small">готово</span><button type="button" class="icon-btn" data-act="remove-upload" data-key="${u.key}" title="Убрать">✕</button>` : ""}
            ${u.status === "failed" ? html`<span class="bad-text small" title="${u.error}">${u.error}</span><button type="button" class="btn sm" data-act="retry-upload" data-key="${u.key}">Повторить</button><button type="button" class="icon-btn" data-act="remove-upload" data-key="${u.key}">✕</button>` : ""}
        </div>`.value).join("");
    }

    // ---------- Меню сообщения ----------

    function messageMenu(id) {
        const m = st.messages.find((x) => x.id === id);
        if (!m) return;
        const mine = m.senderId === me.user.id;
        const p = st.details.permissions;
        const items = [
            ["reply", "↩︎ Ответить"],
            ...(st.details.chat.kind !== "SUPPORT" && !m.audience ? [["forward", "↪︎ Переслать"]] : []),
            ...((p.canPin || st.details.chat.kind === "DIRECT") && !m.audience ? [[m.pinned ? "unpin" : "pin", m.pinned ? "📌 Открепить" : "📌 Закрепить"]] : []),
            ...(mine && m.text ? [["edit", "✏️ Изменить"]] : []),
            ...(mine || p.canPin ? [["delete", "🗑️ Удалить"]] : []),
            ...(st.details.chat.kind !== "DIRECT" ? [["readers", "👀 Кто прочитал"]] : []),
            ...(!mine ? [["report", "⚠️ Пожаловаться"]] : []),
        ];
        const { close } = modal({
            title: "Сообщение",
            body: html`<div class="ms-emoji-row">${EMOJI.map((e) => html`<button class="ms-emoji" data-emoji="${e}">${e}</button>`)}</div>
                <div class="stack" style="gap:6px;margin-top:12px">${items.map(([act, label]) => html`<button class="menu-item-row" data-msg-act="${act}">${label}</button>`)}</div>`,
            onMount: (overlay, closeModal) => overlay.addEventListener("click", async (event) => {
                const emoji = event.target.closest("[data-emoji]");
                const action = event.target.closest("[data-msg-act]");
                try {
                    if (emoji) { closeModal(); await react(m, emoji.dataset.emoji); }
                    if (action) { closeModal(); await menuAction(action.dataset.msgAct, m); }
                } catch (error) { reportError(error); }
            }),
        });
        return close;
    }

    async function react(m, emoji) {
        const mine = m.reactions?.find((r) => r.emoji === emoji && r.mine);
        if (mine) await del(`${API}/messages/${m.id}/reactions?emoji=${encodeURIComponent(emoji)}`);
        else await post(`${API}/messages/${m.id}/reactions`, { emoji });
        await catchUp();
    }

    async function menuAction(action, m) {
        switch (action) {
            case "reply": st.reply = m; st.edit = null; renderChat(); $("[data-text]", mainEl)?.focus(); break;
            case "edit": st.edit = m; st.reply = null; renderChat(); break;
            case "delete":
                if (await confirmDialog("Удалить сообщение?", "Сообщение исчезнет у всех участников, вложения будут удалены.", "Удалить", "danger")) {
                    await del(`${API}/messages/${m.id}`);
                    await catchUp();
                }
                break;
            case "pin": await post(`${API}/messages/${m.id}/pin`); st.pins = await get(`${API}/chats/${st.chatId}/pins`); renderChat(); break;
            case "unpin": await del(`${API}/messages/${m.id}/pin`); st.pins = await get(`${API}/chats/${st.chatId}/pins`); renderChat(); break;
            case "forward": return forwardDialog(m);
            case "readers": {
                const readers = await get(`${API}/messages/${m.id}/readers`);
                modal({ title: "Прочитали", body: readers.length ? html`<div class="stack">${readers.map((r) => html`<div>✓ ${r.name}</div>`)}</div>` : html`<p class="muted">Пока никто — или отчёты о прочтении выключены</p>`, actions: [{ label: "Закрыть" }] });
                break;
            }
            case "report": {
                modal({
                    title: "Пожаловаться на сообщение",
                    body: html`<p class="muted small">Жалобу рассмотрит поддержка платформы. Она увидит только это сообщение, а не всю переписку.</p>
                        <textarea class="input" rows="3" maxlength="500" data-reason placeholder="Что не так?"></textarea>`,
                    actions: [{ label: "Отмена" }, {
                        label: "Отправить", kind: "danger", onClick: async ({ root: r }) => {
                            await post(`${API}/messages/${m.id}/report`, { reason: $("[data-reason]", r).value });
                            toast("Жалоба отправлена", "ok");
                        },
                    }],
                });
                break;
            }
            default:
        }
    }

    async function forwardDialog(m) {
        const targets = st.chats.filter((c) => c.id !== st.chatId && !c.readOnly);
        modal({
            title: "Переслать в…",
            body: html`<p class="muted small">Рабочие сообщения пересылаются только в рабочие чаты той же точки, переписка по заказу — только в чаты этого заказа. Сервер проверит это сам.</p>
                <div class="stack" style="gap:4px;max-height:50vh;overflow:auto">${targets.map((c) => html`<label class="menu-item-row"><input type="checkbox" value="${c.id}"> ${KIND_ICON[c.kind]} ${c.title}</label>`)}</div>`,
            actions: [{ label: "Отмена" }, {
                label: "Переслать", kind: "primary", onClick: async ({ root: r }) => {
                    const ids = [...r.querySelectorAll("input:checked")].map((i) => Number(i.value));
                    if (!ids.length) { toast("Выберите чат"); return false; }
                    await post(`${API}/messages/${m.id}/forward`, { chatIds: ids });
                    toast("Переслано", "ok");
                    loadChats();
                },
            }],
        });
    }

    // ---------- Информация о чате ----------

    function renderInfo() {
        const d = st.details;
        if (!d) return;
        const c = d.chat;
        const p = d.permissions;
        const muted = !!c.mutedUntil;
        infoEl.classList.remove("hidden");
        infoEl.innerHTML = html`<div class="ms-info-head"><b>О чате</b><button class="icon-btn" data-act="close-info">✕</button></div>
            <div class="stack" style="padding:14px">
                <div class="center"><div class="avatar lg" style="margin:0 auto">${c.kind === "DIRECT" ? initials(c.title) : KIND_ICON[c.kind]}</div>
                    <h3 style="margin-top:8px">${c.title}</h3>
                    ${c.restaurantId ? html`<div class="muted small" data-rest="${c.restaurantId}">Точка №${c.restaurantId}</div>` : ""}
                    ${d.description ? html`<p class="muted small" style="margin-top:6px">${d.description}</p>` : ""}</div>
                ${c.orderId ? html`<a class="btn sm" href="#/orders/${c.orderId}">🧾 Открыть заказ №${c.orderId}</a>` : ""}
                <div class="muted small">${c.kind === "GROUP" || c.kind === "WORK" ? (d.historyVisibility === "FULL" ? "Новые участники видят всю историю" : "Новые участники видят историю с момента вступления") : ""}</div>
                <div class="row wrap">
                    <button class="btn sm" data-act="mute">${muted ? "🔔 Включить уведомления" : "🔕 Без уведомлений"}</button>
                    ${!d.me.viewingAsSupport ? html`<button class="btn sm" data-act="archive">${c.archived ? "Вернуть из архива" : "🗄️ В архив"}</button>` : ""}
                    ${p.canResolve && c.supportStatus === "OPEN" ? html`<button class="btn sm green" data-act="resolve">✓ Обращение решено</button>` : ""}
                </div>
                ${p.canManage ? html`<div class="row wrap"><button class="btn sm" data-act="edit-chat">✏️ Изменить</button>${p.isOwner ? html`<button class="btn sm danger" data-act="delete-chat">Удалить группу</button>` : ""}</div>` : ""}
                <div class="row between"><b>Участники · ${d.members.length}</b>${p.canInvite ? html`<button class="btn sm" data-act="invite">➕ Пригласить</button>` : ""}</div>
                <div class="stack" style="gap:6px">${d.members.map((m) => html`<div class="ms-member">
                    <span class="avatar">${initials(m.name)}</span>
                    <span style="flex:1">${m.name}${m.userId === me.user.id ? " (вы)" : ""}<br><span class="muted small">${[ROLE_LABEL[m.role], SIDE_LABEL[m.side], m.auto ? "по назначению" : ""].filter(Boolean).join(" · ")}</span></span>
                    ${p.canManage && m.userId !== me.user.id && !m.auto ? html`<button class="icon-btn" data-act="member-menu" data-user="${m.userId}" data-role="${m.role}">⋯</button>` : ""}
                    ${c.kind !== "DIRECT" && m.userId !== me.user.id ? html`<button class="icon-btn" data-act="dm" data-user="${m.userId}" title="Написать лично">💬</button>` : ""}
                </div>`)}</div>
                ${p.canLeave ? html`<button class="btn sm danger" data-act="leave">Выйти из чата</button>` : ""}
                ${c.kind === "WORK" || c.kind === "CHANNEL" ? html`<p class="muted small">Рабочие чаты точки собираются из назначений сотрудников: выйти из них нельзя, но можно отключить уведомления.</p>` : ""}
                ${c.kind === "DIRECT" ? html`<button class="btn sm ghost" data-act="block" data-user="${c.peerId}">🚫 Заблокировать</button>` : ""}
            </div>`.value;
        fillRestaurantNames();
    }

    // ---------- Диалоги ----------

    async function newChatDialog(tab = "direct") {
        const [contacts, workplaces] = await Promise.all([get(`${API}/contacts`).catch(() => []), get(`${API}/me/workplaces`).catch(() => [])]);
        const options = await Promise.all(workplaces.map(async (w) => ({ ...w, name: await restaurantName(w.restaurantId) })));
        modal({
            title: "Новый чат",
            wide: true,
            body: html`<div class="tabs" style="margin-bottom:12px">
                    <button class="tab ${tab === "direct" ? "on" : ""}" data-tab="direct">Коллеге</button>
                    <button class="tab ${tab === "group" ? "on" : ""}" data-tab="group">Группа</button>
                    <button class="tab ${tab === "support" ? "on" : ""}" data-tab="support">Поддержка</button></div>
                <div data-pane="direct" class="${tab === "direct" ? "" : "hidden"}">
                    ${contacts.length ? html`<div class="stack" style="gap:4px;max-height:50vh;overflow:auto">${contacts.map((c) => html`<button class="menu-item-row" data-dm="${c.userId}" ${c.blocked ? "disabled" : ""}><span class="avatar">${initials(c.name)}</span><span style="flex:1">${c.name}<br><span class="muted small">${POSITION[c.position]} · точка №${c.restaurantId}</span></span></button>`)}</div>`
                        : html`<p class="muted">Лично пишут коллеги по точке или сети. Клиенту, курьеру или ресторану напишите из карточки заказа, а в поддержку — во вкладке «Поддержка».</p>`}
                </div>
                <form data-pane="group" class="stack ${tab === "group" ? "" : "hidden"}" data-group-form>
                    <input class="input" name="title" placeholder="Название группы" maxlength="120" required>
                    <textarea class="input" name="description" rows="2" placeholder="Описание (необязательно)"></textarea>
                    <label class="field"><span>Где</span><select class="input" name="where">
                        <option value="">Личная группа коллег</option>
                        ${options.map((o) => html`<option value="r:${o.restaurantId}">Рабочая группа точки «${o.name}»</option>`)}
                        ${[...new Set(options.filter((o) => o.organizationId && (o.position === "OWNER" || o.position === "MANAGER")).map((o) => o.organizationId))].map((id) => html`<option value="o:${id}">Межфилиальная группа сети №${id}</option>`)}
                    </select></label>
                    <label class="field"><span>История для новых участников</span><select class="input" name="historyVisibility"><option value="">По умолчанию</option><option value="FULL">Вся история</option><option value="FROM_JOIN">С момента вступления</option></select></label>
                    <div class="stack" style="gap:4px;max-height:30vh;overflow:auto">${contacts.map((c) => html`<label class="menu-item-row"><input type="checkbox" name="m" value="${c.userId}"> ${c.name} <span class="muted small">${POSITION[c.position]}</span></label>`)}</div>
                    <button class="btn primary">Создать</button>
                </form>
                <form data-pane="support" class="stack ${tab === "support" ? "" : "hidden"}" data-support-form>
                    <p class="muted small">Обращение увидят сотрудники поддержки платформы. По конкретному заказу удобнее писать из карточки заказа.</p>
                    <input class="input" name="topic" placeholder="Тема обращения" maxlength="120">
                    ${options.some((o) => ["OWNER", "MANAGER", "ADMINISTRATOR"].includes(o.position)) ? html`<label class="field"><span>От чьего имени</span><select class="input" name="restaurantId"><option value="">От себя</option>${options.filter((o) => ["OWNER", "MANAGER", "ADMINISTRATOR"].includes(o.position)).map((o) => html`<option value="${o.restaurantId}">От точки «${o.name}»</option>`)}</select></label>` : ""}
                    <button class="btn primary">Написать в поддержку</button>
                </form>`,
            onMount: (overlay, close) => {
                overlay.addEventListener("click", async (event) => {
                    const tabBtn = event.target.closest("[data-tab]");
                    if (tabBtn) {
                        overlay.querySelectorAll("[data-tab]").forEach((t) => t.classList.toggle("on", t === tabBtn));
                        overlay.querySelectorAll("[data-pane]").forEach((p) => p.classList.toggle("hidden", p.dataset.pane !== tabBtn.dataset.tab));
                    }
                    const dm = event.target.closest("[data-dm]");
                    if (dm) {
                        try { const d = await post(`${API}/chats/direct`, { userId: Number(dm.dataset.dm) }); close(); await loadChats(); openChat(d.chat.id); } catch (error) { reportError(error); }
                    }
                });
                $("[data-group-form]", overlay).addEventListener("submit", async (event) => {
                    event.preventDefault();
                    const f = event.target;
                    const where = f.elements.where.value;
                    try {
                        const d = await post(`${API}/chats/groups`, {
                            title: f.elements.title.value, description: f.elements.description.value || null,
                            memberIds: [...f.querySelectorAll("input[name=m]:checked")].map((i) => Number(i.value)),
                            restaurantId: where.startsWith("r:") ? Number(where.slice(2)) : null,
                            organizationId: where.startsWith("o:") ? Number(where.slice(2)) : null,
                            historyVisibility: f.elements.historyVisibility.value || null,
                        });
                        close(); await loadChats(); openChat(d.chat.id);
                    } catch (error) { reportError(error); }
                });
                $("[data-support-form]", overlay).addEventListener("submit", async (event) => {
                    event.preventDefault();
                    const f = event.target;
                    try {
                        const d = await post(`${API}/support`, { topic: f.elements.topic.value, restaurantId: f.elements.restaurantId?.value ? Number(f.elements.restaurantId.value) : null });
                        close(); await loadChats(); openChat(d.chat.id);
                    } catch (error) { reportError(error); }
                });
            },
        });
    }

    async function inviteDialog() {
        const contacts = await get(`${API}/contacts`);
        const present = new Set(st.details.members.map((m) => m.userId));
        const candidates = contacts.filter((c) => !present.has(c.userId));
        modal({
            title: "Пригласить в чат",
            body: candidates.length ? html`<div class="stack" style="gap:4px;max-height:50vh;overflow:auto">${candidates.map((c) => html`<label class="menu-item-row"><input type="checkbox" value="${c.userId}"> ${c.name} <span class="muted small">${POSITION[c.position]}</span></label>`)}</div>
                <p class="muted small" style="margin-top:8px">${st.details.historyVisibility === "FULL" ? "Приглашённые увидят всю историю чата." : "Приглашённые увидят сообщения только с момента вступления."}</p>` : html`<p class="muted">Все ваши коллеги уже в чате.</p>`,
            actions: [{ label: "Отмена" }, ...(candidates.length ? [{
                label: "Пригласить", kind: "primary", onClick: async ({ root: r }) => {
                    const ids = [...r.querySelectorAll("input:checked")].map((i) => Number(i.value));
                    st.details = await post(`${API}/chats/${st.chatId}/members`, { userIds: ids });
                    renderInfo();
                },
            }] : [])],
        });
    }

    function memberMenu(userId, role) {
        const p = st.details.permissions;
        modal({
            title: "Участник",
            body: html`<div class="stack" style="gap:6px">
                ${p.isOwner ? html`<button class="menu-item-row" data-m="role">${role === "ADMIN" ? "Сделать участником" : "Сделать администратором"}</button>
                    <button class="menu-item-row" data-m="transfer">👑 Передать владение</button>` : ""}
                <button class="menu-item-row" data-m="remove">🚪 Исключить из чата</button></div>`,
            onMount: (overlay, close) => overlay.addEventListener("click", async (event) => {
                const action = event.target.closest("[data-m]")?.dataset.m;
                if (!action) return;
                close();
                try {
                    if (action === "role") await put(`${API}/chats/${st.chatId}/members/${userId}/role`, { role: role === "ADMIN" ? "MEMBER" : "ADMIN" });
                    if (action === "transfer" && await confirmDialog("Передать владение?", "Вы станете администратором группы.", "Передать")) await post(`${API}/chats/${st.chatId}/transfer`, { userId });
                    if (action === "remove" && await confirmDialog("Исключить участника?", "Он потеряет доступ к чату, его вложениям и звонкам.", "Исключить", "danger")) await del(`${API}/chats/${st.chatId}/members/${userId}`);
                    st.details = await get(`${API}/chats/${st.chatId}`);
                    renderInfo();
                } catch (error) { reportError(error); }
            }),
        });
    }

    function editChatDialog() {
        const d = st.details;
        modal({
            title: "Чат",
            body: html`<div class="stack">
                <input class="input" data-f="title" value="${d.chat.title}" maxlength="120">
                <textarea class="input" data-f="description" rows="3" maxlength="1000">${d.description || ""}</textarea>
                ${d.permissions.isOwner ? html`<label class="field"><span>История для новых участников</span><select class="input" data-f="history"><option value="FULL" ${d.historyVisibility === "FULL" ? "selected" : ""}>Вся история</option><option value="FROM_JOIN" ${d.historyVisibility === "FROM_JOIN" ? "selected" : ""}>С момента вступления</option></select></label>
                <label class="menu-item-row"><input type="checkbox" data-f="invite" ${d.membersCanInvite ? "checked" : ""}> Участники могут приглашать</label>` : ""}
                <label class="field"><span>Аватар (изображение)</span><input type="file" accept="image/*" data-f="avatar"></label></div>`,
            actions: [{ label: "Отмена" }, {
                label: "Сохранить", kind: "primary", onClick: async ({ root: r }) => {
                    let avatar = null;
                    const file = $("[data-f=avatar]", r).files[0];
                    if (file) {
                        const form = new FormData();
                        form.append("file", file);
                        const response = await fetch(`${API}/chats/${st.chatId}/attachments?avatar=true`, { method: "POST", headers: { Authorization: `Bearer ${accessToken()}` }, body: form });
                        if (!response.ok) throw new Error("Аватар не загружен");
                        avatar = (await response.json()).id;
                    }
                    st.details = await patch(`${API}/chats/${st.chatId}`, {
                        title: $("[data-f=title]", r).value, description: $("[data-f=description]", r).value,
                        historyVisibility: $("[data-f=history]", r)?.value ?? null, membersCanInvite: $("[data-f=invite]", r)?.checked ?? null,
                        avatarAttachmentId: avatar,
                    });
                    renderChat(); renderInfo(); loadChats();
                },
            }],
        });
    }

    async function settingsDialog() {
        const [settings, notify, telegram] = await Promise.all([
            get(`${API}/settings`), get(`${API}/notifications/settings`), get("/api/v1/notifications/telegram").catch(() => null),
        ]);
        const zones = ["Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg", "Asia/Omsk", "Asia/Novosibirsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Yakutsk", "Asia/Vladivostok", "Asia/Magadan", "Asia/Kamchatka"];
        const tg = telegram === null ? html`<p class="muted small">Telegram на этом стенде не настроен (нет бота).</p>`
            : telegram.linked ? html`<p class="ok-text small">✓ Telegram подключён ${new Date(telegram.linkedAt).toLocaleDateString("ru-RU")}</p><button type="button" class="btn sm" data-tg="unlink">Отключить Telegram</button>`
            : html`${telegram.unlinkReason === "BOT_BLOCKED" ? html`<p class="bad-text small">Бот был заблокирован в Telegram — уведомления остановлены. Разблокируйте бота и подключите заново.</p>` : ""}
                <button type="button" class="btn sm primary" data-tg="link">Подключить Telegram</button><div data-tg-link class="small"></div>`;
        const check = (name, label) => html`<label class="menu-item-row"><input type="checkbox" name="${name}" ${notify[name] ? "checked" : ""}> ${label}</label>`;
        modal({
            title: "Настройки мессенджера",
            wide: true,
            body: html`<form class="stack" data-settings>
                <h4>Приватность</h4>
                <label class="field"><span>Кто видит «в сети»</span><select class="input" name="presenceVisibility">
                    <option value="EVERYONE" ${settings.presenceVisibility === "EVERYONE" ? "selected" : ""}>Все, с кем есть общий чат</option>
                    <option value="CONTACTS" ${settings.presenceVisibility === "CONTACTS" ? "selected" : ""}>Только коллеги</option>
                    <option value="NOBODY" ${settings.presenceVisibility === "NOBODY" ? "selected" : ""}>Никто</option></select></label>
                <label class="menu-item-row"><input type="checkbox" name="readReceipts" ${settings.readReceipts ? "checked" : ""}> Отчёты о прочтении (если выключить, вы не увидите и чужих)</label>
                <label class="field"><span>Часовой пояс (для тихих часов)</span><select class="input" name="timezone">${[...new Set([settings.timezone, ...zones])].map((z) => html`<option ${z === settings.timezone ? "selected" : ""}>${z}</option>`)}</select></label>
                <h4>Уведомления в Telegram</h4>
                ${tg}
                ${check("telegramEnabled", "Присылать уведомления мессенджера")}
                <div class="row wrap">${check("directEnabled", "Личные")}${check("groupsEnabled", "Группы")}${check("workEnabled", "Рабочие")}${check("ordersEnabled", "Заказы")}${check("supportEnabled", "Поддержка")}${check("mentionsEnabled", "Упоминания")}${check("callsEnabled", "Звонки")}</div>
                ${check("workShiftOnly", "Рабочие — только во время моей смены")}
                <label class="field"><span>Что показывать в уведомлении</span><select class="input" name="preview">
                    <option value="NONE" ${notify.preview === "NONE" ? "selected" : ""}>Только «новое сообщение» (рекомендуется)</option>
                    <option value="SENDER" ${notify.preview === "SENDER" ? "selected" : ""}>Отправителя и чат</option>
                    <option value="FULL" ${notify.preview === "FULL" ? "selected" : ""}>Текст сообщения</option></select></label>
                <div class="row wrap"><label class="field"><span>Тихие часы с</span><input class="input" type="time" name="quietFrom" value="${notify.quietFrom ? notify.quietFrom.slice(0, 5) : ""}"></label>
                    <label class="field"><span>до</span><input class="input" type="time" name="quietTo" value="${notify.quietTo ? notify.quietTo.slice(0, 5) : ""}"></label>
                    <label class="field"><span>Ждать прочтения, секунд</span><input class="input" type="number" min="0" max="600" name="delaySeconds" value="${notify.delaySeconds}"></label></div>
                <p class="muted small">Если вы прочитаете сообщение на любом устройстве за это время, уведомление не придёт; несколько сообщений одного чата объединяются.</p>
                <h4>Заблокированные</h4><div data-blocks class="small muted">…</div>
            </form>`,
            actions: [{ label: "Закрыть" }, {
                label: "Сохранить", kind: "primary", onClick: async ({ root: r }) => {
                    const f = $("[data-settings]", r);
                    await put(`${API}/settings`, { presenceVisibility: f.elements.presenceVisibility.value, readReceipts: f.elements.readReceipts.checked, timezone: f.elements.timezone.value });
                    const n = {};
                    for (const key of ["telegramEnabled", "directEnabled", "groupsEnabled", "workEnabled", "ordersEnabled", "supportEnabled", "mentionsEnabled", "callsEnabled", "workShiftOnly"]) n[key] = f.elements[key].checked;
                    n.preview = f.elements.preview.value;
                    n.quietFrom = f.elements.quietFrom.value || null;
                    n.quietTo = f.elements.quietTo.value || null;
                    n.delaySeconds = Number(f.elements.delaySeconds.value || 30);
                    await put(`${API}/notifications/settings`, n);
                    toast("Настройки сохранены", "ok");
                },
            }],
            onMount: async (overlay) => {
                overlay.addEventListener("click", async (event) => {
                    const t = event.target.closest("[data-tg]");
                    if (!t) return;
                    try {
                        if (t.dataset.tg === "link") {
                            const offer = await post("/api/v1/notifications/telegram/link");
                            $("[data-tg-link]", overlay).innerHTML = html`Откройте <a href="${offer.deepLink}" target="_blank" rel="noopener">ссылку в Telegram</a> и нажмите «Запустить». Ссылка одноразовая, действует до ${new Date(offer.expiresAt).toLocaleTimeString("ru-RU")}.`.value;
                        } else {
                            await del("/api/v1/notifications/telegram");
                            toast("Telegram отключён");
                            t.replaceWith(Object.assign(document.createElement("span"), { className: "muted small", textContent: "Отключён" }));
                        }
                    } catch (error) { reportError(error); }
                });
                const blocks = await get(`${API}/blocks`).catch(() => []);
                $("[data-blocks]", overlay).innerHTML = blocks.length ? blocks.map((b) => html`<div class="row between">${b.name}<button type="button" class="btn sm" data-unblock="${b.userId}">Разблокировать</button></div>`.value).join("") : "Никого";
                overlay.addEventListener("click", async (event) => {
                    const u = event.target.closest("[data-unblock]");
                    if (u) { await del(`${API}/blocks/${u.dataset.unblock}`); u.parentElement.remove(); }
                });
            },
        });
    }

    async function searchResults(q, chatId = null) {
        if (q.length < 2) return renderList();
        try {
            const hits = await get(`${API}/search?q=${encodeURIComponent(q)}${chatId ? `&chatId=${chatId}` : ""}`);
            listEl.innerHTML = hits.length ? hits.map((h) => html`<button class="ms-row" data-act="hit" data-id="${h.message.chatId}" data-seq="${h.message.seq}">
                <span class="avatar">🔍</span><span class="ms-row-body"><span class="ms-row-top"><b class="ms-ellipsis">${h.chatTitle}</b><span class="muted small">${shortWhen(h.message.createdAt)}</span></span>
                <span class="muted small ms-ellipsis">${h.message.senderName ? h.message.senderName + ": " : ""}${h.message.text}</span></span></button>`.value).join("")
                : empty("🔍", "Ничего не нашлось", "Поиск идёт только по перепискам, где вы участник").value;
        } catch (error) { reportError(error); }
    }

    // ---------- События ----------

    delegate(root, "click", {
        filter: (el) => { const was = st.filter; st.filter = el.dataset.key; if (was === "ARCHIVE" || st.filter === "ARCHIVE") loadChats(); else renderList(); },
        open: (el) => openChat(Number(el.dataset.id)),
        hit: (el) => openChat(Number(el.dataset.id), Number(el.dataset.seq)),
        back: () => { box.classList.remove("chat-open"); st.chatId = null; history.replaceState(null, "", "#/messenger"); renderList(); },
        new: () => newChatDialog(),
        settings: () => settingsDialog(),
        calls: async () => {
            const history = await get(`${API}/calls`);
            modal({
                title: "Звонки", wide: true, actions: [{ label: "Закрыть" }],
                body: history.length ? html`<div class="stack" style="gap:6px">${history.map((h) => html`<div class="row between ms-call-row ${h.myState === "MISSED" ? "missed" : ""}">
                    <span>${h.kind === "VIDEO" ? "🎥" : "📞"} ${h.outgoing ? "↗︎" : "↙︎"} <b>${h.chatTitle}</b><br><span class="muted small">${new Date(h.createdAt).toLocaleString("ru-RU")} · ${h.myState === "MISSED" ? "пропущен" : h.durationSeconds ? Math.round(h.durationSeconds / 60) + " мин" : h.endReason?.toLowerCase() || ""}</span></span>
                    <button class="btn sm" data-recall="${h.chatId}" data-kind="${h.kind}">Перезвонить</button></div>`)}</div>` : html`<p class="muted">Звонков пока не было</p>`,
                onMount: (overlay, close) => overlay.addEventListener("click", (event) => {
                    const b = event.target.closest("[data-recall]");
                    if (b) { close(); startCall(Number(b.dataset.recall), b.dataset.kind).catch(reportError); }
                }),
            });
        },
        info: () => (infoEl.classList.contains("hidden") ? renderInfo() : infoEl.classList.add("hidden")),
        "close-info": () => infoEl.classList.add("hidden"),
        call: (el) => startCall(st.chatId, el.dataset.kind),
        "chat-search": () => {
            const q = prompt("Найти в этом чате:");
            if (q) searchResults(q.trim(), st.chatId);
        },
        jump: (el) => jump(Number(el.dataset.seq)),
        down: () => { const log = $("[data-log]", mainEl); log.scrollTop = log.scrollHeight; scheduleRead(); },
        menu: (el) => messageMenu(Number(el.dataset.id)),
        react: async (el) => react(st.messages.find((m) => m.id === Number(el.dataset.id)), el.dataset.emoji),
        ack: async (el) => { await post(`${API}/messages/${el.dataset.id}/ack`); await catchUp(); },
        acks: async (el) => {
            const report = await get(`${API}/messages/${el.dataset.id}/acks`);
            modal({ title: "Ознакомление", actions: [{ label: "Закрыть" }], body: html`<div class="grid two"><div><b>Ознакомились · ${report.acknowledged.length}</b>${report.acknowledged.map((p) => html`<div>✓ ${p.name}</div>`)}</div><div><b>Ещё нет · ${report.pending.length}</b>${report.pending.map((p) => html`<div class="muted">${p.name}</div>`)}</div></div>` });
        },
        "cancel-reply": () => { st.reply = null; renderChat(); },
        "cancel-edit": () => { st.edit = null; renderChat(); },
        internal: (el) => { st.internal = el.checked; renderChat(); },
        important: (el) => { st.important = el.checked; },
        "cancel-upload": (el) => st.uploads.find((u) => u.key === el.dataset.key)?.xhr.abort(),
        "remove-upload": async (el) => {
            const u = st.uploads.find((x) => x.key === el.dataset.key);
            st.uploads = st.uploads.filter((x) => x !== u);
            renderUploads();
            if (u?.id) del(`${API}/attachments/${u.id}`).catch(() => {});
        },
        "retry-upload": (el) => { const u = st.uploads.find((x) => x.key === el.dataset.key); if (u) { startUpload(u); renderUploads(); } },
        resend: (el) => { const item = st.pending.find((p) => p.key === el.dataset.key); if (item) deliver(item); },
        view: async (el) => {
            const url = await attachmentUrl(el.dataset.att, false);
            modal({ body: html`<img src="${url}" alt="" style="max-height:80vh;margin:auto">`, wide: true, actions: [{ label: "Скачать", onClick: () => { location.href = url + "&download=true"; } }, { label: "Закрыть" }] });
        },
        download: async (el) => { location.href = (await attachmentUrl(el.dataset.att, false)) + "&download=true"; },
        mute: async () => {
            const muted = !!st.details.chat.mutedUntil;
            await put(`${API}/chats/${st.chatId}/preferences`, { mutedUntil: muted ? new Date().toISOString() : new Date(Date.now() + 365 * 86400_000).toISOString() });
            st.details = await get(`${API}/chats/${st.chatId}`); renderInfo(); loadChats();
        },
        archive: async () => {
            await put(`${API}/chats/${st.chatId}/preferences`, { archived: !st.details.chat.archived });
            st.details = await get(`${API}/chats/${st.chatId}`); renderInfo(); loadChats();
        },
        resolve: async () => { await post(`${API}/chats/${st.chatId}/resolve`); toast("Обращение закрыто", "ok"); st.details = await get(`${API}/chats/${st.chatId}`); renderInfo(); catchUp(); },
        invite: () => inviteDialog(),
        "member-menu": (el) => memberMenu(Number(el.dataset.user), el.dataset.role),
        dm: async (el) => { const d = await post(`${API}/chats/direct`, { userId: Number(el.dataset.user) }); await loadChats(); openChat(d.chat.id); },
        "edit-chat": () => editChatDialog(),
        "delete-chat": async () => {
            if (await confirmDialog("Удалить группу?", "Группа исчезнет у всех участников.", "Удалить", "danger")) { await del(`${API}/chats/${st.chatId}`); infoEl.classList.add("hidden"); navigate("/messenger"); }
        },
        leave: async () => {
            if (await confirmDialog("Выйти из чата?", "Вы потеряете доступ к истории и вложениям этого чата.", "Выйти", "danger")) { await post(`${API}/chats/${st.chatId}/leave`); infoEl.classList.add("hidden"); st.chatId = null; navigate("/messenger"); loadChats(); }
        },
        block: async (el) => {
            if (await confirmDialog("Заблокировать?", "Личная переписка и звонки с этим человеком станут недоступны обоим. В рабочих чатах блокировка не действует — там можно пожаловаться.", "Заблокировать", "danger")) {
                await put(`${API}/blocks/${el.dataset.user}`); toast("Пользователь заблокирован"); renderChat();
            }
        },
    });

    delegate(root, "submit", { send: (form) => submit(form) });
    delegate(root, "change", {
        internal: (el) => { st.internal = el.checked; renderChat(); },
        important: (el) => { st.important = el.checked; },
    });
    root.addEventListener("change", (event) => {
        if (event.target.matches("[data-file]")) { addFiles([...event.target.files]); event.target.value = ""; }
    });
    root.addEventListener("keydown", (event) => {
        if (event.target.matches("[data-text]") && event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            event.target.form.requestSubmit();
        }
    });
    let lastTyping = 0;
    root.addEventListener("input", (event) => {
        if (event.target.matches("[data-text]") && st.chatId && Date.now() - lastTyping > 2500 && !st.internal) {
            lastTyping = Date.now();
            wsSend({ type: "typing", chatId: st.chatId });
        }
        if (event.target.matches("[data-text]")) { event.target.style.height = "auto"; event.target.style.height = Math.min(160, event.target.scrollHeight) + "px"; }
    });
    root.addEventListener("dragover", (event) => { if (st.chatId) event.preventDefault(); });
    root.addEventListener("drop", (event) => { if (st.chatId && event.dataTransfer.files.length) { event.preventDefault(); addFiles([...event.dataTransfer.files]); } });
    root.addEventListener("paste", (event) => { if (st.chatId && event.clipboardData?.files.length) addFiles([...event.clipboardData.files]); });
    $("[data-search]", root).addEventListener("input", debounce((event) => searchResults(event.target.value.trim()), 400));

    function showConnection(state) {
        const el = $("[data-conn]", root);
        if (!el) return;
        el.innerHTML = state === "online" ? "" : html`<div class="ms-offline">${state === "connecting" ? "⏳ Подключаемся…" : "📡 Нет соединения — переподключимся автоматически"}</div>`.value;
    }

    const off = onEvent(async (frame) => {
        switch (frame.type) {
            case "connection": showConnection(frame.state); break;
            case "resync": loadChats(); catchUp(); break;
            case "message.new":
                if (frame.chatId === st.chatId) {
                    applyPage({ messages: [frame.message], lastSeq: Math.max(st.lastSeq, frame.message.seq), eventSeq: frame.message.eventSeq, lastReadSeq: st.lastReadSeq });
                    st.pending = st.pending.filter((p) => p.key !== frame.message.clientMessageId);
                    renderMessages();
                    if (frame.message.senderId !== me.user.id) wsSend({ type: "delivered", chatId: frame.chatId, seq: frame.message.seq });
                } else if (frame.message.senderId !== me.user.id) {
                    wsSend({ type: "delivered", chatId: frame.chatId, seq: frame.message.seq });
                }
                loadChats();
                break;
            case "message.updated": if (frame.chatId === st.chatId) catchUp(); break;
            case "read": case "delivered":
                if (frame.chatId === st.chatId && st.details) {
                    const r = st.details.receipts[frame.userId] || { readSeq: 0, deliveredSeq: 0 };
                    if (frame.type === "read") r.readSeq = Math.max(r.readSeq, frame.seq);
                    r.deliveredSeq = Math.max(r.deliveredSeq, frame.seq);
                    st.details.receipts[frame.userId] = r;
                    renderMessages();
                }
                break;
            case "read.self": loadChats(); if (frame.chatId === st.chatId) st.lastReadSeq = Math.max(st.lastReadSeq, frame.seq); break;
            case "typing":
                if (frame.chatId === st.chatId) {
                    st.typing.set(frame.userId, { name: frame.name, until: Date.now() + 4000 });
                    renderTyping();
                    setTimeout(renderTyping, 4100);
                }
                break;
            case "chat.removed":
                if (frame.chatId === st.chatId) revoke(frame.reason === "left" ? "Вы вышли из чата" : "Доступ к чату закрыт");
                else loadChats();
                break;
            case "chat.added": case "chat.updated": case "support.queue": loadChats(); if (frame.chatId === st.chatId) { st.details = await get(`${API}/chats/${st.chatId}`).catch(() => st.details); renderChat(); } break;
            case "chat.members": if (frame.chatId === st.chatId) { st.details = await get(`${API}/chats/${st.chatId}`).catch(() => st.details); if (!infoEl.classList.contains("hidden")) renderInfo(); } break;
            case "chat.pins": if (frame.chatId === st.chatId) { st.pins = await get(`${API}/chats/${st.chatId}/pins`); renderChat(); } break;
            default:
        }
    });

    function renderTyping() {
        const el = $("[data-typing]", mainEl);
        if (!el) return;
        const now = Date.now();
        const names = [...st.typing.values()].filter((t) => t.until > now).map((t) => t.name.split(" ")[0]);
        el.textContent = names.length ? `${names.join(", ")} ${names.length > 1 ? "печатают" : "печатает"}…` : "";
    }

    const presenceTimer = setInterval(loadPresence, 30_000);
    const visibility = () => { if (!document.hidden) scheduleRead(); };
    document.addEventListener("visibilitychange", visibility);
    onLeave(() => { off(); clearInterval(presenceTimer); clearTimeout(readTimer); document.removeEventListener("visibilitychange", visibility); });

    showConnection(connectionState());
    mainEl.innerHTML = empty("💬", "Выберите чат", "Или начните новый — кнопка ✏️ вверху списка").value;
    await loadChats();

    // Переход из карточки заказа: #/messenger?order=42&scope=CUSTOMER_RESTAURANT
    if (query.order) {
        try {
            const available = await get(`${API}/orders/${query.order}/chats`);
            const scope = query.scope && available.some((a) => a.scope === query.scope) ? query.scope : null;
            if (scope) {
                const d = await post(`${API}/orders/${query.order}/chats/${scope}`);
                await loadChats();
                return openChat(d.chat.id);
            }
            modal({
                title: `Переписки по заказу №${query.order}`,
                body: html`<p class="muted small">У каждой переписки свой состав: клиент не видит разговор ресторана с курьером, а курьер — обсуждение замен.</p>
                    <div class="stack" style="gap:6px">${available.map((a) => html`<button class="menu-item-row" data-scope="${a.scope}">${a.title}${a.chatId ? html` <span class="muted small">· открыта</span>` : ""}</button>`)}</div>`,
                onMount: (overlay, close) => overlay.addEventListener("click", async (event) => {
                    const b = event.target.closest("[data-scope]");
                    if (!b) return;
                    try { const d = await post(`${API}/orders/${query.order}/chats/${b.dataset.scope}`); close(); await loadChats(); openChat(d.chat.id); } catch (error) { reportError(error); }
                }),
            });
        } catch (error) { reportError(error); }
    } else if (query.support) {
        newChatDialog("support");
    } else if (query.dm) {
        try { const d = await post(`${API}/chats/direct`, { userId: Number(query.dm) }); await loadChats(); openChat(d.chat.id); } catch (error) { reportError(error); }
    } else if (st.chatId) {
        openChat(st.chatId);
    }
}

function plural(n, forms) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return forms[0];
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
    return forms[2];
}
