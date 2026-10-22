package local.pudding.eulocalization;

/** Package boundaries for the three independent international-environment repairs. */
public final class InternationalPolicy {
    private InternationalPolicy() {}

    public static boolean applies(String pkg, boolean legacy, boolean xspaceSafety,
            boolean fcm, boolean googleHome) {
        if ("com.miui.securitycore".equals(pkg)) return legacy || xspaceSafety;
        if ("com.miui.home".equals(pkg)) return legacy || googleHome;
        if ("android".equals(pkg) || "com.miui.powerkeeper".equals(pkg)
                || "com.miui.core".equals(pkg)) return legacy || fcm;
        return false;
    }
}
