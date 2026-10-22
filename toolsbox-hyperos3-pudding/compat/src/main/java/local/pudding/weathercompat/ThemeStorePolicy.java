package local.pudding.weathercompat;

/** Only the selected standalone CN theme launcher may see this capability. */
public final class ThemeStorePolicy {
    public static final String PACKAGE = "com.miui.themestore";
    public static final String KEY = "ro.miui.support.system.app.uninstall.v2";
    public static final String SELECTED = "ro.pudding.eu.feature.theme_store";
    private ThemeStorePolicy() {}

    public static boolean applies(String packageName, boolean selected, boolean enabled, String key) {
        return PACKAGE.equals(packageName) && selected && enabled && KEY.equals(key);
    }
}
