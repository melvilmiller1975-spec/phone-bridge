# Offline tests for the contacts-only filter, made-up numbers only. Run: python3 tests/test_contacts.py
import json,os,subprocess,sys,tempfile
os.environ["HOME"]=tempfile.mkdtemp();sys.path.insert(0,".")
import bridge,contacts_hook as h;n=h.norm
class R:
    def __init__(s,o="",c=0):s.stdout,s.returncode=o,c
SP={290:"+13045550101",250:"+12079460101",200:"CAMC",150:"+442079460101",20:"3045550101"}
A=[{"number":SP.get(i,"+1681555%04d"%i),"received":"2026-11-01 %03d"%i,"_id":i} for i in range(300,0,-1)]
C=json.dumps([{"number":"(304) 555-0101"},{"number":"+44 20 7946 0101"},{"number":None}])
S={}
def f(a,**k):
    if "x" in S:raise S["x"]
    if a[0]=="termux-contact-list":return R(S.get("c",C),S.get("rc",0))
    if a[0]=="termux-battery-status":return R('{"p":5}')
    if "s" in S:return R(S["s"])
    o=0 if "no" in S else int(a[4])
    return R(json.dumps(A[o:o+100][::-1]))
subprocess.run=f
I=lambda r:[m["_id"] for m in r["messages"]]
assert bridge.TOOLS["read_texts"][0] is h.read_texts and bridge.call is h.call
assert n("(304) 555-0101")==n("1-304-555-0101")==n("tel:+1 304 555 0101")==n("304.555.0101 ext 2")=="+13045550101"
assert n("+44 20 7946 0101")==n("011 44 20 7946 0101")!=n("207 946 0101") and n("+3045550101")!=n("3045550101")
assert n("22395")!=n("122395") and all(n(b) is None for b in ["CAMC","Montani","1-800-FLOWERS","",None,"+"])
