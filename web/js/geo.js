// Адрес доставки: подсказки при вводе, выбор точки на карте, недавние адреса и карты заказа.
//
// Карта — Leaflet с плитками OpenStreetMap, подсказки и обратный поиск — Photon (геокодер на данных OSM, без ключа).
// Оба адреса задаются в config.js (раздел geo): для общедоступного сервера их стоит заменить на свои или платные.
// Если карта не загрузилась (нет интернета), адрес по-прежнему можно ввести вручную.

import { html, debounce } from "./ui.js";

const DEFAULTS = {
    tiles: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>',
    geocoder: "https://photon.komoot.io",
    center: [55.7558, 37.6173],
    zoom: 12,
};
const GEO = { ...DEFAULTS, ...(window.APP_CONFIG?.geo || {}) };

const LEAFLET = {
    css: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
    cssIntegrity: "sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=",
    js: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
    jsIntegrity: "sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=",
};

let leafletPromise;

/** Leaflet грузится один раз и только там, где нужна карта. */
export function loadLeaflet() {
    if (window.L) return Promise.resolve(window.L);
    leafletPromise ??= new Promise((resolve, reject) => {
        const css = document.createElement("link");
        Object.assign(css, { rel: "stylesheet", href: LEAFLET.css, integrity: LEAFLET.cssIntegrity, crossOrigin: "" });
        document.head.append(css);
        const script = document.createElement("script");
        Object.assign(script, { src: LEAFLET.js, integrity: LEAFLET.jsIntegrity, crossOrigin: "", async: true });
        script.onload = () => resolve(window.L);
        script.onerror = () => { leafletPromise = null; reject(new Error("Карта не загрузилась")); };
        document.head.append(script);
    });
    return leafletPromise;
}

function newMap(L, el, options = {}) {
    const map = L.map(el, { zoomControl: true, attributionControl: true, ...options });
    L.tileLayer(GEO.tiles, { maxZoom: 19, attribution: GEO.attribution }).addTo(map);
    return map;
}

const pin = (L, emoji, cls = "") => L.divIcon({ className: `map-pin ${cls}`, html: `<span>${emoji}</span>`, iconSize: [36, 36], iconAnchor: [18, 34] });

// ---------- Геокодер ----------

/**
 * Подпись адреса из ответа Photon в привычном порядке: «Москва, Тверская улица, 7».
 * main — первая строка подсказки (улица и дом или название места), sub — город и регион.
 */
function describe(properties) {
    const p = properties || {};
    const street = p.street ? [p.street, p.housenumber].filter(Boolean).join(", ") : "";
    const isHouse = p.osm_key === "building" || p.osm_key === "place" && p.osm_value === "house" || (p.housenumber && !p.street);
    const place = p.name && p.name !== p.street && !isHouse ? p.name : "";
    const main = [place, street || (p.housenumber ? `${p.name || ""} ${p.housenumber}`.trim() : "")].filter(Boolean).join(", ") || p.name || "";
    const city = p.city || p.town || p.village || p.district || p.county || "";
    const sub = [city, p.state !== city ? p.state : "", p.country].filter(Boolean).join(", ");
    // В адрес доставки название заведения не идёт: курьеру нужны улица и дом, а «Мумий Тролль» он не найдёт.
    const full = [city, street || main].filter(Boolean).join(", ");
    return { main: main || city, sub, full: full || sub, hasHouse: Boolean(p.housenumber) };
}

function toSuggestion(feature) {
    const [lon, lat] = feature.geometry.coordinates;
    return { ...describe(feature.properties), latitude: lat, longitude: lon };
}

async function photon(path, params) {
    const url = new URL(path, GEO.geocoder);
    Object.entries(params).forEach(([k, v]) => v !== undefined && v !== null && url.searchParams.set(k, v));
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`Геокодер ответил ${response.status}`);
    return (await response.json()).features || [];
}

