// Звонки: WebRTC между браузерами, сигнализация через WebSocket мессенджера.
//
// Схема — сетка (mesh): каждый участник соединён с каждым, до 4 человек. Медиа через сервер не идёт; если прямое
// соединение невозможно, его ретранслирует TURN (coturn) — учётные данные выдаёт мессенджер на 10 минут.
// Кто предлагает соединение (offer): тот, кто уже был в звонке, — новому участнику. Так двое не шлют offer друг
// другу одновременно.
//
// Для проверки на машине без камеры: localStorage["fd.calls.fakeMedia"] = "1" — вместо камеры и микрофона
// рисуется холст и звучит тон. Передача при этом настоящая: те же RTCPeerConnection, кодеки и ICE.

import { get, post } from "./api.js";
import { html, initials, toast, reportError, $ } from "./ui.js";
import { onEvent, send as wsSend, deviceId } from "./messenger-client.js";
import { session } from "./store.js";

const API = "/api/v1/messenger";
let current = null;      // идущий звонок этой вкладки
let incoming = null;     // входящий, ещё не принятый
let ringer = null;
let mounted = false;

/** Подписка на события звонков — один раз на вкладку, на любой странице. */
export function mountCalls() {
    if (mounted) return;
    mounted = true;
    onEvent(onFrame);
    window.__fdCalls = { state: () => current && snapshot() };
}

function me() {
    return session()?.user?.id;
}

// ---------- Начало и приём ----------

export async function startCall(chatId, kind) {
    if (current) return toast("Вы уже в звонке");
    const media = await localMedia(kind);
    try {
        const view = await post(`${API}/chats/${chatId}/calls`, { kind, deviceId: deviceId() });
        if (view.status === "ENDED") {
            stopMedia(media.stream);
            return toast(view.endReason === "BUSY" ? "Абонент занят" : "Звонок не состоялся", "bad");
        }
        await enterCall(view, media);
    } catch (error) {
        stopMedia(media.stream);
        reportError(error);
    }
}

async function accept() {
    const call = incoming;
    stopRinging();
    if (!call) return;
    const media = await localMedia(call.kind);
    try {
        const view = await post(`${API}/calls/${call.callId}/accept`, { deviceId: deviceId() });
        await enterCall(view, media);
    } catch (error) {
        stopMedia(media.stream);
        reportError(error);
    }
}

async function decline() {
    const call = incoming;
    stopRinging();
    if (call) await post(`${API}/calls/${call.callId}/decline`).catch(() => {});
}

/** Камера и микрофон. Отказ в доступе не мешает звонку: участник слышит и видит других, но сам — без звука/видео. */
async function localMedia(kind) {
    const fake = localStorage.getItem("fd.calls.fakeMedia") === "1";
    if (fake) return { stream: fakeStream(kind), denied: null };
    const constraints = { audio: { echoCancellation: true, noiseSuppression: true }, video: kind === "VIDEO" ? { width: { ideal: 640 }, height: { ideal: 360 } } : false };
    try {
        return { stream: await navigator.mediaDevices.getUserMedia(constraints), denied: null };
    } catch (error) {
        if (kind === "VIDEO") {
            try {
                const audioOnly = await navigator.mediaDevices.getUserMedia({ audio: true });
                toast("Камера недоступна — звонок пойдёт без вашего видео", "bad");
                return { stream: audioOnly, denied: "camera" };
            } catch { /* и микрофона нет */ }
        }
        toast(error.name === "NotAllowedError" ? "Доступ к микрофону запрещён в браузере — вы будете только слушать" : "Микрофон не найден — вы будете только слушать", "bad");
        return { stream: new MediaStream(), denied: "all" };
    }
}

