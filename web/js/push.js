// Подписка этого браузера на Web Push: service worker → разрешение человека → PushManager.subscribe с открытым ключом
// VAPID платформы → адрес и ключи шифрования уходят в notification-service. Дальше уведомления о заказах приходят,
// даже когда вкладка закрыта. Работает на https и на localhost (безопасный контекст).

import { get, post, del } from "./api.js";

export const pushSupported = () => "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;

const fromBase64Url = (value) => {
    const base64 = (value + "=".repeat((4 - (value.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
    return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
};

async function registration() {
    return navigator.serviceWorker.register("sw.js");
}

/** @returns текущая подписка этого браузера или null */
export async function currentPushSubscription() {
    if (!pushSupported()) return null;
    const reg = await navigator.serviceWorker.getRegistration();
    return reg ? reg.pushManager.getSubscription() : null;
}

export async function enablePush() {
    if (!pushSupported()) throw new Error("Этот браузер не поддерживает Web Push");
    const { publicKey } = await get("/api/v1/notifications/push/public-key");
    if ((await Notification.requestPermission()) !== "granted") throw new Error("Уведомления запрещены в настройках браузера");
    const reg = await registration();
    await navigator.serviceWorker.ready;
    const subscription = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: fromBase64Url(publicKey) });
    await post("/api/v1/notifications/push/subscriptions", subscription.toJSON());
    return subscription;
}

export async function disablePush() {
    const subscription = await currentPushSubscription();
    if (!subscription) return;
    await del(`/api/v1/notifications/push/subscriptions?endpoint=${encodeURIComponent(subscription.endpoint)}`).catch(() => {});
    await subscription.unsubscribe();
}
