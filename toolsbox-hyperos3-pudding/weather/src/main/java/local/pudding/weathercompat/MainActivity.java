package local.pudding.weathercompat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    public static boolean isModuleActive() { return false; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences preferences = getSharedPreferences(
                WeatherRequestRouter.PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setTextSize(22f);
        title.setText("天气高德定位兼容");
        content.addView(title);

        CheckBox enabled = new CheckBox(this);
        enabled.setText("天气使用高德定位");
        enabled.setTextSize(18f);
        enabled.setPadding(0, 32, 0, 32);
        enabled.setChecked(preferences.getBoolean(WeatherRequestRouter.ENABLED, true));
        enabled.setOnCheckedChangeListener((button, checked) -> preferences.edit()
                .putBoolean(WeatherRequestRouter.ENABLED, checked).apply());
        content.addView(enabled);

        TextView note = new TextView(this);
        note.setTextSize(16f);
        note.setText("请在 LSPosed 中启用本插件，作用域选择“小米系统服务”。\n\n"
                + "首次启用后需要重新启动小米系统服务或重启手机；之后切换开关，"
                + "再次点击天气定位即可。\n\n"
                + "使用系统服务内置的高德定位。定位能否成功仍取决于权限、网络和服务状态。");
        content.addView(note);

        TextView status = new TextView(this);
        status.setPadding(0, 28, 0, 0);
        status.setText(isModuleActive() ? "插件已加载；请打开天气测试定位。"
                : "插件未在此界面加载；请核对 LSPosed 中的小米系统服务作用域。");
        content.addView(status);

        scroll.addView(content);
        setContentView(scroll);
    }
}
