package local.pudding.eulocalization;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.*;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Collections;

/** Fresh APK metadata is compared with PackageManager before offering any cache mutation. */
public final class CacheRepairActivity extends Activity {
    private static final String[] PACKAGES = {"com.android.calendar", "com.android.soundrecorder",
        "com.android.thememanager", "com.mfashiongallery.emag", "com.miui.aod",
        "com.miui.contentcatcher", "com.miui.mediaeditor", "com.miui.personalassistant",
        "com.miui.screenrecorder", "com.miui.weather2", "com.xiaomi.payment", "com.android.mms"};
    private LinearLayout content;
    private TextView result;
    private Button scan;
    private Button batch;
    private final List<Button> actions = new ArrayList<>();
    private final List<CacheRepairBatch.Target> confirmed = new ArrayList<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); UiLayout.padding(content, 16, 16, 16, 16);
        TextView text = new TextView(this);
        text.setText("应用名称与包扫描诊断\n先读取当前 APK 与系统记录，仅对确认不一致且能精确对应的缓存提供修复。需要 root。手动更新和不支持的路径跳过。此页不处理应用私有数据，不提供相册及便签清理。修复后需完整重启，重扫可能暴露签名或共享 UID 冲突。");
        content.addView(text);
        scan = new Button(this); scan.setText("只读检查"); scan.setOnClickListener(v -> inspect()); content.addView(scan);
        batch = new Button(this); batch.setText("一键清理已确认异常缓存（先检查）"); batch.setEnabled(false);
        batch.setOnClickListener(v -> confirmRepair(new ArrayList<>(confirmed)));
        content.addView(batch);
        result = new TextView(this); content.addView(result);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); UiLayout.setContent(this, scroll);
    }
    private static String digest(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
    private static boolean mismatch(PackageInfo installed, PackageInfo archive) {
        ApplicationInfo a = installed.applicationInfo, b = archive.applicationInfo;
        return installed.getLongVersionCode() != archive.getLongVersionCode()
                || !Objects.equals(installed.versionName, archive.versionName)
                || a.labelRes != b.labelRes || a.icon != b.icon || a.theme != b.theme
                || !Objects.equals(a.className, b.className)
                || ((a.flags ^ b.flags) & ApplicationInfo.FLAG_HAS_CODE) != 0;
    }
    private CacheRepairPlan check(String pkg) throws Exception {
        PackageInfo installed = getPackageManager().getPackageInfo(pkg, 0);
        String path = installed.applicationInfo.sourceDir;
        if (path.startsWith("/data/")) throw new IllegalStateException("手动更新保留，跳过");
        if (installed.applicationInfo.splitSourceDirs != null && installed.applicationInfo.splitSourceDirs.length > 0)
            throw new IllegalStateException("split APK，仅诊断");
        String hash = digest(path);
        PackageInfo archive = getPackageManager().getPackageArchiveInfo(path, 0);
        if (archive == null || !pkg.equals(archive.packageName) || archive.applicationInfo == null)
            throw new IllegalStateException("无法独立读取 APK 元数据");
        if (!hash.equals(digest(path))) throw new IllegalStateException("APK 正在变化，请稍后重查");
        if (!mismatch(installed, archive)) throw new IllegalStateException("所检查的版本及资源元数据一致，无修复建议");
        return new CacheRepairPlan(pkg, path, hash);
    }
    private void inspect() {
        for (Button b : actions) content.removeView(b); actions.clear();
        confirmed.clear(); batch.setEnabled(false); batch.setText("一键清理已确认异常缓存（检查中）");
        scan.setEnabled(false); result.setText("正在只读核对…\n");
        new Thread(() -> {
            for (String pkg : PACKAGES) {
                try {
                    CacheRepairPlan plan = check(pkg);
                    String output = RootCommand.run(plan.preview());
                    List<String[]> targets = new ArrayList<>();
                    for (String line : output.split("\n")) {
                        String[] fields = line.split("\\|");
                        if (fields.length == 3 && fields[0].equals("CANDIDATE")) {
                            plan.repair(fields[1], fields[2]); // Validate output before exposing an action.
                            targets.add(fields);
                        }
                    }
                    runOnUiThread(() -> {
                        result.append(pkg + "：元数据不一致；精确缓存 " + targets.size() + " 项\n");
                        for (String[] target : targets) addRepair(plan, target[1], target[2]);
                    });
                } catch (Exception e) { runOnUiThread(() -> result.append(pkg + "：" + e.getMessage() + "\n")); }
            }
            runOnUiThread(() -> {
                scan.setEnabled(true); for (Button b : actions) b.setEnabled(true);
                batch.setText("一键备份并清理已确认异常缓存（" + confirmed.size() + " 项）");
                batch.setEnabled(!confirmed.isEmpty());
            });
        }, "pudding-cache-inspect").start();
    }
    private void addRepair(CacheRepairPlan plan, String path, String hash) {
        CacheRepairBatch.Target target = new CacheRepairBatch.Target(plan, path, hash);
        confirmed.add(target);
        Button button = new Button(this); button.setText("备份并修复：" + plan.pkg);
        button.setEnabled(false);
        button.setOnClickListener(v -> confirmRepair(Collections.singletonList(target)));
        actions.add(button); content.addView(button);
    }
    private void confirmRepair(List<CacheRepairBatch.Target> targets) {
        if (targets.isEmpty()) return;
        StringBuilder summary = new StringBuilder();
        for (CacheRepairBatch.Target target : targets) {
            summary.append(target.plan.pkg).append('\n').append(target.path)
                    .append("\nSHA256：").append(target.sha).append("\n\n");
        }
        new AlertDialog.Builder(this).setTitle("确认清理 " + targets.size() + " 项异常缓存？")
                .setMessage(summary + "逐项复核、备份后移除这些包扫描缓存；应用数据保留。出现变化或失败立即停止。随后需完整重启。")
                .setNegativeButton("取消", null).setPositiveButton("一键备份并清理", (d, w) -> {
                    scan.setEnabled(false); batch.setEnabled(false); for (Button b : actions) b.setEnabled(false);
                    new Thread(() -> {
                        String output = CacheRepairBatch.run(targets, new CacheRepairBatch.Operations() {
                            @Override public void verify(CacheRepairBatch.Target target) throws Exception {
                                CacheRepairPlan current = check(target.plan.pkg);
                                if (!current.apk.equals(target.plan.apk) || !current.apkSha.equals(target.plan.apkSha))
                                    throw new IllegalStateException("应用状态已改变，请重新检查");
                                RootCommand.run(target.plan.verifyTarget(target.path, target.sha));
                            }
                            @Override public String repair(CacheRepairBatch.Target target) throws Exception {
                                CacheRepairPlan current = check(target.plan.pkg);
                                if (!current.apk.equals(target.plan.apk) || !current.apkSha.equals(target.plan.apkSha))
                                    throw new IllegalStateException("应用状态已改变，请重新检查");
                                return RootCommand.run(target.plan.repair(target.path, target.sha));
                            }
                        });
                        runOnUiThread(() -> {
                            result.append(output + "\n"); confirmed.clear();
                            batch.setText("一键清理已确认异常缓存（请重新检查）"); scan.setEnabled(true);
                        });
                    }, "pudding-cache-batch").start();
                }).show();
    }
}
