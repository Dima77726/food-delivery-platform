// Администратор: промокоды, заявки на возврат и текущая цена доставки.

import { get, post, del } from "../api.js";
import { html, delegate, modal, formValues, confirmDialog, money, dateTime, ago, empty, skeleton, toast, reportError } from "../ui.js";

export default async function adminBusinessPage({ root, onLeave }) {
    let promos = [];
    let refunds = [];
    let fee = null;
    root.innerHTML = html`<div class="container"><div class="page-head"><h1>Промо и возвраты</h1></div>${skeleton(3)}</div>`.value;

    async function load() {
        [promos, refunds, fee] = await Promise.all([
            get("/api/v1/admin/promo-codes").catch(() => []),
            get("/api/v1/admin/refund-requests").catch(() => []),
            get("/api/v1/delivery-fee").catch(() => null),
        ]);
    }

    const promoValue = (p) => p.type === "PERCENT" ? `${Number(p.value)}%${p.maxDiscount ? ` (до ${money(p.maxDiscount)})` : ""}` : money(p.value);

    function draw() {
        root.innerHTML = html`<div class="container"><div class="page-head"><h1>Промо и возвраты</h1></div>
            <div class="kpis">
                <div class="kpi"><div class="v">${fee ? money(fee.fee) : "—"}</div><div class="l">доставка сейчас${fee && Number(fee.multiplier) > 1 ? ` (×${fee.multiplier})` : ""}</div></div>
                <div class="kpi"><div class="v">${fee ? `${fee.waitingDeliveries} / ${fee.onlineCouriers}` : "—"}</div><div class="l">ждут курьера / курьеров на линии</div></div>
                <div class="kpi"><div class="v">${promos.filter((p) => p.active).length}</div><div class="l">активных промокодов</div></div>
                <div class="kpi"><div class="v">${refunds.length}</div><div class="l">возвратов ждут решения</div></div></div>
            <div class="grid two">
                <section class="card stack"><h3>Заявки на возврат</h3>
                    ${refunds.length ? refunds.map((r) => html`<div class="stack" style="border-bottom:1px dashed var(--border);padding-bottom:10px">
                        <div class="row between"><b>Заказ №${r.orderId} · ${money(r.amount)}</b><span class="muted small">${ago(r.createdAt)}</span></div>
                        <div class="small">${r.items.map((i) => `${i.name} × ${i.quantity}`).join(", ")}</div>
                        <div class="muted small">«${r.reason}» · клиент №${r.customerId}</div>
                        <div class="row"><button class="btn sm green" data-act="approve" data-id="${r.id}">Вернуть ${money(r.amount)}</button>
                            <button class="btn sm ghost danger" data-act="reject" data-id="${r.id}">Отклонить</button></div></div>`)
                        : empty("✅", "Новых заявок нет", "Небольшие суммы одобряются автоматически, сюда попадают остальные.")}</section>
                <section class="card stack"><div class="row between"><h3>Промокоды</h3><button class="btn sm primary" data-act="create">＋ Промокод</button></div>
                    ${promos.length ? promos.map((p) => html`<div class="row between" style="border-bottom:1px dashed var(--border);padding:6px 0">
                        <div><b>${p.code}</b> <span class="badge ${p.active ? "green" : ""}">${p.active ? "активен" : "выключен"}</span>
                            <div class="muted small">${promoValue(p)}${Number(p.minOrderAmount) > 0 ? ` · от ${money(p.minOrderAmount)}` : ""}${p.restaurantId ? ` · ресторан №${p.restaurantId}` : ""} · использован ${p.usedCount} из ${p.maxUses}${p.validUntil ? ` · до ${dateTime(p.validUntil)}` : ""}</div></div>
                        ${p.active ? html`<button class="btn sm ghost danger" data-act="deactivate" data-code="${p.code}">Выключить</button>` : ""}</div>`)
                        : empty("🏷️", "Промокодов нет")}</section></div></div>`.value;
    }

    async function act(fn, message) {
        try { await fn(); if (message) toast(message, "ok"); } catch (error) { reportError(error); }
        await load();
        draw();
    }

    function createDialog() {
        modal({
            title: "Новый промокод",
            body: html`<form class="stack" id="promo-form">
                <div class="field"><label>Код</label><input class="input" name="code" required pattern="[A-Za-z0-9_\\-]{3,40}" placeholder="AUTUMN15" style="text-transform:uppercase"></div>
                <div class="grid two"><div class="field"><label>Тип</label><select class="input" name="type"><option value="PERCENT">Процент</option><option value="FIXED">Сумма, ₽</option></select></div>
                    <div class="field"><label>Размер</label><input class="input" name="value" type="number" min="0.01" step="0.01" required value="10"></div></div>
                <div class="grid two"><div class="field"><label>Не больше, ₽ (для процента)</label><input class="input" name="maxDiscount" type="number" min="0.01" step="0.01"></div>
                    <div class="field"><label>Заказ от, ₽</label><input class="input" name="minOrderAmount" type="number" min="0" step="0.01" value="0"></div></div>
                <div class="grid two"><div class="field"><label>Всего использований</label><input class="input" name="maxUses" type="number" min="1" required value="100"></div>
                    <div class="field"><label>Действует до</label><input class="input" name="validUntil" type="date"></div></div>
                <p class="muted small">Каждый клиент может использовать код один раз. Последнее использование не продастся дважды: счётчик меняется атомарно.</p></form>`,
            actions: [
                { label: "Отмена" },
                {
                    label: "Создать", kind: "primary", close: false,
                    onClick: async ({ close, root: dialog }) => {
                        const form = dialog.querySelector("#promo-form");
                        if (!form.reportValidity()) return false;
                        const v = formValues(form);
                        try {
                            await post("/api/v1/admin/promo-codes", {
                                code: v.code.trim().toUpperCase(),
                                type: v.type,
                                value: Number(v.value),
                                maxDiscount: v.maxDiscount ? Number(v.maxDiscount) : null,
                                minOrderAmount: Number(v.minOrderAmount || 0),
                                maxUses: Number(v.maxUses),
                                validUntil: v.validUntil ? new Date(`${v.validUntil}T23:59:59`).toISOString() : null,
                            });
                            close();
                            toast("Промокод создан", "ok");
                            await load();
                            draw();
                        } catch (error) { reportError(error); }
                        return false;
                    },
                },
            ],
        });
    }

    delegate(root, "click", {
        create: createDialog,
        approve: (el) => act(() => post(`/api/v1/admin/refund-requests/${el.dataset.id}/approve`), "Возврат одобрен, деньги уходят клиенту"),
        reject: async (el) => {
            if (!(await confirmDialog("Отклонить заявку?", "Клиент увидит, что возврат отклонён.", "Отклонить", "danger"))) return;
            await act(() => post(`/api/v1/admin/refund-requests/${el.dataset.id}/reject`), "Заявка отклонена");
        },
        deactivate: async (el) => {
            if (!(await confirmDialog(`Выключить ${el.dataset.code}?`, "Код перестанет приниматься при оформлении.", "Выключить", "danger"))) return;
            await act(() => del(`/api/v1/admin/promo-codes/${encodeURIComponent(el.dataset.code)}`), "Промокод выключен");
        },
    });

    await load();
    draw();
    const timer = setInterval(async () => {
        if (document.querySelector(".overlay")) return;
        try { await load(); draw(); } catch { /* повторим */ }
    }, 15000);
    onLeave(() => clearInterval(timer));
}
