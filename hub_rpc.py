# phone-bridge hub client for Termux (test copy). Run: sh start_hub.sh
# Outbound authenticated WebSocket to the Cloudflare hub; answers Sesame's tool calls
# with the read-only tools in bridge.py and reconnects by itself.
import json, threading, time
import websocket
import bridge
from hub_util import HUB, KEY, state, say, notify


def answer(ws, m):
    req = m.get("msg") or {}
    try:
        if req.get("method") == "tools/list":
            res = {"tools": bridge.tl()}
        elif req.get("method") == "tools/call":
            res = bridge.call(req.get("params") or {})
        else:
            res = {}
    except Exception as e:
        res = {"content": [{"type": "text", "text": "phone error: %s" % e}], "isError": True}
    try:
        ws.send(json.dumps({"type": "result", "rid": m.get("rid"), "result": res}))
    except Exception as e:
        say("could not send answer: %s" % e)
