// Принятие приглашения в точку: сотрудник открывает ссылку из своего аккаунта — это и есть его согласие.

import { post, restaurantName } from "../api.js";
import { html, empty } from "../ui.js";

const POSITION = { MANAGER: "руководитель", ADMINISTRATOR: "администратор", OPERATOR: "оператор", KITCHEN: "кухня", PICKUP: "выдача" };

export default async function joinPage({ root, query }) {
    if (!query.code) {
        root.innerHTML = empty("🔗", "Нет кода приглашения", "Откройте ссылку, которую прислал руководитель").value;
        return;
    }
    root.innerHTML = html`<div class="container"><div class="card stack center" style="max-width:520px;margin:40px auto">
        <div style="font-size:48px">🏪</div><h2>Приглашение в команду ресторана</h2>
        <p class="muted">Приняв его, вы попадёте в рабочие чаты точки по своей должности. Руководитель не увидит ваших личных переписок.</p>
        <button class="btn primary" data-accept>Принять приглашение</button><div data-result></div></div></div>`.value;
    root.querySelector("[data-accept]").addEventListener("click", async (event) => {
        event.target.disabled = true;
        try {
            const assignment = await post("/api/v1/messenger/staff/invites/accept", { code: query.code });
            const name = await restaurantName(assignment.restaurantId);
            root.querySelector("[data-result]").innerHTML = html`<p class="ok-text">Готово: «${name}», ${POSITION[assignment.position] || assignment.position}.</p><a class="btn" href="#/messenger">Открыть рабочие чаты</a>`.value;
        } catch (error) {
            event.target.disabled = false;
            root.querySelector("[data-result]").innerHTML = html`<p class="bad-text">${error.message}</p>`.value;
        }
    });
}
