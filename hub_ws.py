# phone-bridge hub client for Termux (test copy). Run: sh start_hub.sh
import json, threading, time
import websocket
from hub_util import HUB, KEY, state, say, notify
from hub_rpc import answer


def on_message(ws, data):
    if data == "pong":
        state["pong"] = time.time()
        return
    try:
        m = json.loads(data)
    except ValueError:
        return
    if m.get("type") == "pair_code":
        notify("Sesame sign-in code: %s (10 minutes). Enter it only on a sign-in page you opened." % m.get("code"))
    elif m.get("type") == "rpc":
        threading.Thread(target=answer, args=(ws, m), daemon=True).start()


def keepalive(ws, me):
    while state["conn"] is me:
        time.sleep(30)
        if state["conn"] is not me:
            return
        if time.time() - state["pong"] > 95:
            say("hub silent 95 seconds; reconnecting")
            ws.close()
            return
        try:
            ws.send("ping")
        except Exception:
            return
