package local.pudding.weathercompat;

/** Process-local filtering values; the stored system configuration is never changed. */
public final class NotificationPolicy {
    private NotificationPolicy() {}

    public static String override(String pkg, boolean enabled, String key) {
        if (!enabled || !consumer(pkg)) return null;
        if ("persist.sys.notification_rank".equals(key)) return "3";
        if ("com.miui.notification".equals(pkg)
                && "persist.sys.notification_ver".equals(key)) return "2";
        return null;
    }

    public static boolean blockWrite(String pkg, boolean enabled, String key) {
        return enabled && "com.miui.notification".equals(pkg)
                && ("persist.sys.notification_rank".equals(key)
                || "persist.sys.notification_ver".equals(key));
    }

    public static boolean consumer(String pkg) {
        return "com.miui.notification".equals(pkg) || "com.android.settings".equals(pkg)
                || "com.android.systemui".equals(pkg);
    }

    /** Apply the same default everywhere, preserving explicit user choices (1 / -1). */
    public static String setting(String pkg, String key, String original) {
        if (!consumer(pkg) || !"user_fold".equals(key)) return original;
        if (original == null || "0".equals(original) || "-2".equals(original)
                || "-3".equals(original)) return "2";
        return original;
    }

    /** Verified initializer writes defaults 2 and unsupported -3; UI choices are 1 / -1.
     * Only suppress these automatic migrations in NotificationCenter. All Settings writes pass. */
    public static boolean blockSettingWrite(String pkg, String key, Object value) {
        if (!"com.miui.notification".equals(pkg)) return false;
        String text = String.valueOf(value);
        return ("user_fold".equals(key) && ("2".equals(text) || "-3".equals(text)))
                || ("user_aggregate".equals(key) && "-3".equals(text));
    }
}
