package de.robv.android.xposed;

/**
 * Compile-time stub matching the real Xposed/LSPosed API class of the same
 * name and package. This module is declared `compileOnly` from :app, so it
 * is never packaged into the built APK - at runtime LSPosed injects its own
 * real implementation into the hooked process's classloader instead.
 */
public interface IXposedMod {
}
