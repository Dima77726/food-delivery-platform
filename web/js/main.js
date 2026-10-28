// Каркас приложения: маршруты, верхняя панель, меню аккаунтов с переключением ролей.

import { session, accounts, switchTo, setRole, subscribe, cartRestaurants, applyTheme, cycleTheme, theme } from "./store.js";
import { get, login, logout } from "./api.js";
import { html, initials, ROLE_LABELS, ROLE_ICONS, toast, reportError, $, delegate } from "./ui.js";
import { mountAssistant } from "./assistant.js";
import { navigate, homeFor } from "./nav.js";
import { ensureNotificationFeed, unreadCount, onUnreadChange, markAllSeen } from "./notifications.js";

const CONFIG = window.APP_CONFIG || {};

/** [шаблон пути, модуль страницы, допустимые роли (пусто - любая, включая гостя), нужен ли вход] */
const ROUTES = [
    ["/", "home", [], false],
    ["/search", "home", [], false],
    ["/r/:id", "restaurant", [], false],
    ["/login", "auth", [], false],
    ["/register", "auth", [], false],
    ["/cart", "cart", ["CUSTOMER"], true],
    ["/orders", "orders", ["CUSTOMER"], true],
    ["/orders/:id", "order", ["CUSTOMER"], true],
    ["/profile", "profile", [], true],
    ["/owner", "owner", ["RESTAURANT_OWNER", "ADMIN"], true],
    ["/owner/:id", "manage", ["RESTAURANT_OWNER", "ADMIN"], true],
    ["/courier", "courier", ["COURIER"], true],
    ["/admin", "admin", ["ADMIN"], true],
    ["/admin/users", "admin-users", ["ADMIN"], true],
    ["/admin/audit", "admin-audit", ["ADMIN"], true],
    ["/tools", "tools", [], false],
    ["/admin/business", "admin-business", ["ADMIN"], true],
];


const NAV_BY_ROLE = {
    CUSTOMER: [["/", "Рестораны"], ["/orders", "Мои заказы"], ["/profile", "Уведомления"]],
    RESTAURANT_OWNER: [["/owner", "Мои рестораны"], ["/", "Каталог"], ["/profile", "Профиль"]],
    COURIER: [["/courier", "Доставки"], ["/profile", "Профиль"]],
    ADMIN: [["/admin", "Обзор"], ["/admin/users", "Пользователи"], ["/admin/business", "Промо и возвраты"], ["/admin/audit", "Журнал"], ["/", "Каталог"], ["/tools", "Инструменты"]],
    GUEST: [["/", "Рестораны"], ["/tools", "Инструменты"]],
};

let cleanups = [];
let renderToken = 0;
let cartCount = 0;

function parseHash() {
    const raw = location.hash.replace(/^#/, "") || "/";
    const [path, queryString = ""] = raw.split("?");
    return { path: path || "/", query: Object.fromEntries(new URLSearchParams(queryString)) };
}

function match(path) {
    for (const [pattern, page, roles, needAuth] of ROUTES) {
        const names = [];
        const re = new RegExp("^" + pattern.replace(/:[^/]+/g, (m) => { names.push(m.slice(1)); return "([^/]+)"; }) + "/?$");
        const found = re.exec(path);
        if (found) return { page, roles, needAuth, params: Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(found[i + 1])])) };
    }
    return null;
}

async function route() {
    const token = ++renderToken;
    cleanups.forEach((fn) => { try { fn(); } catch { /* уходим со страницы */ } });
    cleanups = [];

    const { path, query } = parseHash();
    const found = match(path);
    const me = session();

    if (!found) return renderShell(html`<div class="container"><div class="empty"><div class="big">🧭</div><h2>Такой страницы нет</h2><p><a class="btn primary" href="#/">На главную</a></p></div></div>`);

    if (found.needAuth && !me) return navigate(`/login?next=${encodeURIComponent(path)}`);
    if (found.roles.length && me && !found.roles.includes(me.role)) return navigate(homeFor(me.role));

    renderShell();
    const root = $("#view");
    root.innerHTML = "";
    try {
        const module = await import(`./pages/${found.page}.js`);
        if (token !== renderToken) return;
        await module.default({
            root, params: found.params, query, path, me,
            navigate, onLeave: (fn) => cleanups.push(fn), refreshChrome,
        });
        window.scrollTo(0, 0);
    } catch (error) {
        if (token !== renderToken) return;
        console.error(error);
        root.innerHTML = html`<div class="container"><div class="empty"><div class="big">😵</div><h2>Не получилось открыть страницу</h2><p>${error.message}</p></div></div>`.value;
    }
}