function fakeStream(kind) {
    const audioCtx = new AudioContext();
    const osc = audioCtx.createOscillator();
    osc.frequency.value = 440;
    const dest = audioCtx.createMediaStreamDestination();
    const gain = audioCtx.createGain();
    gain.gain.value = 0.05;
    osc.connect(gain).connect(dest);
    osc.start();
    const tracks = [...dest.stream.getAudioTracks()];
    if (kind === "VIDEO") {
        const canvas = Object.assign(document.createElement("canvas"), { width: 320, height: 180 });
        const ctx = canvas.getContext("2d");
        let frame = 0;
        const track = canvas.captureStream(15).getVideoTracks()[0];
        // Рисуем, пока дорожка жива: холст без изменений не даёт новых кадров, и видео «замерло» бы.
        const draw = () => {
            ctx.fillStyle = `hsl(${(frame * 3) % 360} 70% 45%)`;
            ctx.fillRect(0, 0, 320, 180);
            ctx.fillStyle = "#fff";
            ctx.font = "28px sans-serif";
            ctx.fillText(`Тест ${me()} · ${frame++}`, 20, 100);
            if (track.readyState === "live") setTimeout(draw, 66);
        };
        draw();
        tracks.push(track);
    }
    return new MediaStream(tracks);
}

function stopMedia(stream) {
    stream?.getTracks().forEach((t) => t.stop());
}

// ---------- Звонок ----------

async function enterCall(view, media) {
    const ice = await get(`${API}/calls/ice-servers`).catch(() => ({ iceServers: [] }));
    current = {
        id: view.id, chatId: view.chatId, kind: view.kind, stream: media.stream, denied: media.denied,
        peers: new Map(), names: new Map(view.participants.map((p) => [p.userId, p.name])),
        iceServers: ice.iceServers, micOn: true, camOn: view.kind === "VIDEO" && media.denied !== "camera" && media.denied !== "all",
        started: Date.now(), quality: new Map(), statsTimer: null,
    };
    renderScreen();
    current.statsTimer = setInterval(collectStats, 2000);
}

function peer(userId) {
    let p = current.peers.get(userId);
    if (p) return p;
    const pc = new RTCPeerConnection({ iceServers: current.iceServers });
    p = { pc, userId, pendingCandidates: [], remote: new MediaStream(), makingOffer: false };
    current.peers.set(userId, p);

    current.stream.getTracks().forEach((track) => pc.addTrack(track, current.stream));
    // Без своих дорожек (доступ запрещён) всё равно принимаем звук и видео собеседника.
    if (!current.stream.getAudioTracks().length) pc.addTransceiver("audio", { direction: "recvonly" });
    if (current.kind === "VIDEO" && !current.stream.getVideoTracks().length) pc.addTransceiver("video", { direction: "recvonly" });

    pc.ontrack = (event) => {
        event.streams[0]?.getTracks().forEach((t) => { if (!p.remote.getTracks().includes(t)) p.remote.addTrack(t); });
        if (!event.streams[0]) p.remote.addTrack(event.track);
        renderTiles();
    };
    pc.onicecandidate = (event) => {
        if (event.candidate) signal(userId, { candidate: event.candidate.toJSON() });
    };
    pc.oniceconnectionstatechange = () => {
        const state = pc.iceConnectionState;
        if ((state === "failed" || state === "disconnected") && p.offerer) restartIce(p);
        renderTiles();
    };
    return p;
}

async function makeOffer(userId, iceRestart = false) {
    const p = peer(userId);
    p.offerer = true;
    p.makingOffer = true;
    try {
        const offer = await p.pc.createOffer({ iceRestart });
        await p.pc.setLocalDescription(offer);
        signal(userId, { sdp: p.pc.localDescription.toJSON() });
    } finally {
        p.makingOffer = false;
    }
}

function restartIce(p) {
    clearTimeout(p.restartTimer);
    p.restartTimer = setTimeout(() => {
        if (current && current.peers.get(p.userId) === p && ["failed", "disconnected"].includes(p.pc.iceConnectionState)) {
            makeOffer(p.userId, true).catch(() => {});
        }
    }, 2500);
}

