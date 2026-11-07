// phone-bridge worker-test: isolated hello test for Cloudflare Workers. No phone data. Does not touch main.
export default {
async fetch(req) {
const u = new URL(req.url);
if (req.method === "GET") return new Response("phone-bridge worker-test says hello. Live bridge not touched.", { headers: { "content-type": "text/plain" } });
if (u.pathname !== "/mcp" || req.method !== "POST") return new Response("not found", { status: 404 });
const m = await req.json().catch(() => ({}));
if (!("id" in m)) return new Response(null, { status: 202 });
let r = {};
if (m.method === "initialize") r = { protocolVersion: (m.params && m.params.protocolVersion) || "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "phone-bridge-worker-test", version: "0.1" } };
else if (m.method === "tools/list") r = { tools: [{ name: "hello", description: "Say hello. Test only, no phone data.", inputSchema: { type: "object", properties: {} } }] };
else if (m.method === "tools/call") r = { content: [{ type: "text", text: "Hello from the worker-test copy. The live bridge was not touched." }] };
return Response.json({ jsonrpc: "2.0", id: m.id, result: r });
}
};