export async function suggest(query, near) {
    const features = await photon("/api/", { q: query, limit: 7, lat: near?.[0], lon: near?.[1] });
    const seen = new Set();
    return features.map(toSuggestion).filter((s) => s.main && !seen.has(s.full) && seen.add(s.full));
}

/**
 * Центр города. Без фильтра «Москва» находит регион (его середина — в Новой Москве) и улицы с этим словом,
 * а без смещения к центру по умолчанию латинское «Moscow» уводит в Айдахо.
 */
async function locateCity(city) {
    const [feature] = await photon("/api/", { q: city, limit: 1, layer: "city", lat: GEO.center[0], lon: GEO.center[1] });
    return feature ? toSuggestion(feature) : null;
}

export async function reverse(latitude, longitude) {
    const [feature] = await photon("/reverse", { lat: latitude, lon: longitude, limit: 1 });
    return feature ? toSuggestion(feature) : null;
}

// ---------- Недавние адреса (в браузере, по пользователю) ----------

const recentKey = (uid) => `fd.addresses.v1.${uid}`;

export function recentAddresses(uid) {
    try { return JSON.parse(localStorage.getItem(recentKey(uid))) || []; } catch { return []; }
}

export function rememberAddress(uid, address) {
    try {
        const list = recentAddresses(uid).filter((a) => a.address !== address.address);
        localStorage.setItem(recentKey(uid), JSON.stringify([address, ...list].slice(0, 5)));
    } catch { /* без истории адресов можно жить */ }
}

// ---------- Выбор адреса ----------

/**
 * Поле адреса с подсказками, уточнение и карта с перетаскиваемой меткой. Монтируется в готовый элемент.
 * Возвращает { value(), validate(), destroy() }; value() — тело deliveryAddress для заказа.
 * setCity(город ресторана): пока точка не выбрана, карта и подсказки смотрят туда.
 */
