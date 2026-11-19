# phone-bridge

Read-only bridge for Termux on Android. It can read recent texts and the battery level. It cannot send, delete or change anything. No secrets are in this repo. The passcode is made on the phone the first time it runs and saved in ~/.phone-bridge-token.

Setup in Termux:

Step 1: Install Termux and Termux:API, both from F-Droid.

Step 2: pkg install termux-api python cloudflared git

Step 3: git clone https://github.com/melvilmiller1975-spec/phone-bridge

Step 4: cd phone-bridge

Step 5: termux-sms-list -l 1   (tap Allow when asked; do not share the output)

Step 6: termux-wake-lock

Step 7: python server.py & cloudflared tunnel --url http://127.0.0.1:8765

Step 8: Copy the https link ending in trycloudflare.com. Keep the passcode private.

Also turn off battery optimization for Termux and Termux:API in Android settings.

## Permanent link (relay)

Instead of a trycloudflare link that changes every time, the phone can dial out to a small relay on Cloudflare's free Workers plan. Sesame then always uses the same address. Same read-only tools; the relay stores no texts or battery data, it only passes each request through. Code: relay/ (Worker) and relay_client.py (Termux side, standard library only, no cloudflared needed).

On the phone:

Step 1: cd phone-bridge && git pull

Step 2: Save the relay settings in ~/.phone-bridge-relay (line 1 the relay address, line 2 the phone key). Keep the key private.

Step 3: sh start-relay.sh   (leave Termux open; it reconnects by itself if the signal drops)

Deploying the relay (already done): cd relay && npm install && npx wrangler deploy, then npx wrangler secret put SESAME_KEY and npx wrangler secret put PHONE_KEY (long random strings, never committed).
