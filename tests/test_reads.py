# Reading/fail-closed tests. Run from the repo folder: python3 tests/test_reads.py
from test_contacts import *
r=h.read_texts({"limit":3});assert I(r)==[20,150,290] and r["checked"]==300 and r["left_out_not_in_contacts"]==297,r
r=h.read_texts({"limit":1});assert I(r)==[290] and r["checked"]==100,r
assert I(h.read_texts({}))==[20,150,290]
S["no"]=1;r=h.read_texts({"limit":5});assert I(r)==[290] and r["checked"]==500,r
S.clear();o=bridge.call({"name":"read_texts","arguments":{"limit":2}})
assert "isError" not in o and I(json.loads(o["content"][0]["text"]))==[150,290],o
assert json.loads(bridge.call({"name":"battery"})["content"][0]["text"])=={"p":5}
for c in [{"c":""},{"c":"[]"},{"c":'[{"number":"CAMC"}]'},{"c":'{"error":"no"}'},{"c":"x"},{"rc":1},{"x":subprocess.TimeoutExpired("t",9)},{"x":FileNotFoundError()},{"s":""},{"s":'{"error":"no"}'}]:
    S.clear();S.update(c);r=h.read_texts({});assert r["messages"]==[] and r["error"] and bridge.call({"name":"read_texts"})["isError"],c
print("ALL CONTACT FILTER TESTS PASSED")
