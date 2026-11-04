# phone-bridge launcher for Termux. Run: sh start.sh
cd "$(dirname "$0")"
termux-wake-lock
pkill -f "python.*serve" 2>/dev/null; pkill cloudflared 2>/dev/null; sleep 1
python -u serve.py > server.log 2>&1 &
cloudflared tunnel --url http://127.0.0.1:8765 > tunnel.log 2>&1 &
echo "Starting the tunnel, wait up to 30 seconds..."
U=""; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do U=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' tunnel.log | grep -v '//api\.' | head -1); [ -n "$U" ] && break; sleep 2; done
[ -z "$U" ] && echo "No tunnel link yet. Type: cat tunnel.log" && exit 1
echo ""
echo "Long-press, copy this whole line, and paste it in Sesame as the app URL. Keep it private:"
echo "$U/mcp?k=$(cat ~/.phone-bridge-token)"
echo ""
echo "Leave Termux open. Requests show below."
tail -f server.log
