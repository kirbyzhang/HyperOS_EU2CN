package local.pudding.weathercompat;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import local.pudding.eulocalization.MaintenanceActivity;
import local.pudding.eulocalization.CacheRepairActivity;
import local.pudding.eulocalization.UiLayout;

public final class MainActivity extends Activity {
    public static boolean isModuleActive() { return false; }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences preferences = getSharedPreferences(CompatConfig.PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        UiLayout.padding(content, 16, 24, 16, 16);
        text(content, "HyperOS_EU2CN 兼容层", 22);
        text(content, "版本：" + appVersion(), 14);
        text(content, "配合本地化模块，管理国行功能兼容开关并查看运行状态。", 16);
        text(content, "后台挂载例外：在 LSPosed 推荐作用域中勾选 KernelSU，然后从模块“操作”按音量+运行，无需保持亮屏。", 14);
        text(content, isModuleActive() ? "兼容层已在管理界面加载"
                : "请在 LSPosed 中启用本兼容层并选择推荐作用域", 16);
        text(content, "模块安装时选择的功能：下方显示当前状态。要调整，请修改安装配置、重新安装模块并完整重启。", 16);
        status(content, "双开推荐应用兼容", "xspace");
        status(content, "双开国际环境兼容", "xspace_safety");
        status(content, "FCM 与后台国际环境兼容（实验性）", "fcm");
        status(content, "Google 桌面入口兼容", "google_home");
        text(content, "Google 桌面入口兼容只保留 Google 助手和搜索入口，不会安装国行系统搜索。", 14);
        status(content, "小米原生桌面小组件", "widgets");
        status(content, "国际版通知小图标策略", "systemui_icons");
        status(content, "通知过滤（保留原通知配置）", "notification_filter");
        status(content, "旧版国际环境兼容（实验性）", "international");
        text(content, "应用兼容开关：修改后重新打开对应应用；涉及电话时请完整重启手机。", 16);
        toggle(content, preferences, "桌面上滑使用国行搜索", "cn_search");
        text(content, "需在本地化模块安装时选择国行搜索，并在桌面设置中把上滑设为搜索。可与 Google 负一屏兼容同时开启；关闭本项后恢复桌面原有的搜索服务选择。", 14);
        toggle(content, preferences, "天气国行地区兼容", "weather_cn");
        toggle(content, preferences, "主题国行地区兼容", "theme_cn");
        toggle(content, preferences, "黄页国行地区兼容", "yellowpage_cn");
        toggle(content, preferences, "电话国行地区兼容", "phone_cn");
        toggle(content, preferences, "电话启用黄页判断", "phone_yellowpage");
        toggle(content, preferences, "短信版本地区匹配", "mms_region");
        text(content, "以下为实验性地区适配，默认关闭。是否提供完整功能取决于已安装应用、机型和服务支持。", 16);
        toggle(content, preferences, "设置国行地区兼容", "settings_cn", false);
        toggle(content, preferences, "手机管家国行地区兼容", "security_cn", false);
        toggle(content, preferences, "下载管理国行地区兼容", "downloads_cn", false);
        toggle(content, preferences, "拨号与联系人国行地区兼容", "contacts_cn", false);
        toggle(content, preferences, "MIUI+ 国行地区兼容", "mirror_cn", false);
        text(content, "首次启用推荐作用域后需重启手机。", 16);
        Button runtime = new Button(this);
        runtime.setText("检查兼容层是否在运行");
        runtime.setOnClickListener(view -> startActivity(new Intent(this, RuntimeStatusActivity.class)));
        content.addView(runtime);
        Button cache = new Button(this);
        cache.setText("应用名称检查与异常缓存一键清理");
        cache.setOnClickListener(view -> startActivity(new Intent(this, CacheRepairActivity.class)));
        content.addView(cache);
        Button maintenance = new Button(this);
        maintenance.setText("应用维护（自行选择清理项）");
        maintenance.setOnClickListener(view -> startActivity(new Intent(this, MaintenanceActivity.class)));
        content.addView(maintenance);
        text(content, "卸载说明：卸载时备份并移除本模块相关的系统包解析缓存，让下次完整重启重新识别原系统应用；不会清理应用数据、相册收藏或手动更新。恢复成功后移除后台恢复入口和运行脚本，备份与日志归档保留。另行安装的 APK 仍保留。手动调整的 Hybrid Mount / Zygisk Next 设置不会自动回滚。", 14);
        text(content, "Zygisk Next 可以使用“仅还原挂载”，前提是相关系统应用在 KernelSU App Profile 中关闭“卸载模块”。否则它们可能读不到本地化 APK，出现资源错误。不要为此给应用授予 Root。", 14);
        scroll.addView(content);
        UiLayout.setContent(this, scroll);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "未能读取"; }
    }

    private void text(LinearLayout parent, String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 16, 0, 16);
        parent.addView(view);
    }

    private void toggle(LinearLayout parent, SharedPreferences prefs, String label, String key) {
        toggle(parent, prefs, label, key, true);
    }

    private void toggle(LinearLayout parent, SharedPreferences prefs, String label, String key, boolean defaultValue) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(17);
        box.setChecked(prefs.getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        parent.addView(box);
    }

    private void status(LinearLayout parent, String label, String suffix) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(17);
        box.setEnabled(false);
        try {
            Process process = new ProcessBuilder("/system/bin/getprop", "ro.pudding.eu.hook." + suffix).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String value = reader.readLine();
            reader.close();
            process.destroy();
            box.setChecked("1".equals(value) || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
        } catch (Throwable ignored) {
            box.setChecked(false);
        }
        parent.addView(box);
    }
}
