package local.pudding.eulocalization;

import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainModule implements IXposedHookLoadPackage {
    private static final String TAG = "PuddingEUToolsBox";
    private static final String PROP_PREFIX = "ro.pudding.eu.hook.";
    private static final List<String> XSPACE_APPS = Arrays.asList(
            "com.tencent.mm", "com.tencent.mobileqq", "com.sina.weibo",
            "com.whatsapp", "com.facebook.katana", "com.instagram.android"
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (InternationalPolicy.applies(lpparam.packageName, isEnabled("international"),
                    isEnabled("xspace_safety"), isEnabled("fcm"), isEnabled("google_home"))) {
                forceInternational(lpparam.classLoader);
            }
            switch (lpparam.packageName) {
                case "local.pudding.eulocalization.v4":
                    hookSelf(lpparam);
                    break;
                case "com.miui.securitycore":
                    if (isEnabled("xspace")) {
                        patchXSpace(lpparam.classLoader);
                    }
                    break;
                case "com.miui.home":
                    if (isEnabled("widgets")) {
                        hookWidgets(lpparam.classLoader);
                    }
                    break;
                case "com.android.systemui":
                    if (isEnabled("systemui_icons")) {
                        hookSystemUiIcons(lpparam.classLoader);
                    }
                    break;
                case "android":
                case "com.miui.powerkeeper":
                case "com.miui.core":
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            log(lpparam.packageName + " failed", error);
        }
    }

    private static boolean isEnabled(String feature) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = XposedHelpers.callStaticMethod(
                    properties, "getBoolean", PROP_PREFIX + feature, false);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable error) {
            log("Unable to read " + PROP_PREFIX + feature, error);
            return false;
        }
    }

    private static void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                "local.pudding.eulocalization.MainActivity",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
        );
    }

    private static void forceInternational(ClassLoader loader) {
        Class<?> build = XposedHelpers.findClassIfExists("miui.os.Build", loader);
        if (build == null) {
            log("miui.os.Build not found");
            return;
        }
        setBooleanIfPresent(build, "IS_INTERNATIONAL_BUILD", true);
        setBooleanIfPresent(build, "IS_GLOBAL_BUILD", true);
    }

    private static void hookWidgets(ClassLoader loader) {
        Class<?> widget = XposedHelpers.findClassIfExists(
                "com.miui.home.launcher.MIUIWidgetUtil", loader);
        if (widget == null) {
            log("MIUIWidgetUtil not found");
            return;
        }
        XposedBridge.hookAllMethods(widget, "isMIUIWidgetSupport", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(true);
                HookEvidence.record("widgets", "HIT");
            }
        });
    }

    private static void hookSystemUiIcons(ClassLoader loader) {
        Class<?> util = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.utils.NotifImageUtil", loader);
        if (util != null) {
            hookIconMethod(util, loader,
                    "com.miui.utils.configs.MiuiConfigs", "IS_INTERNATIONAL_BUILD");
            return;
        }

        util = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.NotificationUtil", loader);
        if (util != null) {
            hookIconMethod(util, loader,
                    "com.miui.systemui.BuildConfig", "IS_INTERNATIONAL");
            return;
        }
        log("No supported notification icon utility found");
    }

    private static void hookIconMethod(
            Class<?> util, ClassLoader loader, String configName, String fieldName) {
        final Class<?> config = XposedHelpers.findClassIfExists(configName, loader);
        if (config == null) {
            log(configName + " not found");
            return;
        }
        XposedBridge.hookAllMethods(util, "shouldSubstituteSmallIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    boolean previous = XposedHelpers.getStaticBooleanField(config, fieldName);
                    param.setObjectExtra(TAG + ".previous", previous);
                    XposedHelpers.setStaticBooleanField(config, fieldName, true);
                    HookEvidence.record("systemui_icons", "HIT");
                } catch (Throwable error) {
                    log("Unable to set " + config.getName() + "." + fieldName, error);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object previous = param.getObjectExtra(TAG + ".previous");
                if (previous instanceof Boolean) {
                    try {
                        XposedHelpers.setStaticBooleanField(config, fieldName, (Boolean) previous);
                    } catch (Throwable error) {
                        log("Unable to restore " + config.getName() + "." + fieldName, error);
                    }
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void patchXSpace(ClassLoader loader) {
        // Runtime DEX fields, verified in SecurityCore 3.4.5 on EU318 and EU319.
        String[][] holders = {{"com.miui.xspace.constant.XSpaceApps", "XSPACE_INTRODUCE_APPS"},
                {"r6.a", "a"}};
        for (String[] holder : holders) {
            try {
                Class<?> type = XposedHelpers.findClassIfExists(holder[0], loader);
                if (type == null) continue;
                Object value = XposedHelpers.getStaticObjectField(type, holder[1]);
                if (!(value instanceof List)) continue;
                List<?> list = (List<?>) value;
                for (Object item : list) {
                    if (!(item instanceof String)) throw new IllegalStateException("Unexpected XSpace list type");
                }
                appendUnique((List<String>) list);
                HookEvidence.record("xspace", "APPLIED");
                return;
            } catch (Throwable error) {
                log("XSpace holder unavailable: " + holder[0] + "." + holder[1], error);
            }
        }
        HookEvidence.record("xspace", "UNAVAILABLE");
        log("XSpace app-list holder not found");
    }

    private static void appendUnique(List<String> target) {
        if (target == null) {
            return;
        }
        for (String app : XSPACE_APPS) {
            if (!target.contains(app)) {
                target.add(app);
            }
        }
    }

    private static void setBooleanIfPresent(Class<?> type, String field, boolean value) {
        try {
            XposedHelpers.setStaticBooleanField(type, field, value);
            HookEvidence.record("international:" + field, "APPLIED");
        } catch (Throwable error) {
            log(type.getName() + "." + field + " not set", error);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
        HookEvidence.record("legacy", message);
    }

    private static void log(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + ": " + error);
        HookEvidence.record("legacy", message + ":" + error.getClass().getSimpleName());
    }
}
