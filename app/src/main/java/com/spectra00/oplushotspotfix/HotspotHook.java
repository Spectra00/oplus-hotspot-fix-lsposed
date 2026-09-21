package com.spectra00.oplushotspotfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

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
 * See the project README for the Termux-side listener setup
 * (termux/hotspot_listener.py + Termux:Boot).
 */
public class HotspotHook implements IXposedHookLoadPackage {

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
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

        XposedHelpers.findAndHookMethod(
                CONTROLLER_CLASS,
                lpparam.classLoader,
                "setHotspotEnabled",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean enable = (boolean) param.args[0];
                        sendToTermux(enable);
                        param.setResult(null); // skip the stock TetheringManager logic
                    }
                });
    }

    private void sendToTermux(boolean enable) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(
                        new java.net.InetSocketAddress(LISTENER_HOST, LISTENER_PORT),
                        SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                String payload = TOKEN + " " + (enable ? "on" : "off") + "\n";
                socket.getOutputStream().write(payload.getBytes("UTF-8"));
                socket.getOutputStream().flush();
            } catch (Exception ignored) {
                // Termux listener not running, or Termux:Boot hasn't started it yet.
                // Fail silently - never crash SystemUI over this.
            }
        }, "HotspotHook-bridge").start();
    }
}
