# phone-bridge 1.1 for Sesame: also accepts the key in the URL (?k=). Run: sh start.sh
import re,hmac,urllib.parse,bridge,http.server as H
def keys(h): q=urllib.parse.parse_qs(urllib.parse.urlsplit(h.path).query); return [(q.get("k") or [""])[0],h.headers.get("Authorization",""),h.headers.get("X-API-Key","")]
def ok(h): return any(hmac.compare_digest(re.sub(r"(?i)^\s*(authorization:\s*)?(bearer\s+)*","",x).strip().encode(),bridge.T.encode()) for x in keys(h) if x)
bridge.ok=ok
S=H.BaseHTTPRequestHandler; o=S.send_response
S.send_response=lambda h,c,m=None: o(h,404 if c==405 and h.path.startswith("/.well-known") else c,m)
S.log_request=lambda h,c="-",s="-": print(getattr(h,"command",""),getattr(h,"path","").split("?")[0],c,"key-ok" if hasattr(h,"headers") and ok(h) else "no-key",flush=True)
import server
