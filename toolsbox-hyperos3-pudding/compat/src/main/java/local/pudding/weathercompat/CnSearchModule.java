package local.pudding.weathercompat;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.pudding.eulocalization.HookEvidence;

/** Route the launcher's search gesture without changing its Google feed policy. */
public final class CnSearchModule implements IXposedHookLoadPackage {
    private static final String SEARCH = "com.android.quicksearchbox";
    private static boolean enabled(XSharedPreferences prefs) {
        prefs.reload();
        return prefs.getBoolean("cn_search", true);
    }
    private static Context context(ClassLoader loader) {
        return (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.miui.home.launcher.Application", loader), "getInstance");
    }
    private static Intent intent(Context context) {
        Intent intent = new Intent("android.search.action.GLOBAL_SEARCH").setPackage(SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return context.getPackageManager().resolveActivity(intent, 0) == null ? null : intent;
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!"com.miui.home".equals(app.packageName)) return;
        final XSharedPreferences prefs = new XSharedPreferences(CompatConfig.MODULE_PACKAGE, CompatConfig.PREFS);
        // Enable search only for the unset default; explicit user gesture choices win.
        PropertyHooks.hook(app.classLoader, "com.miui.home.settings.LauncherGestureController",
                "getSlideUpGesture", new Object[] {Context.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !enabled(prefs)) return;
                        try {
                            Context launcher = (Context)p.args[0];
                            Object choice = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("com.miui.home.settings.LauncherGestureController", app.classLoader),
                                    "getSystemString", launcher, "launcher_slideup_gesture", null);
                            if (choice == null && intent(launcher) != null) {
                                p.setResult("global_search");
                                HookEvidence.record("cn_search", "HIT");
                            }
                        } catch (Throwable ignored) { HookEvidence.record("cn_search", "UNAVAILABLE"); }
                    }
                }});
        // EU318 migrates an existing slide-up search setting to no_action at startup.
        PropertyHooks.hook(app.classLoader, "com.miui.home.launcher.GlobalSearchUtil",
                "updateslideUpGestureGlobalSearch", new Object[] {new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled(prefs)) return;
                        try {
                            if (intent(context(app.classLoader)) != null) p.setResult(null);
                        } catch (Throwable ignored) { HookEvidence.record("cn_search", "UNAVAILABLE"); }
                    }
                }});
        PropertyHooks.hook(app.classLoader,
                "com.miui.home.common.searchbarconfig.SearchBarConfig", "getSearchGestureProvider",
                new Object[] {new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.hasThrowable() || !enabled(prefs)) return;
                        try {
                            if (intent(context(app.classLoader)) != null) {
                                p.setResult(SEARCH);
                                HookEvidence.record("cn_search", "HIT");
                            }
                        } catch (Throwable ignored) { HookEvidence.record("cn_search", "UNAVAILABLE"); }
                    }
                }});
        // Older gesture paths use SearchManager's global default instead of SearchBarConfig.
        PropertyHooks.hook(app.classLoader, "com.miui.home.launcher.BaseLauncher", "startSearch",
                new Object[] {String.class, boolean.class, Bundle.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!enabled(prefs)) return;
                        try {
                            Context launcher = (Context) p.thisObject;
                            Intent search = intent(launcher);
                            if (search == null) return;
                            if (p.args[0] != null) search.putExtra("query", (String)p.args[0]);
                            if (p.args[2] != null) search.putExtra("app_data", (Bundle)p.args[2]);
                            launcher.startActivity(search);
                            p.setResult(null);
                            HookEvidence.record("cn_search", "HIT");
                        } catch (Throwable ignored) { HookEvidence.record("cn_search", "UNAVAILABLE"); }
                    }
                }});
    }
}
