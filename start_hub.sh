# phone-bridge hub launcher for Termux (test copy, worker-test branch). Run: sh start_hub.sh
# Keeps the phone connected to the fixed Cloudflare address and reconnects by itself.
cd "$(dirname "$0")"
termux-wake-lock
python -c "import websocket" 2>/dev/null || pip install websocket-client
pkill -f "python.*hub_client" 2>/dev/null; sleep 1
echo "Connecting the phone to https://phone-bridge-test.melvilmiller1975.workers.dev"
echo "Leave Termux open. Sign-in codes for Sesame show here and as a notification."
python -u hub_client.py
