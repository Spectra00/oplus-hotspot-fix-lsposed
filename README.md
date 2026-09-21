# Oplus Hotspot Fix (LSPosed)

Patches OnePlus/OxygenOS's native "Personal hotspot" Quick Settings tile to
run a custom root command instead of the OEM's broken carrier-subscription
check, without giving SystemUI itself root access.

## How it works

- The native tile's click handler (across both the stock AOSP `HotspotTile`
  and OnePlus's `OplusHotspotTile`/`HotspotTileEx` variants) funnels into one
  method: `HotspotControllerImpl.setHotspotEnabled(boolean)`.
- This module hooks that exact method via LSPosed. Instead of letting it call
  through to `TetheringManager` (where the subscription check lives), it
  sends a one-line authenticated message over a loopback-only TCP socket
  (`127.0.0.1:47291`) to a listener running in Termux.
- Termux — already granted root the normal way via KernelSU-Next's per-app
  allowlist — runs the actual `su -c "cmd wifi start-softap ... && ndc nat
  enable ..."` command. **SystemUI never touches `su` at all.**

```
[Tap "Personal hotspot" tile]
        |
        v
SystemUI (com.android.systemui, UID 1000)
  HotspotControllerImpl.setHotspotEnabled(bool)   <- hooked by this module
        |
        | loopback TCP socket, 127.0.0.1:47291, token-authenticated
        v
Termux (rooted via KernelSU-Next, sandboxed like any other app)
  hotspot_listener.py  --su-->  cmd wifi start-softap / ndc nat enable
```

## Project layout

- `app/` — the LSPosed module itself (`HotspotHook.java`).
- `xposed-stub/` — compile-time-only stubs of the `de.robv.android.xposed.*`
  API. LSPosed injects its own real versions of these classes into the
  hooked process at runtime; this module exists purely so `:app` compiles,
  and is never bundled into the built APK (declared `compileOnly`).
- `termux/hotspot_listener.py` — the socket listener that runs in Termux and
  actually executes the root command.
- `termux/boot/hotspot-listener.sh` — starts the listener automatically on
  boot via the separate **Termux:Boot** app.

## Setup

### 1. Build and install the module

Either build via this repo's GitHub Actions workflow (Actions tab → download
the `oplus-hotspot-fix-debug-apk` artifact from a run) or `./gradlew
assembleDebug` locally, then:

```bash
su -c "pm install -r /path/to/app-debug.apk"
```

**Note on the debug signing key:** unlike the companion Quick-Tile-Settings
tile app, this repo does not ship a committed `debug.keystore` — GitHub
Actions will auto-generate a fresh one on every run, so *if you rebuild via
Actions more than once, you'll need to uninstall the old APK first* (`su -c
"pm uninstall com.spectra00.oplushotspotfix"`) before installing the new one,
same issue as before we fixed it on the other repo. If you want the same
one-time fix here, generate your own on a machine without approval
restrictions and commit it (`keytool -genkeypair -v -keystore
app/debug.keystore -alias androiddebugkey -storepass android -keypass
android -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=Android
Debug,O=Android,C=US"`), then add a `signingConfigs { debug { storeFile =
file("debug.keystore"); storePassword = "android"; keyAlias =
"androiddebugkey"; keyPassword = "android" } }` block to
`app/build.gradle.kts` pointing the `debug` buildType at it.

### 2. Install and enable LSPosed

Install an LSPosed build compatible with KernelSU-Next (LSPosed integrates
as a KernelSU module rather than needing Zygisk directly). In LSPosed
Manager: enable **Oplus Hotspot Fix**, scope it to **System UI**
(`com.android.systemui`), then reboot.

### 3. Set up the Termux listener

```bash
# In Termux
cp termux/hotspot_listener.py ~/hotspot_listener.py
chmod +x ~/hotspot_listener.py
```

Edit the `SSID`, `SECURITY_TYPE`, `PASSPHRASE`, `WIFI_INTERFACE`,
`UPSTREAM_INTERFACE`, and `SUBNET` constants at the top of
`~/hotspot_listener.py` to match your actual setup (the defaults are
placeholders). Also change `TOKEN` to your own random value — and make the
exact same change to `TOKEN` in
`app/src/main/java/com/spectra00/oplushotspotfix/HotspotHook.java`, then
rebuild the module, since the two must match exactly.

Install **Termux:Boot** (a separate app in F-Droid/the Termux GitHub
releases — not in the main Termux app) so the listener survives reboots
without you having to manually open Termux:

```bash
mkdir -p ~/.termux/boot
cp termux/boot/hotspot-listener.sh ~/.termux/boot/hotspot-listener.sh
chmod +x ~/.termux/boot/hotspot-listener.sh
```

Also exempt Termux from battery optimization (Android Settings → Apps →
Termux → Battery → Unrestricted) so Android doesn't kill the listener in the
background.

Reboot once to confirm the listener starts automatically:

```bash
pgrep -fa hotspot_listener.py
```

### 4. Test

Tap the native "Personal hotspot" tile in Quick Settings. Check
`~/hotspot_listener.log` in Termux if it doesn't work as expected.

## Safety notes

- This hooks a core SystemUI class. A mistake here can affect the whole
  Quick Settings UI, not just this tile. Keep a tested recovery path (custom
  recovery, a stock ROM image via fastboot, or at minimum knowing how to
  boot into KernelSU-Next's safe mode to disable modules) before enabling
  this module.
- `param.setResult(null)` fully replaces the native tethering call — the
  hotspot toggle in Android's own Settings app will no longer reflect actual
  state, since `TetheringManager` is bypassed entirely.
- The socket is bound to `127.0.0.1` only (not reachable off-device) and
  gated by a shared-secret token, but any other locally-installed app in
  principle could still attempt to guess the token and hit the port. Treat
  the token like a password — don't reuse the placeholder value.
