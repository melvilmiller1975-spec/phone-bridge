// phone-bridge hub helpers (worker-test copy)
export const enc = new TextEncoder();
export function b64u(buf) {
  let s = "";
  for (const b of new Uint8Array(buf)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export function rand(n = 32) {
  return b64u(crypto.getRandomValues(new Uint8Array(n)));
}
export async function sha(s) {
  return b64u(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}
export function json(o, status = 200, h = {}) {
  return new Response(JSON.stringify(o), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...h },
  });
}
export function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => "&#" + c.charCodeAt(0) + ";");
}
export function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
export async function body(req) {
  const t = req.headers.get("content-type") || "";
  if (t.includes("json")) return await req.json().catch(() => ({}));
  return Object.fromEntries(new URLSearchParams(await req.text()));
}
