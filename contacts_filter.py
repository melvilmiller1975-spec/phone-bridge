# phone-bridge: contacts-only texts, loaded by bridge.py. Shows only texts from numbers
# saved in contacts; if contacts can't be read, returns an error and NO texts.
import json,re,subprocess,bridge
PAGE,MAX_SCAN=100,500
class Fail(Exception): pass
def norm(x):
    if not isinstance(x,str): return None
    s=re.split(r"[,;#]|ext\.?|x",x.strip().lower().replace("tel:",""),maxsplit=1)[0].strip()
    if not re.fullmatch(r"\+?[0-9 ()./-]*[0-9][0-9 ()./-]*",s): return None
    d=re.sub(r"\D","",s)
    if s[0]=="+": return "+"+d
    if d[:3]=="011" and len(d)>6: return "+"+d[3:]
    if d[:2]=="00" and len(d)>6: return "+"+d[2:]
    if len(d)==10 and d[0]>"1": return "+1"+d
    if len(d)==11 and d[0]=="1" and d[1]>"1": return "+"+d
    return "n:"+d

def run_json(a):
    p=subprocess.run(a,capture_output=True,text=True,timeout=60)
    o=(p.stdout or "").strip()
    if p.returncode or not o: raise Fail(a[0]+" failed or returned nothing (permission?)")
    try: d=json.loads(o)
    except ValueError: raise Fail(a[0]+" returned non-JSON")
    if isinstance(d,dict) and d.get("error"): raise Fail(a[0]+": "+str(d["error"])[:200])
    return d
def contact_keys():
    d=run_json(["termux-contact-list"])
    k={norm(c.get("number")) for c in d if isinstance(c,dict)}-{None} if isinstance(d,list) else set()
    if not k: raise Fail("No contact numbers readable, so no texts shown. Allow Contacts for Termux:API.")
    return k
