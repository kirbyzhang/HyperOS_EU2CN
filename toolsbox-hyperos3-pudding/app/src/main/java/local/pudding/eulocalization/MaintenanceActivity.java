package local.pudding.eulocalization;

import android.app.Activity;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MaintenanceActivity extends Activity {
    private static final String[][] PACKAGES = {
            {"短信应用设置", "com.android.mms"},
            {"ContentCatcher", "com.miui.contentcatcher"},
            {"负一屏布局", "com.miui.personalassistant"},
            {"日历", "com.android.calendar"},
            {"天气", "com.miui.weather2"},
            {"音乐", "com.miui.player"},
            {"录音机", "com.android.soundrecorder"},
            {"MediaEditor", "com.miui.mediaeditor"},
            {"主题管理器", "com.android.thememanager"},
            {"锁屏画报", "com.mfashiongallery.emag"},
            {"XMSF（推送注册将重建）", "com.xiaomi.xmsf"}
    };

    private final List<CheckBox> packageBoxes = new ArrayList<>();
    private Button executeButton;
    private TextView resultView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        UiLayout.padding(content, 16, 16, 16, 16);

        TextView title = new TextView(this);
        title.setText("手动清理");
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("默认不选择任何项目。应用数据重置使用系统 pm clear，仅作用于用户 0；清理前请备份。相册和便签均不提供应用数据清理选项。此页面需要 KernelSU 授予 root 权限，LSPosed 未激活也可使用。");
        explanation.setTextSize(15f);
        explanation.setPadding(0, 16, 0, 24);
        content.addView(explanation);

        Button cacheDiagnosis = new Button(this);
        cacheDiagnosis.setText("应用名称与包扫描诊断（先只读核对）");
        cacheDiagnosis.setOnClickListener(v -> startActivity(new Intent(this, CacheRepairActivity.class)));
        content.addView(cacheDiagnosis);

        for (String[] item : PACKAGES) {
            CheckBox box = new CheckBox(this);
            box.setText(item[0] + " · " + item[1]);
            box.setTextSize(16f);
            packageBoxes.add(box);
            content.addView(box);
        }

        executeButton = new Button(this);
        executeButton.setText("检查选择并执行");
        executeButton.setOnClickListener(view -> confirmSelection());
        content.addView(executeButton);

        resultView = new TextView(this);
        resultView.setTextSize(14f);
        resultView.setPadding(0, 20, 0, 0);
        content.addView(resultView);

        scroll.addView(content);
        UiLayout.setContent(this, scroll);
    }

    private void confirmSelection() {
        List<String> packages = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < PACKAGES.length; i++) {
            if (packageBoxes.get(i).isChecked()) {
                packages.add(PACKAGES[i][1]);
                summary.append("• ").append(PACKAGES[i][0]).append("\n");
            }
        }
        if (summary.length() == 0) {
            resultView.setText("请先选择需要清理的项目。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认清理所选数据？")
                .setMessage(summary + "\n应用私有数据清理后无法恢复。此操作不修复系统包扫描缓存。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清理", (dialog, which) -> executeSelection(packages))
                .show();
    }

    private void executeSelection(List<String> packages) {
        executeButton.setEnabled(false);
        resultView.setText("正在请求 root 并清理所选项目…");
        new Thread(() -> {
            String output;
            try {
                output = runRootCommand(packages);
            } catch (Exception error) {
                output = "执行失败：" + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            final String finalOutput = output;
            runOnUiThread(() -> {
                resultView.setText(finalOutput);
                executeButton.setEnabled(true);
            });
        }, "pudding-manual-cleanup").start();
    }

    private static String runRootCommand(List<String> packages) throws Exception {
        StringBuilder command = new StringBuilder("failed=0\n");
        for (String packageName : packages) {
            // Package names only come from the fixed list above; no user text is executed.
            command.append("pm_result=$(/system/bin/pm clear --user 0 ").append(packageName)
                    .append(" 2>&1)\npm_status=$?\ncase \"$pm_status:$pm_result\" in 0:*Success*) echo OK:")
                    .append(packageName).append(" ;; *) echo FAIL:")
                    .append(packageName).append(" - \"$pm_result\"; failed=1 ;; esac\n");
        }
        command.append("exit \"$failed\"\n");
        return RootCommand.run(command.toString());
    }
}
