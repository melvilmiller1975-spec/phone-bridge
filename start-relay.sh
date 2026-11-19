# phone-bridge relay launcher for Termux. Run: sh start-relay.sh
# Uses the permanent relay link instead of a trycloudflare quick tunnel.
cd "$(dirname "$0")"
termux-wake-lock
pkill -f "python.*relay_client" 2>/dev/null; sleep 1
echo "Connecting to the relay. Leave Termux open. Requests show below."
exec python -u relay_client.py
