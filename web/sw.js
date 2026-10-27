// Service worker «Вкусно»: показывает Web Push, даже когда вкладка закрыта.
//
// Браузер будит его событием push: тело уже расшифровано браузером (шифровал notification-service ключом подписки,
// RFC 8291) — здесь только JSON { title, body, tag, url }. Лежит в корне сайта, чтобы его область (scope) покрывала
// всё приложение.

self.addEventListener("push", (event) => {
    let message = { title: "Вкусно", body: "Новое уведомление", url: "/#/profile" };
    try { message = { ...message, ...event.data.json() }; } catch { /* пустое или не JSON */ }
    event.waitUntil(self.registration.showNotification(message.title, {
        body: message.body,
        tag: message.tag,          // одно уведомление на заказ: новое заменяет прежнее, а не копится стопкой
        renotify: true,
        data: { url: message.url },
        icon: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E🍜%3C/text%3E%3C/svg%3E",
    }));
});

// Клик по уведомлению — открыть заказ: в уже открытой вкладке, если она есть.
self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const url = new URL(event.notification.data?.url || "/", self.location.origin).href;
    event.waitUntil((async () => {
        const tabs = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
        const tab = tabs.find((t) => t.url.startsWith(self.location.origin));
        if (tab) { await tab.focus(); return tab.navigate(url); }
        return self.clients.openWindow(url);
    })());
});
