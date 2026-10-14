// Мелкие помощники интерфейса: безопасные шаблоны, форматирование, окна и уведомления.

const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
export const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ESC[c]);

/** Доверенный фрагмент HTML: не экранируется повторно. */
export class Safe {
    constructor(value) { this.value = value; }
    toString() { return this.value; }
}
export const raw = (value) => new Safe(String(value));

function render(value) {
    if (value === null || value === undefined || value === false || value === true) return "";
    if (value instanceof Safe) return value.value;
    if (Array.isArray(value)) return value.map(render).join("");
    return esc(value);
}

/**
 * Шаблон, экранирующий всё подставляемое. Данные пользователей (названия, отзывы, имена) могут содержать
 * разметку, поэтому в HTML они попадают только через этот тег; вложенные html`` склеиваются без повторного экранирования.
 */
export function html(strings, ...values) {
    let out = strings[0];
    values.forEach((value, i) => { out += render(value) + strings[i + 1]; });
    return new Safe(out);
}

export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

/** Делегирование событий: у элемента data-act="имя", обработчик получает элемент и событие. */
export function delegate(root, type, handlers) {
    root.addEventListener(type, async (event) => {
        const el = event.target.closest("[data-act]");
        if (!el || !root.contains(el)) return;
        const handler = handlers[el.dataset.act];
        if (!handler) return;
        if (type === "submit") event.preventDefault();
        try {
            await handler(el, event);
        } catch (error) {
            reportError(error);
        }
    });
}

export function reportError(error) {
    console.error(error);
    toast(error?.message || "Что-то пошло не так", "bad");
}

// ---------- Форматирование ----------

const rub = new Intl.NumberFormat("ru-RU", { style: "currency", currency: "RUB", minimumFractionDigits: 0, maximumFractionDigits: 2 });
export const money = (value) => rub.format(Number(value || 0));

