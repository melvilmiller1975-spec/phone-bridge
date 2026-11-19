// phone-bridge relay. No texts or battery data are stored or logged here: each
// request is passed to the phone and the phone's answer is passed straight back.
// Only the phone's tool list (names and descriptions) is saved, so Sesame can list
// tools while the phone is offline.
import { DurableObject } from "cloudflare:workers";

const CALL_TIMEOUT_MS = 30000;
const MAX_BODY = 64 * 1024;
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const text = (t, status = 200) => new Response(t, { status, headers: { "content-type": "text/plain", "cache-control": "no-store" } });

// Key from ?k=, "Authorization: Bearer ..." or X-API-Key (same forms the Termux bridge accepts).
function presentedKeys(req, url) {
  const out = [];
  const k = url.searchParams.get("k");
  if (k) out.push(k);
  const a = req.headers.get("authorization");
  if (a) out.push(a.replace(/^\s*(authorization:\s*)?(bearer\s+)*/i, "").trim());
  const x = req.headers.get("x-api-key");
  if (x) out.push(x.trim());
  return out;
}
async function sha(s) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
}
async function keyOk(req, url, secret) {
  if (!secret || secret.length < 20) return false; // refuse to run with a missing or weak key
  const want = await sha(secret);
  for (const k of presentedKeys(req, url)) {
    if (crypto.subtle.timingSafeEqual(await sha(k), want)) return true;
  }
  return false;
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const stub = env.RELAY.get(env.RELAY.idFromName("phone"));
    if (url.pathname === "/") return text("phone-bridge relay");
    if (url.pathname.startsWith("/.well-known")) return text("not found", 404);

    if (url.pathname === "/phone") {
      if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") return text("websocket only", 426);
      if (!(await keyOk(req, url, env.PHONE_KEY))) return text("unauthorized", 401);
      return stub.fetch(new Request("https://relay/phone", req));
    }

    if (url.pathname === "/mcp" || url.pathname === "/status") {
      if (!(await keyOk(req, url, env.SESAME_KEY))) return json({ error: "unauthorized" }, 401);
      if (url.pathname === "/status") return stub.fetch("https://relay/status");
      if (req.method !== "POST") return text("method not allowed", 405);
      const body = await req.text();
      if (body.length > MAX_BODY) return json({ error: "too large" }, 413);
      return stub.fetch("https://relay/mcp", { method: "POST", body });
    }
    return text("not found", 404);
  },
};

export class PhoneRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map();
    // The phone sends "ping" every 30 s; Cloudflare answers "pong" without waking this object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  phone() {
    return this.ctx.getWebSockets("phone")[0] || null;
  }

  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/phone") {
      for (const old of this.ctx.getWebSockets("phone")) {
        try { old.close(4000, "replaced by a newer phone connection"); } catch {}
      }
      const [client, server] = Object.values(new WebSocketPair());
      this.ctx.acceptWebSocket(server, ["phone"]);
      await this.ctx.storage.put("connectedAt", Date.now());
      return new Response(null, { status: 101, webSocket: client });
    }
    if (path === "/status") {
      return json({
        phone_connected: !!this.phone(),
        connected_at: (await this.ctx.storage.get("connectedAt")) || null,
        tools: ((await this.ctx.storage.get("tools")) || []).map((t) => t.name),
      });
    }
    if (path === "/mcp") return this.mcp(await req.text());
    return text("not found", 404);
  }

  async mcp(body) {
    let m;
    try { m = JSON.parse(body || "{}"); } catch { return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }); }
    if (Array.isArray(m) || typeof m !== "object" || m === null)
      return json({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Batches are not supported" } });
    if (!("id" in m)) return new Response(null, { status: 202 }); // notification
    const ok = (result) => json({ jsonrpc: "2.0", id: m.id, result });
    switch (m.method) {
      case "initialize":
        return ok({
          protocolVersion: m.params?.protocolVersion || "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "phone-bridge", version: "2.0-relay" },
        });
      case "ping":
        return ok({});
      case "tools/list":
        return ok({ tools: (await this.ctx.storage.get("tools")) || [] });
      case "tools/call":
        return ok(await this.callPhone(m.params || {}));
      default:
        return json({ jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "Method not found" } });
    }
  }

  async callPhone(params) {
    const ws = this.phone();
    const fail = (t) => ({ content: [{ type: "text", text: t }], isError: true });
    if (!ws) return fail("Phone is offline: the phone app is not connected to the relay right now.");
    const rid = crypto.randomUUID();
    const answer = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(rid);
        resolve(fail("The phone did not answer within 30 seconds."));
      }, CALL_TIMEOUT_MS);
      this.pending.set(rid, (r) => { clearTimeout(timer); resolve(r); });
    });
    try {
      ws.send(JSON.stringify({ type: "call", rid, name: String(params.name || ""), arguments: params.arguments || {} }));
    } catch {
      this.pending.get(rid)?.(fail("Phone connection dropped."));
    }
    return answer;
  }

  async webSocketMessage(ws, msg) {
    let m;
    try { m = JSON.parse(typeof msg === "string" ? msg : new TextDecoder().decode(msg)); } catch { return; }
    if (m.type === "hello" && Array.isArray(m.tools)) {
      const tools = m.tools
        .filter((t) => t && typeof t.name === "string")
        .slice(0, 50)
        .map((t) => ({ name: t.name, description: String(t.description || ""), inputSchema: t.inputSchema || { type: "object", properties: {} } }));
      await this.ctx.storage.put("tools", tools);
      ws.send(JSON.stringify({ type: "hello_ok", tools: tools.length }));
    } else if (m.type === "result" && typeof m.rid === "string") {
      const done = this.pending.get(m.rid);
      if (done) {
        this.pending.delete(m.rid);
        const r = m.result && Array.isArray(m.result.content)
          ? m.result
          : { content: [{ type: "text", text: "bad answer from phone" }], isError: true };
        done(r);
      }
    }
  }

  async webSocketClose(ws, code) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "closing"); } catch {}
  }
  async webSocketError() {}
}