async function onSignal(fromUserId, data) {
    if (!current) return;
    const p = peer(fromUserId);
    if (data.sdp) {
        await p.pc.setRemoteDescription(data.sdp);
        for (const c of p.pendingCandidates.splice(0)) await p.pc.addIceCandidate(c).catch(() => {});
        if (data.sdp.type === "offer") {
            await p.pc.setLocalDescription(await p.pc.createAnswer());
            signal(fromUserId, { sdp: p.pc.localDescription.toJSON() });
        }
    } else if (data.candidate) {
        if (p.pc.remoteDescription) await p.pc.addIceCandidate(data.candidate).catch(() => {});
        else p.pendingCandidates.push(data.candidate);
    }
}

function signal(toUserId, data) {
    wsSend({ type: "call.signal", callId: current.id, toUserId, data });
}

function dropPeer(userId) {
    const p = current?.peers.get(userId);
    if (!p) return;
    p.pc.close();
    current.peers.delete(userId);
    renderTiles();
}

async function hangUp(notifyServer = true) {
    if (!current) return;
    const call = current;
    current = null;
    clearInterval(call.statsTimer);
    call.peers.forEach((p) => p.pc.close());
    stopMedia(call.stream);
    $("#fd-call")?.remove();
    if (notifyServer) await post(`${API}/calls/${call.id}/leave`).catch(() => post(`${API}/calls/${call.id}/cancel`).catch(() => {}));
}

// ---------- События ----------

async function onFrame(frame) {
    try {
        switch (frame.type) {
            case "call.incoming":
                if (current || incoming) return;
                incoming = frame;
                showIncoming(frame);
                break;
            case "call.answered_elsewhere":
                if (incoming?.callId === frame.callId && frame.deviceId !== deviceId()) { stopRinging(); toast("Звонок принят на другом устройстве"); }
                break;
            case "call.joined":
                if (current?.id === frame.callId && frame.userId !== me()) {
                    current.names.set(frame.userId, frame.name);
                    await makeOffer(frame.userId);
                    renderTiles();
                }
                break;
            case "call.signal":
                if (current?.id === frame.callId) await onSignal(frame.fromUserId, frame.data);
                break;
            case "call.left": case "call.removed": case "call.declined":
                if (current?.id === frame.callId) {
                    dropPeer(frame.userId);
                    if (frame.type === "call.removed") toast(`${current.names.get(frame.userId) || "Участник"} больше не участник чата — отключён от звонка`);
                    if (frame.type === "call.left" && frame.reason === "connection-lost") toast(`${current.names.get(frame.userId) || "Участник"}: связь потеряна`);
                }
                break;
            case "call.kicked":
                if (current?.id === frame.callId) { await hangUp(false); toast("Доступ к чату закрыт — звонок завершён", "bad"); }
                if (incoming?.callId === frame.callId) stopRinging();
                break;
            case "call.ended":
                if (incoming?.callId === frame.callId) { stopRinging(); if (frame.reason === "NO_ANSWER" || frame.reason === "CANCELED") toast("Пропущенный звонок"); }
                if (current?.id === frame.callId) { await hangUp(false); toast(frame.reason === "BUSY" ? "Абонент занят" : frame.reason === "DECLINED" ? "Звонок отклонён" : "Звонок завершён"); }
                break;
            case "call.busy":
                if (current?.id === frame.callId) { await hangUp(false); toast("Абонент занят", "bad"); }
                break;
            case "resync":
                // После переподключения сокета — пересогласовать соединения (смена сети).
                if (current) current.peers.forEach((p) => { if (p.offerer) makeOffer(p.userId, true).catch(() => {}); });
                break;
            default:
        }
    } catch (error) {
        console.error(error);
    }
}

// ---------- Входящий звонок ----------

function showIncoming(frame) {
    document.getElementById("fd-incoming")?.remove();
    const el = document.createElement("div");
    el.id = "fd-incoming";
    el.className = "call-incoming";
    el.innerHTML = html`<div class="avatar lg">${initials(frame.fromName)}</div>
        <div><b>${frame.fromName}</b><br><span class="muted small">${frame.kind === "VIDEO" ? "Видеозвонок" : "Аудиозвонок"}${frame.chatTitle && frame.chatTitle !== frame.fromName ? " · " + frame.chatTitle : ""}</span></div>
        <div class="row"><button class="btn danger" data-call="decline">Отклонить</button><button class="btn green" data-call="accept">Принять</button></div>`.value;
    el.addEventListener("click", (event) => {
        const action = event.target.closest("[data-call]")?.dataset.call;
        if (action === "accept") accept();
        if (action === "decline") decline();
    });
    document.body.append(el);
    ringer = ring();
}

