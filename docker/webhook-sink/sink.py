"""Приёмник вебхуков для демонстрации (compose-сервис webhook-sink).

Принимает POST на /hooks/<что угодно>, запоминает последние 200 запросов и показывает их на
http://localhost:8095. Подпись Standard Webhooks проверяется прямо в браузере (WebCrypto): вставьте
секрет подписки whsec_… — у каждого запроса появится ✓ или ✗. Сам приёмник секретов не знает.

Код ответа задаётся путём, чтобы было на чём посмотреть повторы и автовыключение подписки:
  /hooks/ok          → 200
  /hooks/fail-503    → 503 (любой код после fail-)
  /hooks/flaky       → первые две попытки каждого webhook-id — 500, третья — 200
  /hooks/gone        → 410: платформа сразу выключит подписку

Только стандартная библиотека: образ python:alpine без установки пакетов.
"""
import json
import re
import threading
from collections import Counter, deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 256 * 1024
REQUESTS = deque(maxlen=200)
ATTEMPTS = Counter()
LOCK = threading.Lock()
SHOWN_HEADERS = ("webhook-id", "webhook-timestamp", "webhook-signature", "content-type", "user-agent")

PAGE = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Приёмник вебхуков</title>
<style>
 body{font:14px/1.45 system-ui,sans-serif;margin:0;background:#f6f3ef;color:#231f1c}
 header{padding:16px 20px;background:#231f1c;color:#fff} header h1{margin:0;font-size:18px}
 main{padding:16px 20px;max-width:1100px;margin:auto}
 .bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px}
 input{flex:1;min-width:260px;padding:8px 10px;border:1px solid #d9cfc5;border-radius:8px;font:inherit}
 .card{background:#fff;border:1px solid #eadfd4;border-radius:12px;padding:12px 14px;margin-bottom:10px}
 .row{display:flex;gap:10px;flex-wrap:wrap;align-items:baseline}
 .code{font-weight:700;padding:1px 8px;border-radius:999px;background:#e6f4ea;color:#156d34}
 .code.err{background:#fde8e8;color:#9b1c1c} .muted{color:#7a6f66;font-size:12px}
 pre{background:#f6f3ef;border-radius:8px;padding:8px;overflow:auto;margin:8px 0 0;font-size:12px}
 .ok{color:#156d34;font-weight:700} .bad{color:#9b1c1c;font-weight:700}
 @media (prefers-color-scheme:dark){body{background:#15110e;color:#f4ede6}.card{background:#1f1a16;border-color:#322a24}
  pre{background:#15110e}input{background:#1f1a16;color:#f4ede6;border-color:#322a24}}
</style></head><body>
<header><h1>Приёмник вебхуков · webhook-sink</h1>
<div class="muted" style="color:#c9bfb5">Адреса для подписки: http://webhook-sink:8000/hooks/ok · /hooks/fail-503 · /hooks/flaky · /hooks/gone</div></header>
<main>
 <div class="bar"><input id="secret" placeholder="Секрет подписки whsec_… — для проверки подписи в браузере" autocomplete="off">
  <span class="muted" id="count"></span></div>
 <div id="list"></div>
</main>
<script>
const list = document.getElementById("list"), secretInput = document.getElementById("secret");
secretInput.value = sessionStorage.getItem("secret") || "";
secretInput.addEventListener("input", () => { sessionStorage.setItem("secret", secretInput.value.trim()); render(); });
let items = [];

// Standard Webhooks: base64(HMAC-SHA256(base64decode(секрет без whsec_), id + "." + timestamp + "." + тело)).
async function sign(secret, id, ts, body) {
  const raw = Uint8Array.from(atob(secret.replace(/^whsec_/, "")), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(id + "." + ts + "." + body));
  return btoa(String.fromCharCode(...new Uint8Array(mac)));
}

async function verdict(item) {
  const secret = secretInput.value.trim();
  if (!secret) return "";
  try {
    const expected = "v1," + await sign(secret, item.headers["webhook-id"], item.headers["webhook-timestamp"], item.body);
    const ok = (item.headers["webhook-signature"] || "").split(" ").includes(expected);
    return ok ? "✓ подпись верна" : "✗ подпись не совпала";
  } catch { return "✗ секрет не разобрался"; }
}

function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }

async function render() {
  document.getElementById("count").textContent = items.length ? `${items.length} запросов` : "Запросов пока нет";
  list.replaceChildren();
  for (const item of items) {
    const card = el("div", "card"), row = el("div", "row");
    row.append(el("span", "code" + (item.status >= 300 ? " err" : ""), String(item.status)),
               el("b", null, item.type || "?"), el("span", "muted", item.path), el("span", "muted", item.received));
    const v = await verdict(item);
    if (v) row.append(el("span", v.startsWith("✓") ? "ok" : "bad", v));
    let pretty = item.body; try { pretty = JSON.stringify(JSON.parse(item.body), null, 2); } catch {}
    card.append(row,
      el("div", "muted", `webhook-id: ${item.headers["webhook-id"] || "—"} · попытка ${item.attempt}`),
      el("pre", null, pretty));
    list.append(card);
  }
}

// Обычный опрос раз в 2 секунды: для страницы-демонстрации это проще всего (см. учебник, глава 1).
async function refresh() {
  try { const r = await fetch("api/requests", { cache: "no-store" }); items = await r.json(); render(); } catch {}
}
refresh(); setInterval(refresh, 2000);
</script></body></html>"""


def status_for(path, webhook_id):
    match = re.search(r"fail-(\d{3})", path)
    if match:
        return int(match.group(1))
    if "gone" in path:
        return 410
    if "flaky" in path:
        with LOCK:
            ATTEMPTS[webhook_id] += 1
            return 500 if ATTEMPTS[webhook_id] <= 2 else 200
    return 200


class Handler(BaseHTTPRequestHandler):
    server_version = "webhook-sink/1.0"

    def do_GET(self):
        if self.path.startswith("/api/requests"):
            with LOCK:
                payload = json.dumps(list(REQUESTS), ensure_ascii=False).encode()
            self.reply(200, payload, "application/json; charset=utf-8")
        else:
            self.reply(200, PAGE.encode(), "text/html; charset=utf-8")

    def do_POST(self):
        length = min(int(self.headers.get("Content-Length") or 0), MAX_BODY)
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        headers = {name: self.headers.get(name) for name in SHOWN_HEADERS if self.headers.get(name)}
        webhook_id = headers.get("webhook-id", "")
        status = status_for(self.path, webhook_id)
        try:
            event_type = json.loads(body).get("type")
        except (ValueError, AttributeError):
            event_type = None
        with LOCK:
            attempt = sum(1 for r in REQUESTS if r["headers"].get("webhook-id") == webhook_id) + 1 if webhook_id else 1
            REQUESTS.appendleft({
                "received": datetime.now(timezone.utc).astimezone().strftime("%H:%M:%S"),
                "path": self.path, "status": status, "type": event_type, "attempt": attempt,
                "headers": headers, "body": body,
            })
        print(f"{self.path} {event_type} id={webhook_id} → {status}", flush=True)
        self.reply(status, b"", "text/plain")

    def reply(self, status, payload, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("webhook-sink слушает :8000", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
