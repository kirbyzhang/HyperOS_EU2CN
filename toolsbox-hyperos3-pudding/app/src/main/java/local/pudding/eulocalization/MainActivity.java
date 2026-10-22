package local.pudding.eulocalization;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class MainActivity extends Activity {
    public static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        UiLayout.padding(content, 16, 24, 16, 16);

        TextView title = new TextView(this);
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setText(isModuleActive() ? "LSPosed 模块已激活" : "LSPosed 模块尚未激活");
        content.addView(title);

        TextView note = new TextView(this);
        note.setTextSize(16f);
        note.setPadding(0, 24, 0, 24);
        note.setText("Xiaomi 17 pudding / OS3.0.319.0\n"
                + "以下开关由本地化模块安装配置决定；修改选择后重新刷入模块并重启。");
        content.addView(note);

        addStatus(content, "双开推荐应用兼容", "xspace");
        addStatus(content, "小米原生桌面小组件", "widgets");
        addStatus(content, "国际版通知小图标策略", "systemui_icons");
        addStatus(content, "全作用域国际标志（实验性）", "international");

        Button maintenance = new Button(this);
        maintenance.setText("手动清理系统缓存与应用数据");
        maintenance.setOnClickListener(view -> startActivity(new Intent(this, MaintenanceActivity.class)));
        content.addView(maintenance);

        if (!isModuleActive()) {
            TextView help = new TextView(this);
            help.setTextSize(16f);
            help.setPadding(0, 28, 0, 0);
            help.setText("请在 LSPosed 中启用本模块，采用推荐作用域，然后重启手机。");
            content.addView(help);
        }

        scroll.addView(content);
        UiLayout.setContent(this, scroll);
    }

    private void addStatus(LinearLayout parent, String label, String propertySuffix) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(17f);
        box.setChecked(readBooleanProperty("ro.pudding.eu.hook." + propertySuffix));
        box.setEnabled(false);
        parent.addView(box);
    }

    private static boolean readBooleanProperty(String name) {
        try {
            Process process = new ProcessBuilder("/system/bin/getprop", name).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            reader.close();
            process.destroy();
            return "1".equals(value) || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