function stopRinging() {
    incoming = null;
    document.getElementById("fd-incoming")?.remove();
    ringer?.();
    ringer = null;
}

function ring() {
    try {
        const ctx = new AudioContext();
        let stopped = false;
        const beep = () => {
            if (stopped) return;
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            gain.gain.value = 0.08;
            osc.frequency.value = 520;
            osc.connect(gain).connect(ctx.destination);
            osc.start();
            osc.stop(ctx.currentTime + 0.4);
            setTimeout(beep, 1500);
        };
        beep();
        return () => { stopped = true; ctx.close(); };
    } catch {
        return () => {};
    }
}

// ---------- Экран звонка ----------

function renderScreen() {
    document.getElementById("fd-call")?.remove();
    const el = document.createElement("div");
    el.id = "fd-call";
    el.className = "call-screen";
    document.body.append(el);
    el.addEventListener("click", onControl);
    el.addEventListener("change", onDeviceChange);
    renderTiles();
}

function renderTiles() {
    const el = document.getElementById("fd-call");
    if (!el || !current) return;
    const tiles = [...current.peers.values()].map((p) => {
        const state = p.pc.iceConnectionState;
        const q = current.quality.get(p.userId);
        return html`<div class="call-tile" data-peer="${p.userId}">
            ${current.kind === "VIDEO" ? html`<video autoplay playsinline data-remote="${p.userId}"></video>` : html`<div class="avatar lg">${initials(current.names.get(p.userId))}</div><audio autoplay data-remote="${p.userId}"></audio>`}
            <div class="call-name">${current.names.get(p.userId) || "Участник"}
                ${state === "connected" || state === "completed" ? (q?.poor ? html` <span title="Плохое соединение">📶⚠️</span>` : "") : html` <span class="muted">${state === "checking" || state === "new" ? "соединяемся…" : "переподключение…"}</span>`}</div></div>`;
    });
    el.innerHTML = html`<div class="call-top"><b>${current.kind === "VIDEO" ? "Видеозвонок" : "Аудиозвонок"}</b><span class="muted small" data-timer></span>
            ${current.denied ? html`<span class="badge red">${current.denied === "all" ? "без микрофона" : "без камеры"}</span>` : ""}</div>
        <div class="call-grid">${tiles.length ? tiles : html`<div class="muted center" style="padding:40px">Ждём ответа…</div>`}
            ${current.kind === "VIDEO" ? html`<video class="call-self" autoplay playsinline muted data-local></video>` : ""}</div>
        <div class="call-controls">
            <button class="call-btn ${current.micOn ? "" : "off"}" data-ctl="mic" title="Микрофон">${current.micOn ? "🎙️" : "🔇"}</button>
            ${current.kind === "VIDEO" ? html`<button class="call-btn ${current.camOn ? "" : "off"}" data-ctl="cam" title="Камера">${current.camOn ? "📷" : "🚫"}</button>` : ""}
            <button class="call-btn" data-ctl="devices" title="Устройства">⚙️</button>
            <button class="call-btn hang" data-ctl="hangup" title="Завершить">📞</button>
        </div>
        <div class="call-devices hidden" data-devices></div>`.value;
    for (const p of current.peers.values()) {
        const media = el.querySelector(`[data-remote="${p.userId}"]`);
        if (media) media.srcObject = p.remote;
    }
    const local = el.querySelector("[data-local]");
    if (local) local.srcObject = current.stream;
    tickTimer();
}

