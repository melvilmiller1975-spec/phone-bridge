# phone-bridge: makes read_texts contacts-only; bridge.py loads it last. Fails closed.
import subprocess,bridge
from contacts_filter import Fail,norm,run_json,contact_keys,PAGE,MAX_SCAN
def _read(x):
    want,box,keys=int(bridge.lim(x)),bridge.box(x),contact_keys()
    seen,found,n,hid=set(),[],0,0
    while n<MAX_SCAN:
        page=run_json(["termux-sms-list","-l",str(PAGE),"-o",str(n),"-t",box])
        if not isinstance(page,list): raise Fail("bad termux-sms-list result")
        for m in page:
            i=m.get("_id") if isinstance(m,dict) else None
            if not isinstance(m,dict) or i in seen: continue
            if i is not None: seen.add(i)
            if keys&{norm(m.get("number")),norm(m.get("address"))}: found.append(m)
            else: hid+=1
        n+=len(page)
        if len(page)<PAGE or len(found)>=want: break
    found.sort(key=lambda m:(str(m.get("received") or ""),m.get("_id") if type(m.get("_id"))==int else 0),reverse=True)
    return {"filter":"contacts only","messages":found[:want][::-1],"checked":n,"left_out_not_in_contacts":hid}

def read_texts(x):
    try: return _read(x)
    except Fail as e: msg=str(e)
    except FileNotFoundError: msg="termux-api missing: pkg install termux-api"
    except subprocess.TimeoutExpired: msg="Termux:API timed out (waiting for a permission?)"
    except Exception as e: msg="read_texts failed: "+type(e).__name__
    print("read_texts blocked:",msg,flush=True)
    return {"error":msg,"messages":[]}
_call=bridge.call
def call(p):
    r=_call(p)
    if p.get("name")=="read_texts" and r["content"][0]["text"].startswith('{"error"'): r["isError"]=True
    return r
bridge.TOOLS["read_texts"]=(read_texts,"Read recent SMS texts from saved contacts only (read-only).",bridge.TOOLS["read_texts"][2])
bridge.call=call
