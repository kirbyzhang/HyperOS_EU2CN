package local.pudding.weathercompat;

/** Match the installed APK's declaration; unknown or malformed declarations fail open. */
public final class MmsRegionPolicy {
    private MmsRegionPolicy() {}

    public static String override(String packageName, boolean enabled, Object globalVersion, String key) {
        if (!enabled || !"com.android.mms".equals(packageName)
                || !"ro.product.mod_device".equals(key) || !(globalVersion instanceof Boolean)) return null;
        return (Boolean) globalVersion ? "xiaomieu_global" : "xiaomieu";
    }
}
