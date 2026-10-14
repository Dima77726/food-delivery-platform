// Сессии аккаунтов, тема и список корзин. Всё хранится в браузере этого компьютера.

const KEY = "fd.sessions.v1";
const CART_KEY = "fd.carts.v1";
const THEME_KEY = "fd.theme";

const listeners = new Set();
export const subscribe = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };
const notify = () => listeners.forEach((fn) => fn());

function read(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
function write(key, value) {
    localStorage.setItem(key, JSON.stringify(value));
}

/** Состояние: { active: email | null, accounts: { email: { access, refresh, user, role } } } */
export function state() {
    return read(KEY, { active: null, accounts: {} });
}
function save(next) {
    write(KEY, next);
    notify();
}

export function session() {
    const s = state();
    const account = s.active && s.accounts[s.active];
    return account ? { email: s.active, ...account } : null;
}

export function accounts() {
    const s = state();
    return Object.entries(s.accounts).map(([email, a]) => ({ email, ...a, current: email === s.active }));
}

const ROLE_ORDER = ["ADMIN", "RESTAURANT_OWNER", "COURIER", "CUSTOMER"];

/** Сохраняет ответ входа или регистрации и делает аккаунт активным. */
export function signIn(response) {
    const s = state();
    const email = response.user.email;
    const previous = s.accounts[email];
    const roles = response.user.roles || [];
    const role = previous && roles.includes(previous.role) ? previous.role : ROLE_ORDER.find((r) => roles.includes(r)) || roles[0] || "CUSTOMER";
    s.accounts[email] = { access: response.accessToken, refresh: response.refreshToken, user: response.user, role };
    s.active = email;
    save(s);
    return role;
}

export function updateTokens(email, tokens) {
    const s = state();
    if (!s.accounts[email]) return;
    s.accounts[email].access = tokens.accessToken;
    s.accounts[email].refresh = tokens.refreshToken;
    if (tokens.user) s.accounts[email].user = tokens.user;
    write(KEY, s);
}

export function switchTo(email) {
    const s = state();
    if (!s.accounts[email]) return false;
    s.active = email;
    save(s);
    return true;
}

export function setRole(role) {
    const s = state();
    if (s.active && s.accounts[s.active]) {
        s.accounts[s.active].role = role;
        save(s);
    }
}

/** Убирает аккаунт; активным становится любой оставшийся. */
export function forget(email) {
    const s = state();
    delete s.accounts[email];
    if (s.active === email) s.active = Object.keys(s.accounts)[0] || null;
    save(s);
}

export function signOutAll() {
    save({ active: null, accounts: {} });
}

// ---------- Корзины ----------
// У платформы нет списка корзин клиента, поэтому браузер помнит, в каких ресторанах клиент добавлял блюда.

export function cartRestaurants(userId) {
    return read(CART_KEY, {})[userId] || [];
}
export function trackCart(userId, restaurantId) {
    const all = read(CART_KEY, {});
    const list = new Set(all[userId] || []);
    list.add(Number(restaurantId));
    all[userId] = [...list];
    write(CART_KEY, all);
    notify();
}
export function untrackCart(userId, restaurantId) {
    const all = read(CART_KEY, {});
    all[userId] = (all[userId] || []).filter((id) => id !== Number(restaurantId));
    write(CART_KEY, all);
    notify();
}

// ---------- Тема ----------

export function theme() {
    return localStorage.getItem(THEME_KEY) || "auto";
}
export function applyTheme(value = theme()) {
    if (value === "auto") document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", value);
}
export function cycleTheme() {
    const next = { auto: "dark", dark: "light", light: "auto" }[theme()];
    localStorage.setItem(THEME_KEY, next);
    applyTheme(next);
    return next;
}
