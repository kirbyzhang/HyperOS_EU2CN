package local.pudding.weathercompat;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.pudding.eulocalization.HookEvidence;

/** Installs overloads independently so an absent optional signature cannot abort others. */
final class PropertyHooks {
    private PropertyHooks() {}

    static void getters(ClassLoader loader, String type, XC_MethodHook callback) {
        hook(loader, type, "get", new Object[] {String.class, callback});
        hook(loader, type, "get", new Object[] {String.class, String.class, callback});
    }

    static void hook(ClassLoader loader, String type, String method, Object[] args) {
        try {
            XposedHelpers.findAndHookMethod(type, loader, method, args);
            HookEvidence.record(type + "." + method + "/" + (args.length - 1), "REGISTERED");
        } catch (Throwable error) {
            HookEvidence.record(type + "." + method + "/" + (args.length - 1), "UNAVAILABLE");
            XposedBridge.log("PuddingEU: " + type + "." + method + " unavailable: "
                    + error.getClass().getSimpleName());
        }
    }
}
