package local.pudding.weathercompat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.pudding.eulocalization.HookEvidence;

/** Enable the official launcher's VIEW_HOME handoff without changing global properties. */
public final class ThemeStoreModule implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!ThemeStorePolicy.PACKAGE.equals(app.packageName)) return;
        final XSharedPreferences prefs = new XSharedPreferences(CompatConfig.MODULE_PACKAGE, CompatConfig.PREFS);
        final Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", app.classLoader);
        XC_MethodHook read = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam request) {
                if (request.hasThrowable() || !ThemeStorePolicy.KEY.equals(request.args[0])) return;
                prefs.reload();
                boolean selected = (Boolean) XposedHelpers.callStaticMethod(properties, "getBoolean",
                        ThemeStorePolicy.SELECTED, false);
                if (ThemeStorePolicy.applies(app.packageName, selected, prefs.getBoolean("theme_cn", true),
                        (String) request.args[0])) {
                    request.setResult(Boolean.TRUE);
                    HookEvidence.record("theme_store_entry", "HIT");
                }
            }
        };
        for (String type : new String[] {"android.os.SystemProperties", "miuix.core.util.SystemProperties"}) {
            PropertyHooks.hook(app.classLoader, type, "getBoolean", new Object[] {String.class, boolean.class, read});
        }
    }
}
