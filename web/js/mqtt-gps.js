// Точки GPS курьера по MQTT поверх WebSocket: браузер → nginx /mqtt → RabbitMQ (rabbitmq_web_mqtt) → amq.topic →
// tracking-service. Логин — идентификатор курьера, пароль — его access-токен: брокер проверяет их у tracking-service и
// разрешает писать только в топики couriers/<свой id>/... Клиент — mqtt.js, загружается при первом включении.

import { accessToken } from "./api.js";

const MQTT_JS = "https://unpkg.com/mqtt@5.10.1/dist/mqtt.min.js";

let loading = null;
function loadMqtt() {
    if (window.mqtt) return Promise.resolve(window.mqtt);
    loading ??= new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = MQTT_JS;
        script.onload = () => resolve(window.mqtt);
        script.onerror = () => { loading = null; reject(new Error("Не удалось загрузить клиент MQTT (нужен интернет)")); };
        document.head.append(script);
    });
    return loading;
}

/**
 * Соединение курьера с брокером. QoS 1 — «хотя бы один раз»: точка, отправленная в тоннеле, уйдёт после
 * переподключения из очереди клиента, а не потеряется.
 *
 * @returns {{ publish(deliveryId, point): Promise<void>, close(): void, onState(fn): void }}
 */
export async function connectCourierMqtt(courierId) {
    const mqtt = await loadMqtt();
    const listeners = new Set();
    const client = mqtt.connect(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/mqtt`, {
        username: String(courierId),
        password: accessToken(),
        clientId: `courier-${courierId}-${Math.random().toString(16).slice(2, 8)}`,
        protocolVersion: 4,
        reconnectPeriod: 3000,
        connectTimeout: 8000,
        clean: true,
    });
    const emit = (state) => listeners.forEach((fn) => fn(state));
    client.on("connect", () => emit("live"));
    client.on("reconnect", () => {
        // Токен живёт 15 минут: переподключаемся со свежим.
        client.options.password = accessToken();
        emit("reconnecting");
    });
    client.on("error", (error) => emit("error:" + error.message));

    return {
        publish(deliveryId, point) {
            return new Promise((resolve, reject) => client.publish(
                `couriers/${courierId}/deliveries/${deliveryId}/position`, JSON.stringify(point), { qos: 1 },
                (error) => (error ? reject(error) : resolve()),
            ));
        },
        close() { client.end(true); },
        onState(fn) { listeners.add(fn); },
    };
}
