package local.pudding.weathercompat;

import android.content.ContentProvider;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class WeatherModule implements IXposedHookLoadPackage {
    private static final String TAG = "PuddingWeatherAMap";
    private static final String REDIRECTED = TAG + ".redirected";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (WeatherRequestRouter.MODULE_PACKAGE.equals(app.packageName)) {
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), app.classLoader,
                    "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!"com.miui.core".equals(app.packageName)) {
            return;
        }
        try {
            final XSharedPreferences preferences = new XSharedPreferences(
                    WeatherRequestRouter.MODULE_PACKAGE, WeatherRequestRouter.PREFS);
            XposedHelpers.findAndHookMethod(
                    "com.miui.weather2map.Weather2LocationProvider", app.classLoader,
                    "call", String.class, String.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam request) {
                            try {
                                // The provider validates the Binder caller. Do not trust extras.
                                String caller = ((ContentProvider) request.thisObject)
                                        .getCallingPackage();
                                preferences.reload();
                                boolean enabled = preferences.getBoolean(
                                        WeatherRequestRouter.ENABLED, true);
                                if (WeatherRequestRouter.shouldRedirect(
                                        enabled, caller, (String) request.args[0])) {
                                    request.args[0] = WeatherRequestRouter.AMAP_METHOD;
                                    request.setObjectExtra(REDIRECTED, Boolean.TRUE);
                                    log("weather NLP request redirected to AMap");
                                }
                            } catch (Throwable error) {
                                // Leave the original call intact when the guard cannot be checked.
                                log("request guard failed: " + error.getClass().getSimpleName());
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam request) {
                            if (!Boolean.TRUE.equals(request.getObjectExtra(REDIRECTED))) {
                                return;
                            }
                            if (request.hasThrowable()) {
                                log("AMap provider threw: "
                                        + request.getThrowable().getClass().getSimpleName());
                                return;
                            }
                            try {
                                Object result = request.getResult();
                                String location = result instanceof Bundle
                                        ? ((Bundle) result).getString("location") : null;
                                // Record only outcome, never coordinates, city, address or JSON.
                                log("AMap provider returned location="
                                        + (location != null && !location.isEmpty()));
                            } catch (Throwable error) {
                                log("result check failed: " + error.getClass().getSimpleName());
                            }
                        }
                    });
            log("provider hook installed in " + app.processName);
        } catch (Throwable error) {
            log("provider hook unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
        android.util.Log.i(TAG, message);
    }
}