export function addressPicker(root, { uid, near } = {}) {
    const recent = recentAddresses(uid);
    // Пока ничего не выбрано, карта смотрит на последний адрес клиента: скорее всего, он снова закажет туда же.
    const last = recent.find((a) => a.latitude != null);
    if (last) near = [Number(last.latitude), Number(last.longitude)];
    root.innerHTML = html`<div class="addr stack">
        ${recent.length ? html`<div class="row wrap addr-recent">${recent.map((a, i) => html`<button type="button" class="chip" data-recent="${i}" title="${a.address}">📍 ${a.address}</button>`)}</div>` : ""}
        <div class="field addr-field"><label for="addr-q">Адрес доставки</label>
            <div class="addr-input"><input class="input" id="addr-q" autocomplete="off" placeholder="Улица и дом, например «Тверская 7»" role="combobox" aria-expanded="false" aria-controls="addr-list">
                <button type="button" class="btn sm ghost" data-locate title="Определить, где я">🎯</button></div>
            <ul class="addr-list" id="addr-list" role="listbox" hidden></ul>
            <span class="addr-hint muted small"></span></div>
        <div class="field"><label for="addr-details">Квартира, подъезд, этаж, домофон</label><input class="input" id="addr-details" maxlength="200" placeholder="кв. 12, подъезд 2, домофон 12К"></div>
        <div class="map addr-map"><div class="map-fallback muted small">Загружаем карту…</div></div>
        <span class="muted small">Нажмите на карту или перетащите метку, чтобы уточнить, куда подъехать курьеру.</span></div>`.value;

    const input = root.querySelector("#addr-q");
    const details = root.querySelector("#addr-details");
    const list = root.querySelector("#addr-list");
    const hint = root.querySelector(".addr-hint");
    const mapEl = root.querySelector(".addr-map");
    let point = null; // [lat, lon] выбранной точки
    let items = [];
    let active = -1;
    let map; let marker; let L;
    let request = 0;
    let destroyed = false;

    const setHint = (text, bad = false) => { hint.textContent = text; hint.classList.toggle("err", bad); };

    function showList(next) {
        items = next;
        active = -1;
        list.innerHTML = items.map((s, i) => html`<li role="option" data-i="${i}"><b>${s.main}</b><span class="muted small">${s.sub}</span></li>`.value).join("");
        list.hidden = !items.length;
        input.setAttribute("aria-expanded", String(!list.hidden));
    }

    function highlight(i) {
        active = i;
        [...list.children].forEach((li, n) => li.classList.toggle("on", n === i));
    }

    function placeMarker(lat, lon, zoom) {
        point = [lat, lon];
        if (!map) return;
        if (!marker) {
            marker = L.marker(point, { draggable: true, icon: pin(L, "📍") }).addTo(map);
            marker.on("dragend", () => { const { lat: a, lng: b } = marker.getLatLng(); pickPoint(a, b); });
        } else {
            marker.setLatLng(point);
        }
        map.setView(point, zoom ?? Math.max(map.getZoom(), 16));
    }

    function choose(s) {
        input.value = s.full;
        showList([]);
        placeMarker(s.latitude, s.longitude, 17);
        setHint(s.hasHouse ? "" : "Уточните номер дома: курьеру нужен точный адрес.", !s.hasHouse);
    }

    async function pickPoint(lat, lon) {
        placeMarker(lat, lon);
        setHint("Ищем адрес точки…");
        try {
            const found = await reverse(lat, lon);
            if (destroyed) return;
            if (found) { input.value = found.full; setHint(found.hasHouse ? "" : "Рядом нет дома с номером: проверьте адрес.", !found.hasHouse); }
            else setHint("Адрес точки не найден — впишите его вручную.", true);
        } catch {
            setHint("Не удалось определить адрес точки — впишите его вручную.", true);
        }
    }

    const search = debounce(async () => {
        const q = input.value.trim();
        if (q.length < 3) { showList([]); return; }
        const mine = ++request;
        try {
            const found = await suggest(q, point || (map ? [map.getCenter().lat, map.getCenter().lng] : near));
            if (mine === request && !destroyed) showList(found);
        } catch {
            if (mine === request) setHint("Подсказки недоступны — адрес можно ввести вручную.");
        }
    }, 300);

    input.addEventListener("input", () => { point = null; setHint(""); search(); });
    input.addEventListener("keydown", (e) => {
        if (list.hidden) return;
        if (e.key === "ArrowDown") { e.preventDefault(); highlight((active + 1) % items.length); }
        else if (e.key === "ArrowUp") { e.preventDefault(); highlight((active - 1 + items.length) % items.length); }
        else if (e.key === "Enter" && active >= 0) { e.preventDefault(); choose(items[active]); }
        else if (e.key === "Escape") { e.stopPropagation(); showList([]); }
    });
    input.addEventListener("blur", () => setTimeout(() => showList([]), 150));
    list.addEventListener("mousedown", (e) => {
        const li = e.target.closest("[data-i]");
        if (li) { e.preventDefault(); choose(items[Number(li.dataset.i)]); }
    });
    root.addEventListener("click", (e) => {
        const chip = e.target.closest("[data-recent]");
        if (chip) {
            const a = recent[Number(chip.dataset.recent)];
            input.value = a.address;
            details.value = a.details || "";
            setHint("");
            if (a.latitude != null) placeMarker(Number(a.latitude), Number(a.longitude), 17); else point = null;
        }
        if (e.target.closest("[data-locate]")) {
            if (!navigator.geolocation) { setHint("Браузер не умеет определять местоположение.", true); return; }
            setHint("Определяем местоположение…");
            navigator.geolocation.getCurrentPosition(
                (pos) => pickPoint(pos.coords.latitude, pos.coords.longitude),
                () => setHint("Местоположение недоступно — выберите точку на карте.", true),
                { enableHighAccuracy: true, timeout: 10000 },
            );
        }
    });

    loadLeaflet().then((leaflet) => {
        if (destroyed) return;
        L = leaflet;
        mapEl.innerHTML = "";
        map = newMap(L, mapEl).setView(point || near || GEO.center, point ? 17 : last ? 15 : GEO.zoom);
        map.on("click", (e) => pickPoint(e.latlng.lat, e.latlng.lng));
        if (point) placeMarker(point[0], point[1], 17);
        // Окно открывается с анимацией: размер контейнера устанавливается чуть позже первой отрисовки.
        setTimeout(() => map?.invalidateSize(), 200);
    }).catch(() => {
        mapEl.innerHTML = html`<div class="map-fallback muted small">Карта недоступна. Адрес можно ввести вручную.</div>`.value;
    });

    return {
        value() {
            const address = input.value.trim();
            const extra = details.value.trim();
            return {
                address,
                details: extra || null,
                latitude: point ? Number(point[0].toFixed(6)) : null,
                longitude: point ? Number(point[1].toFixed(6)) : null,
            };
        },
        /** Возвращает текст ошибки или пустую строку. */
        validate() {
            const address = input.value.trim();
            if (!address) { input.focus(); setHint("Укажите адрес доставки.", true); return "Укажите адрес доставки"; }
            if (address.length > 500) return "Адрес слишком длинный";
            return "";
        },
        async setCity(city) {
            if (!city || last) return;
            const found = await locateCity(city).catch(() => null);
            if (!found || destroyed) return;
            near = [found.latitude, found.longitude];
            if (map && !point) map.setView(near, GEO.zoom);
        },
        destroy() { destroyed = true; map?.remove(); },
    };
}

