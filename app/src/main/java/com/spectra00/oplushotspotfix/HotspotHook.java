package com.spectra00.oplushotspotfix;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Hooks HotspotControllerImpl.setHotspotEnabled(boolean) inside SystemUI -
 * the single method both the stock and OPlus-customized hotspot Quick
 * Settings tiles funnel into - and, instead of letting it call through to
 * TetheringManager, forwards the request to a locally-running Termux
 * listener over a loopback TCP socket. Termux (already granted root the
 * normal, sandboxed way) does the actual privileged work; SystemUI itself
 * never touches su.
 *
 * HotspotControllerImpl lives in one of SystemUI's secondary dex files, and
 * handleLoadPackage() can fire before Android's multidex loader has fully
 * merged those into the process's classloader - hooking the target class
 * directly from handleLoadPackage risks a ClassNotFoundException even
 * though the class genuinely exists. The fix is to anchor on
 * SystemUIApplication.onCreate() instead: multidex is guaranteed fully
 * loaded by the time any Application's onCreate() runs, so installing the
 * real hook from inside that callback is reliable.
 *
 * See the project README for the Termux-side listener setup
 * (termux/hotspot_listener.py + Termux:Boot).
 */
public class HotspotHook implements IXposedHookLoadPackage {

    private static final String TAG = "HotspotHookDBG";

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String APPLICATION_CLASS = "com.android.systemui.SystemUIApplication";
    private static final String CONTROLLER_CLASS =
            "com.android.systemui.statusbar.policy.HotspotControllerImpl";

    // Must match the TOKEN in termux/hotspot_listener.py exactly.
    private static final String TOKEN = "fa764b123c5fe97c48f0ddd97cff1e30";
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
    }

    private void sendToTermux(boolean enable) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(LISTENER_HOST, LISTENER_PORT), SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                String payload = TOKEN + " " + (enable ? "on" : "off") + "\n";
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
