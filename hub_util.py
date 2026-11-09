# phone-bridge hub settings and helpers (test copy). The phone key stays on this phone and is sent only to the hub.
import os, secrets, subprocess, time
HUB = os.environ.get("HUB_URL", "wss://phone-bridge-test.melvilmiller1975.workers.dev/phone")
F = os.path.expanduser("~/.phone-bridge-hub-key")
if not os.path.exists(F):
    with open(F, "w") as f:
        f.write(secrets.token_urlsafe(32))
os.chmod(F, 0o600)
KEY = open(F).read().strip()
state = {"pong": 0.0, "conn": None}


def say(t):
    print(time.strftime("%H:%M:%S"), t, flush=True)


def notify(t):
    say(t)
    try:
        subprocess.run(["termux-notification", "--id", "phone-bridge", "--title", "phone-bridge", "--content", t], timeout=15)
    except Exception:
        pass
