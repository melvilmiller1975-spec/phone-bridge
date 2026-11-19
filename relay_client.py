# phone-bridge relay client for Termux: dials OUT to the Cloudflare relay so Sesame keeps
# one permanent link. Same read-only tools as bridge.py (battery, contacts-only texts).
# Settings live in ~/.phone-bridge-relay: line 1 the relay address (wss://...), line 2 the phone key.
# Run: sh start-relay.sh   (standard library only, no pip installs)
import base64,json,os,random,socket,ssl,struct,sys,threading,time,urllib.parse
import bridge  # loads the tools, including the contacts-only read_texts hook

CFG=os.path.expanduser("~/.phone-bridge-relay")

class WS:
    def __init__(s,url,key):
        u=urllib.parse.urlsplit(url)
        host,port=u.hostname,u.port or (443 if u.scheme=="wss" else 80)
        raw=socket.create_connection((host,port),timeout=20)
        s.sock=ssl.create_default_context().wrap_socket(raw,server_hostname=host) if u.scheme=="wss" else raw
        path=(u.path or "/")+("?"+u.query if u.query else "")
        nonce=base64.b64encode(os.urandom(16)).decode()
        s.sock.sendall((("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer %s\r\n\r\n")%(path,host,nonce,key)).encode())
        head=b""
        while b"\r\n\r\n" not in head:
            c=s.sock.recv(1)
            if not c: raise OSError("relay closed during handshake")
            head+=c
        status=head.split(b"\r\n",1)[0]
        if b" 101 " not in status+b" ": raise OSError("relay refused: "+status.decode(errors="replace"))
        s.lock=threading.Lock()
        s.sock.settimeout(75)
    def _exact(s,n):
        b=b""
        while len(b)<n:
            c=s.sock.recv(n-len(b))
            if not c: raise OSError("connection closed")
            b+=c
        return b
    def send(s,data,op=1):
        if isinstance(data,str): data=data.encode()
        n=len(data); mask=os.urandom(4)
        h=bytes([0x80|op])+(bytes([0x80|n]) if n<126 else bytes([0x80|126])+struct.pack(">H",n) if n<65536 else bytes([0x80|127])+struct.pack(">Q",n))
        body=bytes(b^mask[i%4] for i,b in enumerate(data))
        with s.lock: s.sock.sendall(h+mask+body)
    def recv(s):
        msg=b""
        while True:
            b1,b2=s._exact(2); op=b1&15; n=b2&127
            if n==126: n=struct.unpack(">H",s._exact(2))[0]
            elif n==127: n=struct.unpack(">Q",s._exact(8))[0]
            mask=s._exact(4) if b2&128 else None
            data=s._exact(n)
            if mask: data=bytes(b^mask[i%4] for i,b in enumerate(data))
            if op==9: s.send(data,10); continue   # ping -> pong
            if op==10: continue
            if op==8: raise OSError("relay closed the connection")
            msg+=data
            if b1&0x80: return msg.decode(errors="replace")
    def close(s):
        try: s.sock.close()
        except Exception: pass

def handle(ws,m):
    name=m.get("name")
    print(time.strftime("%H:%M:%S"),"call",name,flush=True)
    try: r=bridge.call({"name":name,"arguments":m.get("arguments") or {}})
    except Exception as e: r={"content":[{"type":"text","text":"tool failed: "+type(e).__name__}],"isError":True}
    try: ws.send(json.dumps({"type":"result","rid":m.get("rid"),"result":r}))
    except Exception: pass

def session(url,key):
    ws=WS(url,key)
    ws.send(json.dumps({"type":"hello","tools":bridge.tl()}))
    print(time.strftime("%H:%M:%S"),"connected to relay",flush=True)
    alive=[True]
    def pinger():
        while alive[0]:
            time.sleep(30)
            try: ws.send("ping")
            except Exception: break
    threading.Thread(target=pinger,daemon=True).start()
    try:
        while True:
            t=ws.recv()
            if t=="pong": continue
            try: m=json.loads(t)
            except ValueError: continue
            if m.get("type")=="call": threading.Thread(target=handle,args=(ws,m),daemon=True).start()
    finally:
        alive[0]=False; ws.close()

def main():
    try: url,key=[l.strip() for l in open(CFG).read().split("\n")[:2]]
    except Exception: sys.exit("Missing "+CFG+": line 1 relay address, line 2 phone key.")
    wait=2
    while True:
        start=time.time()
        try: session(url,key)
        except Exception as e: print(time.strftime("%H:%M:%S"),"disconnected:",str(e)[:120],flush=True)
        wait=2 if time.time()-start>60 else min(wait*2,60)
        time.sleep(wait+random.random())

if __name__=="__main__": main()