export function dateTime(value) {
    if (!value) return "—";
    return new Date(value).toLocaleString("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function timeOnly(value) {
    return value ? new Date(value).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "—";
}

export function ago(value) {
    if (!value) return "";
    const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000));
    if (seconds < 60) return "только что";
    if (seconds < 3600) return `${Math.floor(seconds / 60)} мин назад`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)} ч назад`;
    return dateTime(value);
}

export const initials = (name) => (name || "?").trim().split(/\s+/).slice(0, 2).map((p) => p[0]?.toUpperCase() || "").join("") || "?";

export const ROLE_LABELS = {
    CUSTOMER: "Клиент", RESTAURANT_OWNER: "Ресторатор", COURIER: "Курьер", ADMIN: "Администратор",
};
export const ROLE_ICONS = { CUSTOMER: "🛍️", RESTAURANT_OWNER: "👨‍🍳", COURIER: "🛵", ADMIN: "🛡️" };

export const ORDER_STATUS = {
    CREATED: ["Создан", "blue"],
    PAYMENT_PENDING: ["Ожидает оплаты", "yellow"],
    PAID: ["Оплачен", "orange"],
    ACCEPTED: ["Принят рестораном", "orange"],
    COOKING: ["Готовится", "orange"],
    READY_FOR_DELIVERY: ["Ждёт курьера", "orange"],
    IN_DELIVERY: ["В пути", "blue"],
    DELIVERED: ["Доставлен", "green"],
    CANCELED: ["Отменён", "red"],
};
export const DELIVERY_STATUS = {
    CREATED: ["Ищет курьера", "yellow"], ASSIGNED: ["Назначена", "orange"], PICKED_UP: ["В пути", "blue"], DELIVERED: ["Доставлена", "green"],
};

export function statusBadge(status, map = ORDER_STATUS) {
    const [label, color] = map[status] || [status, ""];
    return html`<span class="badge ${color}">${label}</span>`;
}

/** Картинка-заглушка по смыслу названия: у блюд и ресторанов фото может не быть. */
export function emojiFor(...texts) {
    const rules = [
        [/пицц|пепперони|маргарит|итал|траттор|милан/, "🍕"], [/паст|карбонар|болонь|спагет/, "🍝"], [/бургер|чизбург/, "🍔"],
        [/суш|ролл|филадельф|калифорн|дракон|японск/, "🍣"], [/пельмен|вареник/, "🥟"], [/том ям|мисо|суп|бульон|борщ/, "🍜"],
        [/тирамису|десерт|панна|торт|пирожн/, "🍰"], [/лимонад|напит|чай|кофе|сок/, "🥤"], [/фри|картоф/, "🍟"],
        [/салат/, "🥗"], [/бекон/, "🥓"], [/кур|чикен|крыл/, "🍗"], [/стейк|мяс|говяд/, "🥩"], [/хлеб|выпеч/, "🥐"], [/кольц|закуск/, "🧅"],
    ];
    // Сначала смысл названия: в описании «Классик» упомянут салат, но это бургер.
    for (const source of texts) {
        const t = (source || "").toLowerCase();
        for (const [re, emoji] of rules) if (re.test(t)) return emoji;
    }
    return "🍽️";
}

const PALETTE = [["#ffe3d3", "#ffb98f"], ["#ffeccc", "#ffd27a"], ["#e0f3e8", "#9fdcb9"], ["#e3e9ff", "#b3c3ff"], ["#f6e1f3", "#e4a6dc"], ["#dff1f5", "#9bd3e0"]];
export function coverStyle(seed) {
    let hash = 0;
    for (const ch of String(seed)) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
    const [a, b] = PALETTE[hash % PALETTE.length];
    return `background: linear-gradient(135deg, ${a}, ${b});`;
}

export const debounce = (fn, ms = 300) => {
    let timer;
    return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), ms); };
};

/** Склонение по числу: plural(5, ["отзыв", "отзыва", "отзывов"]) -> "отзывов". */
export function plural(n, forms) {
    const m10 = Math.abs(n) % 10;
    const m100 = Math.abs(n) % 100;
    if (m10 === 1 && m100 !== 11) return forms[0];
    if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return forms[1];
    return forms[2];
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------- Уведомления и окна ----------

export function toast(message, kind = "") {
    const box = document.getElementById("toasts");
    const el = document.createElement("div");
    el.className = `toast ${kind}`;
    el.textContent = message;
    box.append(el);
    setTimeout(() => el.remove(), kind === "bad" ? 6000 : 3500);
}

/** Модальное окно. Возвращает { close, root }. body - Safe, onMount получает корень окна. */
export function modal({ title, body, actions = [], onMount, wide = false }) {
    const host = document.getElementById("modal-root");
    const overlay = document.createElement("div");
    overlay.className = "overlay";
    const buttons = actions.map((a, i) => html`<button type="button" class="btn ${a.kind || ""}" data-modal-act="${i}">${a.label}</button>`);
    overlay.innerHTML = html`<div class="modal" role="dialog" aria-modal="true" style="${wide ? "max-width:760px" : ""}">
        ${title ? html`<h3>${title}</h3>` : ""}<div data-modal-body>${body}</div>
        ${actions.length ? html`<div class="actions">${buttons}</div>` : ""}</div>`.value;
    host.append(overlay);

    const close = () => overlay.remove();
    overlay.addEventListener("mousedown", (event) => { if (event.target === overlay) close(); });
    overlay.addEventListener("click", async (event) => {
        const btn = event.target.closest("[data-modal-act]");
        if (!btn) return;
        const action = actions[Number(btn.dataset.modalAct)];
        try {
            const result = await action.onClick?.({ close, root: overlay, button: btn });
            if (result !== false && action.close !== false) close();
        } catch (error) {
            reportError(error);
        }
    });
    onMount?.(overlay, close);
    return { close, root: overlay };
}

export function confirmDialog(title, text, okLabel = "Да", kind = "primary") {
    return new Promise((resolve) => {
        modal({
            title,
            body: html`<p>${text}</p>`,
            actions: [
                { label: "Отмена", onClick: () => resolve(false) },
                { label: okLabel, kind, onClick: () => resolve(true) },
            ],
        });
    });
}

export function formValues(form) {
    const data = {};
    new FormData(form).forEach((value, key) => { data[key] = typeof value === "string" ? value.trim() : value; });
    return data;
}

export function skeleton(count = 6) {
    return html`<div class="grid">${Array.from({ length: count }, () => html`<div class="skeleton"></div>`)}</div>`;
}

export function empty(icon, title, text = "") {
    return html`<div class="empty"><div class="big">${icon}</div><h3>${title}</h3><p>${text}</p></div>`;
}
