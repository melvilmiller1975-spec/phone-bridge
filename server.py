# phone-bridge part 2: the web server. Run: python server.py
from bridge import *
def res(m): return {"protocolVersion":(m.get("params") or {}).get("protocolVersion","2025-06-18"),"capabilities":{"tools":{}},"serverInfo":{"name":"phone-bridge","version":"1.0"}} if m.get("method")=="initialize" else {"tools":tl()} if m.get("method")=="tools/list" else call(m.get("params") or {}) if m.get("method")=="tools/call" else {}
def reply(h,c,b=b""): h.send_response(c); h.send_header("Content-Type","application/json"); h.send_header("Content-Length",str(len(b))); h.end_headers(); h.wfile.write(b)
def post(h): m=json.loads(h.rfile.read(int(h.headers.get("Content-Length") or 0)) or b"{}"); reply(h,202) if "id" not in m else reply(h,200,json.dumps({"jsonrpc":"2.0","id":m["id"],"result":res(m)}).encode())
B=type("B",(H.BaseHTTPRequestHandler,),{"do_POST":lambda h: post(h) if ok(h) else reply(h,401),"do_GET":lambda h: reply(h,405)})
print("phone-bridge on 127.0.0.1:%d  passcode: %s"%(P,T))
H.ThreadingHTTPServer(("127.0.0.1",P),B).serve_forever()
