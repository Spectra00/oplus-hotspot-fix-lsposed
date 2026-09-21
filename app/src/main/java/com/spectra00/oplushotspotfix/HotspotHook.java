package com.spectra00.oplushotspotfix;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Hooks two points inside SystemUI and forwards both to a locally-running
 * Termux listener over a loopback TCP socket, instead of letting either
 * call through to the stock logic. Termux (already granted root the
 * normal, sandboxed way) does the actual privileged work; SystemUI itself
 * never touches su.
 *
 * 1. HotspotControllerImpl.setHotspotEnabled(boolean) - the shared AOSP
 *    entry point both tile variants ultimately call.
 * 2. OplusHotspotTile.handleUserOperationInternal$1(boolean) - OnePlus's
 *    own click-handling method, which is where the actual bug lives: it
 *    runs a carrier-entitlement check (QsOperatorUtils.isVZWHotspotUnAuth /
 *    isATTHotspotUnAuth / a Sprint dialog, depending on
 *    CustomizeFeatureOption flags) *before* ever reaching
 *    setHotspotEnabled(true), and returns early without calling it at all
 *    if that check fails. Confirmed via decompiled source + on-device
 *    logcat: setHotspotEnabled(false) was reliably intercepted, but
 *    setHotspotEnabled(true) never fired even once - the tile-level gate
 *    was blocking it upstream of hook #1. Hooking this method directly
 *    skips that gate entirely.
 *
 * Both HotspotControllerImpl and OplusHotspotTile live in SystemUI's
 * secondary dex files, and handleLoadPackage() can fire before Android's
 * multidex loader has fully merged those into the process's classloader -
 * hooking either directly from handleLoadPackage risks a
 * ClassNotFoundException even though the classes genuinely exist. The fix
 * is to anchor on SystemUIApplication.onCreate() instead: multidex is
 * guaranteed fully loaded by the time any Application's onCreate() runs,
 * so installing the real hooks from inside that callback is reliable.
 *
 * See the project README for the Termux-side listener setup
 * (termux/hotspot_listener.py + Termux:Boot).
 *
 * The shared-secret token lives at TOKEN_FILE_PATH, not a compiled-in
 * constant, so it can be rotated entirely from Termux without ever
 * rebuilding this module - see readToken() and the listener's matching
 * get_or_create_token()/read_current_token().
 */
public class HotspotHook implements IXposedHookLoadPackage {

    private static final String TAG = "HotspotHookDBG";

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String APPLICATION_CLASS = "com.android.systemui.SystemUIApplication";
    private static final String CONTROLLER_CLASS =
            "com.android.systemui.statusbar.policy.HotspotControllerImpl";
    private static final String TILE_CLASS = "com.oplus.systemui.qs.tiles.OplusHotspotTile";

    // Shared secret lives in a plain file, not a compiled-in constant, so it can be
    // rotated entirely from Termux (nano/echo) without ever rebuilding this module.
    // Must match TOKEN_FILE in termux/hotspot_listener.py exactly.
    private static final String TOKEN_FILE_PATH = "/data/local/tmp/.oplushotspotfix_token";
    private static final String LISTENER_HOST = "127.0.0.1";
    private static final int LISTENER_PORT = 47291;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        Log.i(TAG, "handleLoadPackage entered for " + lpparam.packageName);

        try {
            XposedHelpers.findAndHookMethod(
                    APPLICATION_CLASS,
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installHotspotHook(lpparam.classLoader);
                        }
                    });
            Log.i(TAG, "Anchored on " + APPLICATION_CLASS + "#onCreate()");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to anchor on " + APPLICATION_CLASS, t);
        }
    }

    private void installHotspotHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    CONTROLLER_CLASS,
                    classLoader,
                    "setHotspotEnabled",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enable = (boolean) param.args[0];
                            Log.i(TAG, "setHotspotEnabled(" + enable + ") intercepted");
                            sendToTermux(enable);
                            param.setResult(null); // skip the stock TetheringManager logic
                        }
                    });
            Log.i(TAG, "Hook installed successfully on " + CONTROLLER_CLASS);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install hook on " + CONTROLLER_CLASS, t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    TILE_CLASS,
                    classLoader,
                    "handleUserOperationInternal$1",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean enable = (boolean) param.args[0];
                            Log.i(TAG, "handleUserOperationInternal$1(" + enable
                                    + ") intercepted - bypassing carrier entitlement check");
                            sendToTermux(enable);
                            param.setResult(true); // mimic "operation accepted"
                        }
                    });
            Log.i(TAG, "Hook installed successfully on " + TILE_CLASS);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install hook on " + TILE_CLASS, t);
        }
    }

    private String readToken() {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(TOKEN_FILE_PATH));
            return new String(bytes, "UTF-8").trim();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read token from " + TOKEN_FILE_PATH
                    + " - has the Termux listener run at least once?", e);
            return null;
        }
    }

    private void sendToTermux(boolean enable) {
        new Thread(() -> {
            String token = readToken();
            if (token == null || token.isEmpty()) {
                Log.e(TAG, "No token available, not sending to Termux listener");
                return;
            }
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(LISTENER_HOST, LISTENER_PORT), SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                String payload = token + " " + (enable ? "on" : "off") + "\n";
                socket.getOutputStream().write(payload.getBytes("UTF-8"));
                socket.getOutputStream().flush();
                Log.i(TAG, "Sent '" + (enable ? "on" : "off") + "' to Termux listener");
            } catch (Exception e) {
                // Termux listener not running, or Termux:Boot hasn't started it yet.
                // Never crash SystemUI over this - but do log it so it's diagnosable.
                Log.e(TAG, "Failed to reach Termux listener at "
                        + LISTENER_HOST + ":" + LISTENER_PORT, e);
            }
        }, "HotspotHook-bridge").start();
    }
}
