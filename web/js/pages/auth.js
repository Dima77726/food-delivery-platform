// Вход и регистрация. Демо-аккаунты позволяют зайти под любой ролью одним нажатием.

import { login, register } from "../api.js";
import { session } from "../store.js";
import { homeFor, navigate } from "../nav.js";
import { html, delegate, formValues, toast, ROLE_LABELS, ROLE_ICONS } from "../ui.js";

const CONFIG = window.APP_CONFIG || {};

export default async function authPage({ root, path, query }) {
    const isRegister = path === "/register";
    const adding = Boolean(query.add) && session();

    const draw = (error = "") => {
        root.innerHTML = html`<div class="auth"><div class="card auth-card stack lg">
            <div class="center">
                <div style="font-size:48px">🍜</div>
                <h2>${isRegister ? "Создать аккаунт" : adding ? "Добавить аккаунт" : "С возвращением"}</h2>
                <p class="muted">${isRegister ? "Выберите, как вы будете пользоваться «Вкусно»" : "Войдите, чтобы заказывать, готовить или доставлять"}</p>
            </div>
            ${!isRegister && CONFIG.demoAccounts && CONFIG.demo?.length ? html`
                <div class="stack">
                    <div class="divider">демо-вход одним нажатием</div>
                    <div class="demo-grid" style="grid-template-columns:repeat(${CONFIG.demo.length}, 1fr)">${CONFIG.demo.map((d) => html`<button class="demo-btn" data-act="demo" data-email="${d.email}"><span class="ico">${d.icon}</span>${d.title}<span class="muted small">${d.name}</span></button>`)}</div>
                    <div class="divider">или свой аккаунт</div>
                </div>` : ""}
            <form class="stack" data-act="submit">
                ${isRegister ? html`
                    <div class="role-pick">${["CUSTOMER", "RESTAURANT_OWNER", "COURIER"].map((r, i) => html`<label><input type="radio" name="role" value="${r}" ${i === 0 ? "checked" : ""}><span style="font-size:26px">${ROLE_ICONS[r]}</span>${ROLE_LABELS[r]}</label>`)}</div>
                    <div class="field"><label>Имя и фамилия</label><input class="input" name="fullName" required minlength="2" placeholder="Анна Иванова"></div>
                    <div class="field"><label>Телефон</label><input class="input" name="phone" placeholder="+7 999 000-00-00"></div>` : ""}
                <div class="field"><label>Эл. почта</label><input class="input" name="email" type="email" required autocomplete="username" placeholder="you@example.com"></div>
                <div class="field"><label>Пароль</label><input class="input" name="password" type="password" required minlength="${isRegister ? 8 : 1}" autocomplete="${isRegister ? "new-password" : "current-password"}"></div>
                ${error ? html`<div class="err">${error}</div>` : ""}
                <button class="btn primary block" id="go">${isRegister ? "Зарегистрироваться" : "Войти"}</button>
            </form>
            <p class="center muted small">${isRegister ? html`Уже есть аккаунт? <a href="#/login" style="color:var(--primary);font-weight:700">Войти</a>` : html`Нет аккаунта? <a href="#/register" style="color:var(--primary);font-weight:700">Зарегистрироваться</a>`}</p>
        </div></div>`.value;
    };

    const done = (role) => {
        toast("Добро пожаловать!", "ok");
        const next = query.next && query.next.startsWith("/") ? query.next : homeFor(role);
        navigate(next);
    };

    delegate(root, "click", {
        demo: async (el) => {
            const demo = CONFIG.demo.find((d) => d.email === el.dataset.email);
            el.disabled = true;
            try {
                done(await login(demo.email, CONFIG.demoPassword));
            } catch (error) {
                el.disabled = false;
                draw(error.status === 401 || error.status === 404
                    ? "Демо-аккаунты ещё не созданы. Выполните: python scripts/seed-demo.py"
                    : error.message);
            }
        },
    });

    delegate(root, "submit", {
        submit: async (form) => {
            const values = formValues(form);
            const button = root.querySelector("#go");
            button.disabled = true;
            try {
                done(isRegister
                    ? await register({ email: values.email, password: values.password, fullName: values.fullName, phone: values.phone || null, role: values.role })
                    : await login(values.email, values.password));
            } catch (error) {
                draw(error.status === 401 ? "Неверная почта или пароль" : error.message);
            }
        },
    });

    draw();
}
