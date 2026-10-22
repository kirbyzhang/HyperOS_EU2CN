package local.pudding.weathercompat;

import android.content.Context;
import local.pudding.eulocalization.HookEvidence;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class RegionCompatModule implements IXposedHookLoadPackage {
    private static final String TAG = "PuddingEURegion";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        final String feature = RegionPolicy.preferenceFor(app.packageName);
        if (feature == null) return;
        final XSharedPreferences prefs = new XSharedPreferences(
                CompatConfig.MODULE_PACKAGE, CompatConfig.PREFS);
        prefs.reload();
        final boolean defaultValue = RegionPolicy.defaultEnabled(feature);
        if (prefs.getBoolean(feature, defaultValue)) {
            Class<?> build = XposedHelpers.findClassIfExists("miui.os.Build", app.classLoader);
            if (build != null) {
                for (String field : new String[] {"IS_INTERNATIONAL_BUILD", "IS_GLOBAL_BUILD"}) {
                    try {
                        XposedHelpers.setStaticBooleanField(build, field, false);
                    } catch (Throwable error) {
                        log(app.packageName + ": " + field + " unavailable");
                    }
                }
            }
        }
        XC_MethodHook properties = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam request) {
                // Never hide a failure from the original property API.
                if (request.hasThrowable()) return;
                String key = (String) request.args[0];
                if (!"ro.product.mod_device".equals(key) && !"ro.miui.region".equals(key)) return;
                prefs.reload();
                String value = RegionPolicy.override(app.packageName,
                        prefs.getBoolean(feature, defaultValue), key);
                if (value != null) {
                    request.setResult(value);
                    HookEvidence.record(feature, "HIT");
                }
            }
        };
        PropertyHooks.getters(app.classLoader, "android.os.SystemProperties", properties);
        PropertyHooks.getters(app.classLoader, "miuix.core.util.SystemProperties", properties);
        log("region hooks installed in " + app.processName);
        if (!"com.android.phone".equals(app.packageName)) return;
        try {
            XposedHelpers.findAndHookMethod("miui.yellowpage.YellowPageUtils", app.classLoader,
                    "isYellowPageAvailable", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam request) {
                            if (request.hasThrowable()) return;
                            prefs.reload();
                            if (RegionPolicy.enableYellowPage(app.packageName,
                                    prefs.getBoolean("phone_yellowpage", true))) request.setResult(true);
                        }
                    });
            log("phone yellow page hook installed");
        } catch (Throwable error) {
            log("phone yellow page hook unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
        android.util.Log.i(TAG, message);
    }
}
