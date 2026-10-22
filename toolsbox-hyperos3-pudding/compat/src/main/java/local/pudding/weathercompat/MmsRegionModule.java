package local.pudding.weathercompat;

import android.app.Application;
import local.pudding.eulocalization.HookEvidence;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MmsRegionModule implements IXposedHookLoadPackage {
    private volatile Object declaration;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!"com.android.mms".equals(app.packageName)) return;
        XSharedPreferences prefs = new XSharedPreferences(CompatConfig.MODULE_PACKAGE, CompatConfig.PREFS);
        declaration = metadata(app.appInfo);
        // Resolve before the application constructor, where static flags may already be cached.
        try {
            Class<?> thread = XposedHelpers.findClass("android.app.ActivityThread", app.classLoader);
            Object current = XposedHelpers.callStaticMethod(thread, "currentActivityThread");
            Context context = (Context) XposedHelpers.callMethod(current, "getSystemContext");
            resolve(context);
        } catch (Throwable error) {
            XposedBridge.log("PuddingEUMms: early metadata unavailable; attach fallback");
        }
        XC_MethodHook properties = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam request) {
                if (request.hasThrowable()) return;
                if (!"ro.product.mod_device".equals(request.args[0])) return;
                prefs.reload();
                String value = MmsRegionPolicy.override(app.packageName,
                        prefs.getBoolean("mms_region", true), declaration, (String) request.args[0]);
                if (value != null) {
                    request.setResult(value);
                    HookEvidence.record("mms_region", "HIT");
                } else {
                    HookEvidence.record("mms_region", "UNCHANGED_DISABLED_OR_UNKNOWN_METADATA");
                }
            }
        };
        PropertyHooks.getters(app.classLoader, "android.os.SystemProperties", properties);
        PropertyHooks.getters(app.classLoader, "miuix.core.util.SystemProperties", properties);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam request) {
                if (declaration == null) resolve((Context) request.args[0]);
            }
        });
        XposedBridge.log("PuddingEUMms: installed APK global_version=" + declaration);
    }

    private void resolve(Context context) {
        try {
            declaration = metadata(context.getPackageManager().getApplicationInfo(
                    "com.android.mms", PackageManager.GET_META_DATA));
        } catch (Throwable error) {
            XposedBridge.log("PuddingEUMms: metadata unavailable, original region preserved");
        }
    }

    @SuppressWarnings("deprecation")
    private static Object metadata(ApplicationInfo info) {
        return info != null && info.metaData != null ? info.metaData.get("global_version") : null;
    }
}
