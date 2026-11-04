# phone-bridge: read-only texts and battery for Termux. No secrets in this file.
import json,os,secrets,subprocess,hmac,http.server as H
P=int(os.environ.get("PORT","8765"))
F=os.path.expanduser("~/.phone-bridge-token")
os.path.exists(F) or open(F,"w").write(secrets.token_urlsafe(24))
os.chmod(F,0o600)
T=open(F).read().strip()
def run(a): return json.loads(subprocess.run(a,capture_output=True,text=True,timeout=60).stdout or "null")
def lim(x): return str(max(1,min(int(x.get("limit") or 20),100)))
def box(x): return x.get("box") if x.get("box") in ("inbox","sent","all") else "inbox"
def tl(): return [{"name":k,"description":v[1],"inputSchema":{"type":"object","properties":v[2]}} for k,v in TOOLS.items()]
def ok(h): return hmac.compare_digest(h.headers.get("Authorization","").encode(),("Bearer "+T).encode())
TOOLS={"read_texts":(lambda x:run(["termux-sms-list","-l",lim(x),"-t",box(x)]),"Read recent SMS/MMS texts (read-only).",{"limit":{"type":"integer","description":"1-100, default 20"},"box":{"type":"string","enum":["inbox","sent","all"]}}),"battery":(lambda x:run(["termux-battery-status"]),"Phone battery level and charging state.",{})}
def call(p): return {"content":[{"type":"text","text":json.dumps(TOOLS[p["name"]][0](p.get("arguments") or {}))}]} if p.get("name") in TOOLS else {"content":[{"type":"text","text":"unknown tool"}],"isError":True}
import contacts_hook  # contacts-only read_texts; keep this the last line
