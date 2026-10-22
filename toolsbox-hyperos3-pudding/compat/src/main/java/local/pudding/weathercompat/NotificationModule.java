package local.pudding.weathercompat;

import android.content.ContentResolver;
import local.pudding.eulocalization.HookEvidence;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class NotificationModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!NotificationPolicy.consumer(app.packageName)) return;
        Class<?> systemProperties = XposedHelpers.findClass("android.os.SystemProperties", app.classLoader);
        boolean enabled = (Boolean) XposedHelpers.callStaticMethod(systemProperties, "getBoolean",
                "ro.pudding.eu.hook.notification_filter", false);
        if (!enabled) { HookEvidence.record("notification_filter", "DISABLED"); return; }
        if ("com.miui.notification".equals(app.packageName)) {
            Class<?> build = XposedHelpers.findClassIfExists("miui.os.Build", app.classLoader);
            if (build != null) {
                for (String name : new String[] {"IS_INTERNATIONAL_BUILD", "IS_GLOBAL_BUILD"}) {
                    try {
                        XposedHelpers.setStaticBooleanField(build, name, false);
                    } catch (Throwable error) {
                        XposedBridge.log("PuddingEUNotification: unavailable flag " + name);
                    }
                }
            }
        }
        XC_MethodHook read = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam request) {
                if (request.hasThrowable()) return;
                String value = NotificationPolicy.override(app.packageName, true, (String) request.args[0]);
                if (value != null) HookEvidence.record("notification_filter", "PROPERTY_HIT");
                if (value != null) request.setResult("getInt".equals(request.method.getName())
                        ? Integer.valueOf(value) : value);
            }
        };
        XC_MethodHook write = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam request) {
                if (NotificationPolicy.blockWrite(app.packageName, true, (String) request.args[0])) {
                    request.setResult(null);
                }
            }
        };
        for (String type : new String[] {"android.os.SystemProperties", "miuix.core.util.SystemProperties"}) {
            PropertyHooks.getters(app.classLoader, type, read);
            PropertyHooks.hook(app.classLoader, type, "getInt", new Object[] {String.class, int.class, read});
            if ("com.miui.notification".equals(app.packageName)) {
                PropertyHooks.hook(app.classLoader, type, "set", new Object[] {String.class, String.class, write});
            }
        }
        sharedSettings(app);
        XposedBridge.log("PuddingEUNotification: local rank=3 in " + app.processName
                + "; persistent properties preserved");
    }

    private static void sharedSettings(XC_LoadPackage.LoadPackageParam app) {
        // No process-private cache: all three consumers derive the default from the real provider.
        // Explicit changes reach the provider and its normal ContentObserver notifications.
        XC_MethodHook read = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam request) {
                if (request.hasThrowable()) return;
                Object original = request.getResult();
                String value = NotificationPolicy.setting(app.packageName, (String) request.args[1],
                        original == null ? null : String.valueOf(original));
                if ("user_fold".equals(request.args[1]) && value != null) {
                    HookEvidence.record("notification_filter", "SETTING_HIT");
                    request.setResult(request.method.getName().startsWith("getString")
                            ? value : Integer.valueOf(value));
                }
            }
        };
        PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "getInt",
                new Object[] {ContentResolver.class, String.class, int.class, read});
        PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "getString",
                new Object[] {ContentResolver.class, String.class, read});
        PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "getIntForUser",
                new Object[] {ContentResolver.class, String.class, int.class, int.class, read});
        PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "getStringForUser",
                new Object[] {ContentResolver.class, String.class, int.class, read});
        XC_MethodHook write = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam request) {
                        String key = (String) request.args[1];
                        if (NotificationPolicy.blockSettingWrite(app.packageName, key, request.args[2])) {
                            request.setResult(true);
                        }
                    }
                };
        if ("com.miui.notification".equals(app.packageName)) {
            PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "putInt",
                    new Object[] {ContentResolver.class, String.class, int.class, write});
            PropertyHooks.hook(app.classLoader, "android.provider.Settings$Global", "putString",
                    new Object[] {ContentResolver.class, String.class, String.class, write});
        }
    }
}
