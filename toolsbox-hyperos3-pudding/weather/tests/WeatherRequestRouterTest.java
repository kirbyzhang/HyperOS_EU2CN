import local.pudding.weathercompat.WeatherRequestRouter;

public final class WeatherRequestRouterTest {
    public static void main(String[] args) {
        assert WeatherRequestRouter.shouldRedirect(true, "com.miui.weather2", "locationFromNlp");
        assert !WeatherRequestRouter.shouldRedirect(false, "com.miui.weather2", "locationFromNlp");
        assert !WeatherRequestRouter.shouldRedirect(true, "com.autonavi.minimap", "locationFromNlp");
        assert !WeatherRequestRouter.shouldRedirect(true, "com.miui.weather2", "locationFromAmap");
        assert !WeatherRequestRouter.shouldRedirect(true, "com.miui.weather2", "locationFromWifi");
        assert !WeatherRequestRouter.shouldRedirect(true, null, "locationFromNlp");
        assert !WeatherRequestRouter.shouldRedirect(true, "com.miui.weather2", null);
        assert !WeatherRequestRouter.shouldRedirect(true, "com.miui.weather2.other", "locationFromNlp");
        System.out.println("Weather request isolation checks passed (8 cases).");
    }
}
