package local.pudding.weathercompat;

import android.content.Context;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.pudding.eulocalization.HookEvidence;

/** Allow only the rain map's startup check, without changing shared build flags. */
public final class WeatherMapModule implements IXposedHookLoadPackage {
    private static final String PREVIOUS = "EU2CNWeatherMap.previous";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!"com.miui.core".equals(app.packageName)) return;
        final ThreadLocal<Object> initializing = new ThreadLocal<>();
        final XSharedPreferences prefs = new XSharedPreferences(
                CompatConfig.MODULE_PACKAGE, CompatConfig.PREFS);
        try {
            Class<?> tools = XposedHelpers.findClass(
                    "com.miui.weather2map.tools.y", app.classLoader);
            Class<?> rain = XposedHelpers.findClass(
                    "com.miui.weather2map.fragment.FragmentMinuteRain", app.classLoader);
            if (tools.getDeclaredMethod("x", Context.class).getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Unsupported weather map check");
            }
            rain.getDeclaredMethod("onCreate", Bundle.class);
            XposedHelpers.findAndHookMethod(tools, "x", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam request) {
                    if (request.hasThrowable() || initializing.get() == null
                            || !(request.getResult() instanceof Boolean)) return;
                    Context context = (Context) request.args[0];
                    if (context == null) return;
                    try {
                        prefs.reload();
                        if (WeatherMapPolicy.shouldOverride(prefs.getBoolean("weather_cn", true),
                                true, (Boolean) request.getResult(),
                                context.getResources().getConfiguration().getLocales().get(0))) {
                            request.setResult(true);
                            HookEvidence.record("weather_rain_map", "HIT");
                        }
                    } catch (Throwable error) {
                        HookEvidence.record("weather_rain_map", "UNAVAILABLE");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(rain, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam request) {
                    request.setObjectExtra(PREVIOUS, initializing.get());
                    initializing.set(request.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam request) {
                    Object previous = request.getObjectExtra(PREVIOUS);
                    if (previous == null) initializing.remove();
                    else initializing.set(previous);
                }
            });
            HookEvidence.record("weather_rain_map", "INSTALLED");
        } catch (Throwable error) {
            HookEvidence.record("weather_rain_map", "UNAVAILABLE");
            XposedBridge.log("EU2CNWeatherMap: hook unavailable: " + error.getClass().getSimpleName());
        }
    }
}
