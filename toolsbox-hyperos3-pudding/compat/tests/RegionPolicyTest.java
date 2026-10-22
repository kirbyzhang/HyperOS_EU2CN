import local.pudding.weathercompat.RegionPolicy;

public final class RegionPolicyTest {
    public static void main(String[] args) {
        for (String pkg : new String[] {"com.miui.weather2", "com.android.thememanager",
                "com.miui.yellowpage", "com.android.phone", "com.android.settings",
                "com.miui.securitycenter", "com.android.providers.downloads",
                "com.android.providers.downloads.ui", "com.android.contacts", "com.xiaomi.mirror"}) {
            assert RegionPolicy.preferenceFor(pkg) != null;
            assert "CN".equals(RegionPolicy.override(pkg, true, "ro.miui.region"));
            assert "xiaomieu".equals(RegionPolicy.override(pkg, true, "ro.product.mod_device"));
            assert RegionPolicy.override(pkg, false, "ro.miui.region") == null;
            assert RegionPolicy.override(pkg, false, "ro.product.mod_device") == null;
            assert RegionPolicy.override(pkg, true, "ro.build.version.incremental") == null;
            assert RegionPolicy.override(pkg, true, null) == null;
        }
        for (String pkg : new String[] {null, "android", "com.miui.core", "com.android.mms",
                "com.google.android.gms", "com.autonavi.minimap", "com.miui.weather2.other"}) {
            assert RegionPolicy.preferenceFor(pkg) == null;
            assert RegionPolicy.override(pkg, true, "ro.miui.region") == null;
            assert RegionPolicy.override(pkg, true, "ro.product.mod_device") == null;
            assert !RegionPolicy.enableYellowPage(pkg, true);
        }
        assert RegionPolicy.enableYellowPage("com.android.phone", true);
        assert !RegionPolicy.enableYellowPage("com.android.phone", false);
        assert !RegionPolicy.enableYellowPage("com.miui.yellowpage", true);
        assert RegionPolicy.defaultEnabled("weather_cn");
        for (String feature : new String[] {null, "settings_cn", "security_cn", "downloads_cn",
                "contacts_cn", "mirror_cn"}) assert !RegionPolicy.defaultEnabled(feature);
        System.out.println("Region allowlist, switch-off and property isolation checks passed.");
    }
}
