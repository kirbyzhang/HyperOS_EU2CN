package local.pudding.weathercompat;

/** Restrict the redirect to the authenticated weather caller and NLP operation. */
public final class WeatherRequestRouter {
    public static final String MODULE_PACKAGE = "local.pudding.weathercompat";
    public static final String PREFS = "weather_compat";
    public static final String ENABLED = "use_amap";
    public static final String AMAP_METHOD = "locationFromAmap";

    private WeatherRequestRouter() {}

    public static boolean shouldRedirect(boolean enabled, String caller, String method) {
        return enabled && "com.miui.weather2".equals(caller)
                && "locationFromNlp".equals(method);
    }
}
