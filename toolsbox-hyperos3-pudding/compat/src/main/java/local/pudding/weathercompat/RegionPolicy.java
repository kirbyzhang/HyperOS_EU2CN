package local.pudding.weathercompat;

/** The explicit allowlist also guards against an accidentally enlarged LSPosed scope. */
public final class RegionPolicy {
    private RegionPolicy() {}

    public static String preferenceFor(String packageName) {
        if ("com.miui.weather2".equals(packageName)) return "weather_cn";
        if ("com.android.thememanager".equals(packageName)) return "theme_cn";
        if ("com.miui.yellowpage".equals(packageName)) return "yellowpage_cn";
        if ("com.android.phone".equals(packageName)) return "phone_cn";
        if ("com.android.settings".equals(packageName)) return "settings_cn";
        if ("com.miui.securitycenter".equals(packageName)) return "security_cn";
        if ("com.android.providers.downloads".equals(packageName)
                || "com.android.providers.downloads.ui".equals(packageName)) return "downloads_cn";
        if ("com.android.contacts".equals(packageName)) return "contacts_cn";
        if ("com.xiaomi.mirror".equals(packageName)) return "mirror_cn";
        return null;
    }

    public static boolean defaultEnabled(String feature) {
        return "weather_cn".equals(feature) || "theme_cn".equals(feature)
                || "yellowpage_cn".equals(feature) || "phone_cn".equals(feature);
    }

    public static String override(String packageName, boolean enabled, String key) {
        if (!enabled || preferenceFor(packageName) == null) return null;
        if ("ro.product.mod_device".equals(key)) return "xiaomieu";
        if ("ro.miui.region".equals(key)) return "CN";
        return null;
    }

    public static boolean enableYellowPage(String packageName, boolean enabled) {
        return enabled && "com.android.phone".equals(packageName);
    }
}