function tickTimer() {
    const t = document.querySelector("#fd-call [data-timer]");
    if (!t || !current) return;
    const s = Math.floor((Date.now() - current.started) / 1000);
    t.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
setInterval(tickTimer, 1000);

async function onControl(event) {
    const ctl = event.target.closest("[data-ctl]")?.dataset.ctl;
    if (!ctl || !current) return;
    if (ctl === "hangup") return hangUp(true);
    if (ctl === "mic") {
        current.micOn = !current.micOn;
        current.stream.getAudioTracks().forEach((t) => { t.enabled = current.micOn; });
    }
    if (ctl === "cam") {
        current.camOn = !current.camOn;
        current.stream.getVideoTracks().forEach((t) => { t.enabled = current.camOn; });
    }
    if (ctl === "devices") {
        const box = document.querySelector("#fd-call [data-devices]");
        const devices = await navigator.mediaDevices.enumerateDevices().catch(() => []);
        const opts = (kind) => devices.filter((d) => d.kind === kind).map((d, i) => html`<option value="${d.deviceId}">${d.label || `${kind} ${i + 1}`}</option>`);
        box.innerHTML = html`<label class="field"><span>Микрофон</span><select class="input" data-device="audioinput">${opts("audioinput")}</select></label>
            ${current.kind === "VIDEO" ? html`<label class="field"><span>Камера</span><select class="input" data-device="videoinput">${opts("videoinput")}</select></label>` : ""}`.value;
        box.classList.toggle("hidden");
        return;
    }
    renderTiles();
}

/** Смена устройства без пересогласования: replaceTrack у всех отправителей. */
async function onDeviceChange(event) {
    const kind = event.target.dataset.device;
    if (!kind || !current) return;
    try {
        const constraints = kind === "audioinput" ? { audio: { deviceId: { exact: event.target.value } } } : { video: { deviceId: { exact: event.target.value } } };
        const fresh = await navigator.mediaDevices.getUserMedia(constraints);
        const track = fresh.getTracks()[0];
        const old = current.stream.getTracks().find((t) => t.kind === track.kind);
        if (old) { current.stream.removeTrack(old); old.stop(); }
        current.stream.addTrack(track);
        current.peers.forEach((p) => p.pc.getSenders().filter((s) => s.track?.kind === track.kind).forEach((s) => s.replaceTrack(track)));
        renderTiles();
    } catch (error) {
        reportError(error);
    }
}

/** Качество: потери пакетов и задержка; при плохом соединении снижаем битрейт видео, звук важнее. */
async function collectStats() {
    if (!current) return;
    for (const p of current.peers.values()) {
        try {
            const stats = await p.pc.getStats();
            let lost = 0;
            let received = 0;
            let rtt = 0;
            let bytesIn = 0;
            let frames = 0;
            stats.forEach((s) => {
                if (s.type === "inbound-rtp") { lost += s.packetsLost || 0; received += s.packetsReceived || 0; bytesIn += s.bytesReceived || 0; frames += s.framesDecoded || 0; }
                if (s.type === "candidate-pair" && s.state === "succeeded") rtt = s.currentRoundTripTime || rtt;
            });
            const prev = current.quality.get(p.userId) || {};
            const lossRate = received ? lost / (lost + received) : 0;
            const poor = lossRate > 0.08 || rtt > 0.6;
            current.quality.set(p.userId, { poor, lossRate, rtt, bytesIn, frames });
            if (poor !== prev.poor) {
                p.pc.getSenders().filter((s) => s.track?.kind === "video").forEach((s) => {
                    const params = s.getParameters();
                    if (!params.encodings?.length) return;
                    params.encodings[0].maxBitrate = poor ? 150_000 : 800_000;
                    s.setParameters(params).catch(() => {});
                });
                renderTiles();
            }
        } catch { /* соединение закрыто */ }
    }
}

/** Для проверки звонка: что реально пришло от каждого собеседника. */
function snapshot() {
    return {
        id: current.id,
        peers: [...current.peers.values()].map((p) => ({
            userId: p.userId, ice: p.pc.iceConnectionState, ...(current.quality.get(p.userId) || {}),
            remoteTracks: p.remote.getTracks().map((t) => t.kind),
        })),
    };
}

export const activeCall = () => current;
