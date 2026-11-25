# DroidWeb

**Your Android device hosts its own browser-based remote control.**

DroidWeb runs on the Android device itself: it captures the screen, serves a
web page on the device's own localhost, and lets any browser view the live
screen and tap / swipe / type on it — like VNC, but web-native, so it works
directly in a browser with no client app. A password gates everything, and an
optional Cloudflare Tunnel makes that localhost reachable ONLINE from anywhere.

## How it works

| Piece | What it does |
|---|---|
| Screen capture | `MediaProjection` → `VirtualDisplay` → `ImageReader` → JPEG frames, in a foreground service (Android doesn't kill it) |
| Web server | Embedded NanoHTTPD on `localhost:8080`: the viewer page, an MJPEG stream, a login page, and a JSON input endpoint |
| Password gate | Password is set in the app and stored as SHA-256(salt + password). The server will not start without one. Login creates a session token (cookie + `?token=`); the stream and every input are refused without it |
| Input | Root: `su -c input tap / swipe / keyevent` (runs as the shell user, UID 2000, which is the reliable form on KernelSU/Magisk). The browser scales its viewer coordinates to real display pixels |
| Online | Paste a Cloudflare Tunnel token in the app. DroidWeb downloads the `cloudflared` arm64 binary on first use (from Cloudflare's official releases — not bundled in the APK) and runs `cloudflared tunnel run --token …` pointing at `localhost:8080`, then shows the public `https://` URL. With no token it still works on local Wi-Fi / Tailscale |

## Use it

1. Install the APK (from the GitHub Actions build, or build it yourself).
2. Open DroidWeb. **Set a password first** — the Start button refuses without one.
3. (Optional) Paste a Cloudflare Tunnel token if you want it reachable from anywhere. Create one at [Cloudflare Zero Trust](https://one.dash.cloudflare.com/) → Access → Tunnels. The token is stored only on the device and is never logged.
4. Tap **Start sharing** and allow the screen-capture permission.
5. Open the shown URL in any browser (e.g. `http://192.168.1.5:8080`), log in, and drive the phone.

Tap, drag (swipe), and type from the browser. Back / Home / Recents buttons are in the viewer bar; your physical keyboard's Backspace, Enter, Escape, and arrow keys map to device keys too.

## Security — read this

This app gives **full control of the device** to anyone who logs in. A password
gate is the only thing standing between the open internet and your phone, so:

- **Use a strong, unique password.** A VNC-style short password is weak — the login page + a strong password are what matter on the open internet.
- **The tunnel makes it public.** Only enable the Cloudflare Tunnel when you need remote access, and use a tunnel token you control. Anyone with the URL still needs the password, but don't share the URL.
- The password is stored as a salted SHA-256 hash on the device, never in plaintext, and sessions expire after 24 hours.
- Changing the password invalidates all sessions.

## Build it

Builds run on **GitHub Actions only** (`.github/workflows/android.yml`):
`./gradlew assembleDebug` (and `assembleRelease`) with JDK 17. The debug APK
is uploaded as an Actions artifact on every push to `main`.

To build locally you need the Android SDK + JDK 17:
```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## What v1 does NOT include

- The VNC protocol itself — this is VNC-*like* (screen + input) over MJPEG + HTTP, which is why it works directly in a browser with no client.
- An AccessibilityService input fallback (root is the primary path; if root is unavailable, input fails and the app says so).
- Bundled `cloudflared` — it is downloaded on first use so the APK stays small.
- Auto-start on boot, multi-user support, or audio streaming.

## License

MIT — see [LICENSE](LICENSE).