// ---------- Верхняя панель ----------

function renderShell(content) {
    const app = document.getElementById("app");
    const me = session();
    const role = me?.role || "GUEST";
    const { path } = parseHash();
    const links = (NAV_BY_ROLE[role] || NAV_BY_ROLE.GUEST)
        .map(([to, label]) => html`<a href="#${to}" class="${path === to || (to !== "/" && path.startsWith(to + "/")) ? "active" : ""}">${label}</a>`);

    app.innerHTML = html`
        <header class="nav">
            <a href="#${homeFor(role)}" class="logo"><span>🍜</span><b>Вкусно</b></a>
            <nav class="nav-links">${links}</nav>
            <div class="nav-spacer"></div>
            <button class="icon-btn" data-act="theme" title="Тема оформления">${{ auto: "🌗", dark: "🌙", light: "☀️" }[theme()]}</button>
            ${me ? html`<a class="icon-btn" href="#/profile" title="Уведомления" data-bell>🔔${unreadCount() ? html`<span class="dot">${unreadCount()}</span>` : ""}</a>` : ""}
            ${role === "CUSTOMER" ? html`<a class="icon-btn" href="#/cart" title="Корзина">🛒${cartCount ? html`<span class="dot">${cartCount}</span>` : ""}</a>` : ""}
            ${me ? accountButton(me) : html`<a class="btn primary" href="#/login">Войти</a>`}
        </header>
        <main id="view">${content || ""}</main>`.value;

    if (!content) mountAssistantIfNeeded();
    ensureNotificationFeed(me?.email ?? null);
    if (path === "/profile") markAllSeen();
}

// Счётчик на колокольчике обновляется точечно, без перерисовки всей панели.
onUnreadChange((count) => {
    const bell = document.querySelector("[data-bell]");
    if (bell) bell.innerHTML = count ? `🔔<span class="dot">${count}</span>` : "🔔";
});

function accountButton(me) {
    return html`<div class="rel" id="account-root">
        <button class="account" data-act="account-menu">
            <span class="avatar">${initials(me.user.fullName)}</span>
            <span class="small"><b>${me.user.fullName.split(" ")[0]}</b><br><span class="muted">${ROLE_ICONS[me.role]} ${ROLE_LABELS[me.role]}</span></span>
        </button>
    </div>`;
}

function accountMenu() {
    const me = session();
    const all = accounts();
    const known = new Set(all.map((a) => a.email));
    const demo = (CONFIG.demoAccounts ? CONFIG.demo || [] : []).filter((d) => !known.has(d.email));
    return html`<div class="menu-pop" id="account-pop">
        <div class="head">Аккаунты на этом компьютере</div>
        ${all.map((a) => html`<button class="menu-item-row ${a.current ? "current" : ""}" data-act="switch-account" data-email="${a.email}">
            <span class="avatar">${initials(a.user.fullName)}</span>
            <span style="flex:1"><b>${a.user.fullName}</b><br><span class="muted small">${ROLE_ICONS[a.role]} ${ROLE_LABELS[a.role]} · ${a.email}</span></span>
            ${a.current ? "✓" : ""}
        </button>`)}
        ${me.user.roles.length > 1 ? html`<hr><div class="head">Роль</div>${me.user.roles.map((r) => html`<button class="menu-item-row ${r === me.role ? "current" : ""}" data-act="switch-role" data-role="${r}">${ROLE_ICONS[r]} ${ROLE_LABELS[r]} ${r === me.role ? "✓" : ""}</button>`)}` : ""}
        ${demo.length ? html`<hr><div class="head">Демо-аккаунты</div>${demo.map((d) => html`<button class="menu-item-row" data-act="demo-login" data-email="${d.email}"><span class="avatar">${d.icon}</span><span><b>${d.title}</b> <span class="muted small">${d.email}</span></span></button>`)}` : ""}
        <hr>
        <a class="menu-item-row" href="#/tools">🧰 Инструменты: Grafana, Kafka, БД…</a>
        <button class="menu-item-row" data-act="add-account">➕ Войти в другой аккаунт</button>
        <button class="menu-item-row" data-act="sign-out">🚪 Выйти из «${me.user.fullName}»</button>
    </div>`;
}

