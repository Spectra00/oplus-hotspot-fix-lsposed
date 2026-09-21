package de.robv.android.xposed.callbacks;

/**
 * Compile-time stub - see IXposedMod.java for why this exists and why it's
 * safe (never bundled into the built APK). Only the fields actually read by
 * this project's hook code are declared here; the real LSPosed class has
 * more (e.g. appInfo, isFirstApplication).
 */
public final class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
