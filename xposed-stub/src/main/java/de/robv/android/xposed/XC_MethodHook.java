package de.robv.android.xposed;

/**
 * Compile-time stub - see IXposedMod.java for why this exists and why it's
 * safe (never bundled into the built APK).
 *
 * IMPORTANT: the return type of XposedHelpers.findAndHookMethod below must
 * match the real API's return type (XC_MethodHook.Unhook) exactly, or the
 * JVM's invokestatic method resolution fails at runtime with
 * NoSuchMethodError once LSPosed substitutes its real classes in - Java
 * bytecode call sites encode the full method descriptor including return
 * type, so "close enough" (e.g. declaring Object instead) is not safe here.
 */
public abstract class XC_MethodHook {

    public static class Unhook {
        public void unhook() {
        }
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public Throwable getThrowable() {
            return null;
        }

        public void setThrowable(Throwable t) {
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }
}