async function refreshCartCount() {
    const me = session();
    if (!me || me.role !== "CUSTOMER") { cartCount = 0; return; }
    const ids = cartRestaurants(me.user.id);
    try {
        const carts = await Promise.all(ids.map((id) => get(`/api/v1/customers/${me.user.id}/restaurants/${id}/cart`).catch(() => null)));
        cartCount = carts.reduce((sum, cart) => sum + (cart?.items || []).reduce((s, i) => s + i.quantity, 0), 0);
    } catch {
        cartCount = 0;
    }
}

/** Перерисовывает верхнюю панель (после действий, меняющих корзину или аккаунт). */
export async function refreshChrome() {
    await refreshCartCount();
    const header = $(".nav");
    if (!header) return;
    const me = session();
    const dot = header.querySelector('a[href="#/cart"]');
    if (dot) dot.innerHTML = `🛒${cartCount ? `<span class="dot">${cartCount}</span>` : ""}`;
    if (!me) renderShell();
}

// ---------- Ассистент ----------

let assistantHandle = null;
function mountAssistantIfNeeded() {
    const me = session();
    // Помощник есть у всех ролей: набор его инструментов агент выбирает по роли в токене.
    const wanted = CONFIG.assistant && me && me.role;
    if (!wanted) {
        assistantHandle?.destroy();
        assistantHandle = null;
        return;
    }
    if (!assistantHandle || assistantHandle.email !== me.email || assistantHandle.role !== me.role) {
        assistantHandle?.destroy();
        assistantHandle = mountAssistant(me.email, me.role, me.user.id);
    }
}

// ---------- События панели ----------

delegate(document.body, "click", {
    theme: () => { cycleTheme(); renderShell(); route(); },
    "account-menu": (el) => {
        const root = $("#account-root");
        const open = $("#account-pop");
        if (open) return open.remove();
        root.insertAdjacentHTML("beforeend", accountMenu().value);
    },
    "switch-account": (el) => { switchTo(el.dataset.email); toast("Аккаунт переключён", "ok"); navigate(homeFor(session().role)); route(); },
    "switch-role": (el) => { setRole(el.dataset.role); navigate(homeFor(el.dataset.role)); route(); },
    "demo-login": async (el) => {
        const demo = (CONFIG.demo || []).find((d) => d.email === el.dataset.email);
        const role = await login(demo.email, CONFIG.demoPassword);
        toast(`Вы вошли как ${demo.title.toLowerCase()}`, "ok");
        navigate(homeFor(role));
        route();
    },
    "add-account": () => navigate("/login?add=1"),
    "sign-out": async () => {
        const me = session();
        await logout(me.email);
        toast("Вы вышли из аккаунта");
        const next = session();
        navigate(next ? homeFor(next.role) : "/");
        route();
    },
});

document.addEventListener("click", (event) => {
    const pop = $("#account-pop");
    if (pop && !event.target.closest("#account-root")) pop.remove();
});

window.addEventListener("hashchange", route);
subscribe(() => { /* токены обновляются в других вкладках - панель перерисуется при следующем переходе */ });

applyTheme();
await refreshCartCount();
route();
