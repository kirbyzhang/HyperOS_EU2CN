package local.pudding.weathercompat;

import java.util.Locale;

/** Keep the map's language restriction and the user's existing weather switch. */
public final class WeatherMapPolicy {
    private WeatherMapPolicy() {}

    public static boolean shouldOverride(boolean enabled, boolean rainMapInitializing,
            boolean originalResult, Locale locale) {
        return enabled && rainMapInitializing && !originalResult
                && Locale.SIMPLIFIED_CHINESE.equals(locale);
    }
}
