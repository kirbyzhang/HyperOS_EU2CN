package local.pudding.weathercompat;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import local.pudding.eulocalization.RootCommand;
import local.pudding.eulocalization.UiLayout;

public final class RuntimeStatusActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); UiLayout.padding(layout, 16, 16, 16, 16);
        TextView title = new TextView(this); title.setText("兼容层运行检查"); title.setTextSize(22);
        layout.addView(title);
        TextView result = new TextView(this);
        result.setTextSize(16);
        result.setText("检查兼容层是否有加载记录，以及最近的启动恢复任务是否完成。需要 root；不会自动打开应用、重启或清理数据。\n\n这里不判断相册、便签等应用的功能是否正常。先正常打开你要检查的应用，再点击下面的按钮。\n");
        TextView detail = new TextView(this); detail.setTextSize(13); detail.setTextIsSelectable(true); detail.setVisibility(android.view.View.GONE);
        Button showDetail = new Button(this); showDetail.setText("查看详细记录（排查用）"); showDetail.setEnabled(false);
        showDetail.setOnClickListener(v -> {
            boolean show = detail.getVisibility() != android.view.View.VISIBLE;
            detail.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            showDetail.setText(show ? "收起详细记录" : "查看详细记录（排查用）");
        });
        Button read = new Button(this); read.setText("开始检查（只读）");
        read.setOnClickListener(v -> {
            read.setEnabled(false); showDetail.setEnabled(false); result.setText("正在检查，请稍候…");
            new Thread(() -> {
                String report, records;
                boolean success;
                try {
                    String input = RootCommand.run(script());
                    report = EvidenceReport.summarize(input, local.pudding.eulocalization.HookEvidence.VERSION); records = EvidenceReport.render(input); success = true;
                } catch (Exception e) {
                    report = "未能完成检查。请确认本应用已获得 root 授权，然后重试。\n此次无法判断兼容层状态，未执行任何修复或清理。";
                    records = "读取错误：" + e.getMessage(); success = false;
                }
                String value = report, raw = records;
                boolean ok = success;
                runOnUiThread(() -> {
                    result.setText(value); detail.setText(raw); detail.setVisibility(android.view.View.GONE);
                    showDetail.setText("查看详细记录（排查用）"); showDetail.setEnabled(ok || !raw.isEmpty()); read.setEnabled(true);
                });
            }, "pudding-runtime-diagnosis").start();
        });
        layout.addView(read); layout.addView(result); layout.addView(showDetail); layout.addView(detail);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); UiLayout.setContent(this, scroll);
    }
    static String script() {
        return "set -eu\nps -A -o PID,NAME | while read -r pid name; do\n"
            + "case \"${name%%:*}\" in system_server|com.android.systemui|com.android.settings|com.android.mms|com.android.phone|com.android.contacts|com.android.providers.downloads|com.android.providers.downloads.ui|com.android.thememanager|com.miui.themestore|com.miui.core|com.miui.securitycore|com.miui.home|com.miui.powerkeeper|com.miui.weather2|com.miui.yellowpage|com.miui.notification|com.miui.securitycenter|com.xiaomi.mirror|local.pudding.weathercompat) ;; *) continue;; esac\n"
            + "case \"$pid\" in ''|*[!0-9]*) continue;; esac\n"
            + "start=$(sed 's/.*) //' /proc/\"$pid\"/stat 2>/dev/null | awk '{print $20}')\n"
            + "if [ -n \"$start\" ]; then printf 'LIVE|%s|%s|%s\\n' \"$pid\" \"$start\" \"$name\"; fi\ndone\n"
            + "logcat -d -b main -v threadtime -s PuddingEUState:I '*:S' | tail -n 1000\n"
            + "echo STATE_BEGIN\n"
            + "if [ -f /data/adb/pudding-eu-localization-state/status ]; then cat /data/adb/pudding-eu-localization-state/status; else echo '尚无启动状态记录'; fi\n"
            + "if [ -f /data/adb/pudding-eu-localization-state/lifecycle.log ]; then tail -n 24 /data/adb/pudding-eu-localization-state/lifecycle.log; fi\n";
    }
}