// ---------- Карта заказа: куда везти и где курьер ----------

/**
 * Карта, которая переживает перерисовку страницы: элемент создаётся один раз и переносится в новую разметку.
 * mount(placeholder) ставит карту на место заглушки, update({ destination, path }) обновляет метки.
 */
export function orderMap() {
    const el = document.createElement("div");
    el.className = "map order-map";
    let map; let L; let dest; let route; let courier; let fitted = "";
    let state = { destination: null, path: [] };
    const ready = loadLeaflet().then((leaflet) => {
        L = leaflet;
        map = newMap(L, el, { scrollWheelZoom: false });
        map.setView(GEO.center, GEO.zoom);
        render();
    });

    function render() {
        if (!map) return;
        const { destination, path } = state;
        if (destination) {
            dest ??= L.marker(destination, { icon: pin(L, "🏠", "home") }).addTo(map);
            dest.setLatLng(destination);
        }
        if (path.length) {
            route ??= L.polyline(path, { color: "#ff6a2b", weight: 4, dashArray: "2 8", lineCap: "round" }).addTo(map);
            route.setLatLngs(path);
            courier ??= L.marker(path[path.length - 1], { icon: pin(L, "🛵", "courier"), zIndexOffset: 1000 }).addTo(map);
            courier.setLatLng(path[path.length - 1]);
        }
        // Подгоняем вид только когда меняется набор точек «по смыслу», а не при каждом обновлении: иначе карту
        // нельзя было бы подвинуть рукой.
        const key = `${destination ? 1 : 0}:${Math.min(path.length, 1)}`;
        if (key !== fitted) {
            fitted = key;
            const points = [...(destination ? [destination] : []), ...path];
            if (points.length > 1) map.fitBounds(points, { padding: [36, 36], maxZoom: 16 });
            else if (points.length === 1) map.setView(points[0], 16);
        }
    }

    return {
        ready,
        mount(placeholder) {
            if (!placeholder) return;
            placeholder.replaceWith(el);
            setTimeout(() => map?.invalidateSize(), 0);
        },
        update(next) { state = next; render(); },
        destroy() { map?.remove(); },
    };
}

/** Ссылка «открыть в картах» для курьера: по точке, если она есть, иначе поиском по адресу. */
export function mapsLink(address) {
    if (!address) return "";
    return address.latitude != null
        ? `https://www.openstreetmap.org/?mlat=${address.latitude}&mlon=${address.longitude}#map=17/${address.latitude}/${address.longitude}`
        : `https://www.openstreetmap.org/search?query=${encodeURIComponent(address.address)}`;
}

export const formatAddress = (a) => (a ? [a.address, a.details].filter(Boolean).join(" · ") : "");
