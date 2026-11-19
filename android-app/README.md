# Charlie: phone app for the Sesame link

One Kotlin app that replaces Termux, Termux:API and start-relay.sh. It dials out to the same Cloudflare relay
(`relay/`), so Sesame keeps the same link. No Play Store; install the APK yourself.

## Tools it gives Sesame

| Tool | What it does | Needs |
|---|---|---|
| battery | level and charging | nothing |
| read_texts | SMS from saved contacts only, fails closed | SMS + Contacts |
| new_messages | new messages from contacts, read from Messages notifications (catches RCS) | notification access |
| list_apps, open_app | open apps on Todd's approved list | Display over other apps, or screen control |
| send_text | asks to text a contact or number; nothing sends until Todd taps Send on the phone (unlocked) | Send SMS |
| check_approval | was the text or tap approved, sent, denied, expired | |
| read_screen, tap, type_text, scroll, press_key | screen control in approved apps | accessibility service + switch in the app |
| link_status | which features are on | |

Safety rules (in `core/Safety.kt`, unit tested): never reads or taps Charlie, Settings, the lock screen/shade,
the installer or permission screens; password fields are never read or typed; taps on Send, Pay, Buy, Delete,
Call, Confirm and similar wait for Todd's OK on a notification; approvals expire after 10 minutes and run once.
Always-on notification while the link runs, with Stop link and Stop screen control buttons. No shell or
run-a-command tool.

## Limits

- RCS and group chats are not in Android's SMS store. new_messages sees new ones from notifications only
  (not history, not ones that arrived before it was turned on). send_text sends plain SMS.
- Some apps hide their screens from accessibility (banking apps, secure screens); read_screen shows nothing there.
- Android 15 blocks SMS, accessibility, notification access and Display over other apps for sideloaded apps
  until you tap App info > ⋮ > Allow restricted settings.
- Android may not open an app from the background without Display over other apps; Charlie then posts
  "Tap to open".
- Only one phone connection can hold the relay. Stop start-relay.sh in Termux before switching the link on.

## Build and test

```
cd android-app
./gradlew testDebugUnitTest            # core tests
RELAY_TEST_URL=http://127.0.0.1:8799 ./gradlew testDebugUnitTest   # plus relay tests against `wrangler dev`
CHARLIE_KEYSTORE=... CHARLIE_KEYSTORE_PASS=... ./gradlew assembleRelease
```

The release key is not in the repo. Keep using the same key, or Android refuses updates over the installed app.
