package local.pudding.ksu;

/** Accessibility descriptions are frequently null; they are not empty labels. */
public final class UiText {
    public static boolean sharedGroup(CharSequence label) {
        return label != null && label.toString().matches("包含 [2-9][0-9]* 个应用|包含 1[0-9]+ 个应用");
    }
    private UiText() {}
    public static boolean matches(String expected, CharSequence actual) {
        return actual != null && expected.contentEquals(actual);
    }
    public static boolean superuserTab(CharSequence label, int top, int bottom) {
        return matches("超级用户", label) && top >= 2396 && bottom <= 2656 && bottom > top;
    }
}
