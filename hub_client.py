# phone-bridge hub client for Termux (test copy). Run: sh start_hub.sh
# Outbound authenticated WebSocket from the phone to the Cloudflare hub. Reconnects by itself.
import json, threading, time
import websocket
from hub_util import HUB, KEY, state, say
from hub_ws import on_message, keepalive


def on_open(ws):
    me = object()
    state["conn"] = me
    state["pong"] = time.time()
    ws.send(json.dumps({"type": "hello"}))
    say("connected to %s" % HUB)
    threading.Thread(target=keepalive, args=(ws, me), daemon=True).start()


def main():
    delay = 2
    while True:
        started = time.time()
        app = websocket.WebSocketApp(HUB, header=["Authorization: Bearer " + KEY],
                                     on_open=on_open, on_message=on_message,
                                     on_error=lambda ws, e: say("hub error: %s" % e),
                                     on_close=lambda ws, c, r: say("hub closed %s %s" % (c, r)))
        try:
            app.run_forever()
        except KeyboardInterrupt:
            break
        except Exception as e:
            say("connection failed: %s" % e)
        state["conn"] = None
        delay = 2 if time.time() - started > 60 else min(delay * 2, 60)
        say("reconnecting in %d seconds" % delay)
        time.sleep(delay)


if __name__ == "__main__":
    main()
