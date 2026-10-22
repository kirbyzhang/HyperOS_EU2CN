package local.pudding.ksu;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs as a root app_process child. Writes profiles ONLY through the official UI. */
public final class KsuMountHelper {
    private static final String MANAGER = "me.weishu.kernelsu";
    private static final String MANAGER_SHA = "c197060ecb89702e7d54a4c95e29cf5e8d97369bbbb436979ab7fd6bcde7b077";
    private static final File PROFILES = new File("/data/adb/ksu/.allowlist");
    private UiAutomation ui;
    private HandlerThread thread;
    private File audit;
    private String boot;
    private Map<String, Integer> installed;
    private Map<String, Integer> rowIdentities;
    private Map<Integer, Set<String>> targets;
    private final List<Integer> completed = new ArrayList<>();
    private Integer editing;
    private boolean profileEditing;

    public static void main(String[] args) {
        int status = 1;
        KsuMountHelper helper = new KsuMountHelper();
        try {
            MountPolicy.require(args.length == 3 && (args[0].equals("--plan") || args[0].equals("--apply") || args[0].equals("--probe-ui")), "参数无效");
            File module = new File(args[1]);
            helper.audit = new File(args[2]);
            MountPolicy.require(module.getCanonicalPath().equals("/data/adb/modules/HyperOS_EU2CN"), "仅支持活动模块目录");
            MountPolicy.require(helper.audit.getCanonicalPath().startsWith("/data/adb/pudding-eu-localization-state/ksu-mount-actions/"), "日志路径无效");
            MountPolicy.require(helper.audit.isDirectory(), "缺少日志目录");
            File lockPath = new File(helper.audit.getParentFile(), "helper.lock");
            MountPolicy.require(!Files.isSymbolicLink(lockPath.toPath()), "锁文件是链接");
            try (FileChannel channel = new FileOutputStream(lockPath, true).getChannel(); FileLock lock = channel.tryLock()) {
                MountPolicy.require(lock != null, "已有批处理正在运行");
                helper.run(module, args[0]);
                status = 0;
            }
        } catch (Throwable error) {
            System.out.println("停止：" + error.getMessage());
            error.printStackTrace(System.out);
            if (helper.editing != null) System.out.println("当前 UID " + helper.editing + " 可能已保存，请核对本次配置快照和管理器页面。");
            System.out.println("已完成 UID：" + helper.completed + "。未自动回滚；已保存项保持，剩余项尚未处理。");
        } finally {
            if (helper.ui != null) try { UiAutomation.class.getDeclaredMethod("disconnect").invoke(helper.ui); } catch (Exception ignored) {}
            if (helper.thread != null) helper.thread.quitSafely();
        }
        System.exit(status);
    }

    private void run(File module, String mode) throws Exception {
        MountPolicy.require(command("/system/bin/id", "-u").trim().equals("0"), "需要模块管理器以 Root 运行");
        MountPolicy.require(Build.VERSION.SDK_INT >= 33, "界面缓存刷新需要 Android 13 或更新系统");
        MountPolicy.require(command("/system/bin/am", "get-current-user").trim().equals("0"), "仅支持用户 0");
        MountPolicy.require(command("/data/adb/ksud", "--version").trim().equals("ksud 3.3.0 (uapi: 2)"), "未验证的 KernelSU 版本");
        String info = command("/data/adb/ksud", "debug", "info");
        MountPolicy.require(info.contains("version: 32601\n") && info.contains("uapi_version: 2"), "未验证的内核接口");
        String path = command("/system/bin/pm", "path", MANAGER).trim();
        MountPolicy.require(path.startsWith("package:/data/app/") && !path.contains("\n"), "无法确认 KernelSU 管理器");
        MountPolicy.require(sha(Files.readAllBytes(new File(path.substring(8)).toPath())).equals(MANAGER_SHA), "管理器版本不同，停止自动操作");
        boot = read(new File("/proc/sys/kernel/random/boot_id")).trim();
        installed = packages();
        rowIdentities = new LinkedHashMap<>(installed);
        Set<String> selected = TargetPlan.selectedPackages(module);
        Set<String> code = new LinkedHashSet<>();
        for (String name : selected) {
            if (!installed.containsKey(name)) continue;
            String dump = command("/system/bin/dumpsys", "package", name);
            TargetPlan.addSharedIdentity(rowIdentities, dump, installed.get(name));
            Matcher flags = Pattern.compile("(?m)^\\s*flags=\\[([^]]*)]").matcher(dump);
            MountPolicy.require(flags.find(), "无法确认包标志：" + name);
            if (Arrays.asList(flags.group(1).trim().split("\\s+")).contains("HAS_CODE")) code.add(name);
            else System.out.println("原生壳/RRO，不在此管理器列表设置：" + name);
        }
        targets = TargetPlan.group(selected, installed, code);
        byte[] raw = Files.readAllBytes(PROFILES.toPath());
        Map<Integer, byte[]> baseline = MountPolicy.decode(raw);
        saveNew("profiles-before.bin", raw);
        int count = 0;
        StringBuilder plan = new StringBuilder();
        for (Map.Entry<Integer, Set<String>> item : targets.entrySet()) {
            boolean needed = MountPolicy.needsException(baseline, item.getKey());
            if (needed) count++;
            plan.append(needed ? "待补齐 " : "保留 ").append(item.getKey()).append(' ').append(item.getValue()).append('\n');
        }
        saveNew("plan.txt", plan.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(plan);
        System.out.println("待处理 " + count + " 个 UID 组；不授予 Root，不改变默认卸载开关。");
        if (mode.equals("--plan")) return;
        MountPolicy.require(command("/system/bin/wm", "size").contains("Physical size: 1220x2656"), "未验证的屏幕尺寸");
        unlocked();
        connect();
        if (mode.equals("--probe-ui")) {
            List<AccessibilityNodeInfo> probe = waitForAppList();
            if (!isAppList(probe)) describe(probe);
            MountPolicy.require(isAppList(probe), "请将 KernelSU Material 超级用户列表置于前台");
            System.out.println("UI_PROBE_OK nodes=" + probe.size());
            return;
        }
        if (count == 0) return;
        command("/system/bin/am", "start", "-n", MANAGER + "/.ui.MainActivity");
        Thread.sleep(900);
        List<AccessibilityNodeInfo> nodes = waitForAppList();
        if (!isAppList(nodes)) {
            // A profile page also has a "超级用户" grant switch. It must NEVER
            // be treated as navigation. Only the pinned bottom tab is valid.
            for (AccessibilityNodeInfo n : nodes) MountPolicy.require(!text(n).matches("UID [0-9]+"), "请先返回超级用户应用列表，不能从应用配置页继续");
            List<AccessibilityNodeInfo> tabs = new ArrayList<>();
            for (AccessibilityNodeInfo n : nodes) if (UiText.superuserTab(n.getText(), bounds(n).top, bounds(n).bottom)) tabs.add(n);
            MountPolicy.require(tabs.size() == 1, "底部超级用户导航不可识别，请先手动打开应用列表");
            AccessibilityNodeInfo tab = tabs.get(0);
            while (tab != null && !tab.isClickable()) tab = tab.getParent();
            MountPolicy.require(tab != null && bounds(tab).top >= 2396, "导航位置不符合已验证布局");
            click(tab);
            Thread.sleep(500);
            nodes = waitForAppList();
        }
        MountPolicy.require(isAppList(nodes), "请切换为 Material 样式并打开超级用户应用列表，再从模块重试");
        // Rewind first: a previous partial batch may have left the list near its end.
        String previous = "";
        for (int i = 0; i < 140; i++) {
            nodes = snapshot();
            String signature = signature(nodes);
            if (signature.equals(previous)) break;
            previous = signature;
            if (!scroll(nodes, false)) break;
            Thread.sleep(160);
        }
        Map<Integer, byte[]> latest = baseline;
        Set<String> inspectedGroups = new LinkedHashSet<>();
        previous = "";
        int stagnant = 0;
        for (int step = 0; step < 240; step++) {
            Map<Integer, byte[]> current = MountPolicy.decode(Files.readAllBytes(PROFILES.toPath()));
            sameProfiles(latest, current);
            Set<Integer> pending = new LinkedHashSet<>();
            for (Integer uid : targets.keySet()) if (MountPolicy.needsException(current, uid)) pending.add(uid);
            if (pending.isEmpty()) {
                saveNew("completed.txt", ("已完成 UID=" + completed + "\n需完整重启验证挂载。\n").getBytes(StandardCharsets.UTF_8));
                System.out.println("完成：" + completed + "；请使用系统电源菜单完整重启。LSPosed 启用与作用域需单独核对。");
                return;
            }
            nodes = snapshot();
            MountPolicy.require(isAppList(nodes), "应用列表离开前台");
            AccessibilityNodeInfo candidate = null;
            Integer targetUid = null;
            String sharedGroup = null;
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = bounds(node);
                if (!node.isClickable() || bounds.width() < 1000 || bounds.top < 540 || bounds.bottom > 2396 || bounds.height() < 150 || bounds.height() > 500) continue;
                Set<String> texts = new LinkedHashSet<>();
                for (AccessibilityNodeInfo child : flatten(node)) texts.add(text(child));
                boolean otherUser = false;
                for (String label : texts) if (label.matches("USER [1-9][0-9]*")) otherUser = true;
                if (otherUser) continue;
                Integer matched = TargetPlan.visibleRowUid(texts, rowIdentities, pending);
                if (matched != null) { candidate = node; targetUid = matched; break; }
            }
            if (candidate == null) for (AccessibilityNodeInfo node : nodes) {
                Rect r = bounds(node);
                if (!node.isClickable() || r.width() < 1000 || r.top < 540 || r.bottom > 2396 || r.height() < 150 || r.height() > 500) continue;
                String group = null, title = null;
                boolean otherUser = false;
                for (AccessibilityNodeInfo child : flatten(node)) {
                    String label = text(child);
                    if (UiText.sharedGroup(label)) group = label;
                    if (label.matches("USER [1-9][0-9]*")) otherUser = true;
                    if (title == null && !label.isEmpty()) title = label;
                }
                if (otherUser || group == null || !inspectedGroups.add(title + "\n" + group)) continue;
                candidate = node;
                sharedGroup = group;
                break;
            }
            if (candidate != null) {
                verifyPackages();
                sameProfiles(latest, MountPolicy.decode(Files.readAllBytes(PROFILES.toPath())));
                editing = targetUid;
                System.out.println("定位 UID " + targetUid + " bounds=" + bounds(candidate));
                if (sharedGroup == null) click(candidate);
                else {
                    // Group headers omit package/UID identity. Opening their
                    // profile is read-only; inspect the UID before any edit.
                    unlocked();
                    MountPolicy.require(ui.clearCache() && candidate.refresh(), "共享组节点已失效");
                    AccessibilityNodeInfo live = ui.getRootInActiveWindow();
                    MountPolicy.require(live != null && UiText.matches(MANAGER, live.getPackageName()) && isAppList(flatten(live)), "共享组列表离开前台");
                    MountPolicy.require(hasText(flatten(candidate), sharedGroup) && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK), "无法查看共享 UID 组");
                }
                for (int attempt = 0; attempt < 16; attempt++) {
                    Thread.sleep(150);
                    nodes = snapshot();
                    boolean uidVisible = false;
                    for (AccessibilityNodeInfo n : nodes) if (text(n).matches("UID [0-9]+")) uidVisible = true;
                    if (uidVisible) break;
                }
                // Compose may expose both outgoing and incoming profile trees
                // during navigation. Never inspect controls in that frame.
                ui.waitForIdle(200, 2500);
                Thread.sleep(300);
                nodes = snapshot();
                if (sharedGroup != null) {
                    Set<Integer> visibleUids = new LinkedHashSet<>();
                    for (AccessibilityNodeInfo node : nodes) if (text(node).matches("UID [0-9]+")) visibleUids.add(Integer.parseInt(text(node).substring(4)));
                    MountPolicy.require(visibleUids.size() == 1, "共享组 UID 不明确");
                    targetUid = visibleUids.iterator().next();
                    if (!pending.contains(targetUid)) {
                        sameProfiles(latest, MountPolicy.decode(Files.readAllBytes(PROFILES.toPath())));
                        System.out.println("只读跳过共享 UID " + targetUid);
                        command("/system/bin/input", "keyevent", "4");
                        previous = ""; stagnant = 0;
                        continue;
                    }
                    boolean identity = false;
                    for (AccessibilityNodeInfo node : nodes) if (targetUid.equals(rowIdentities.get(text(node)))) identity = true;
                    MountPolicy.require(identity, "共享组包身份不明确");
                    editing = targetUid;
                }
                MountPolicy.require(hasText(nodes, "UID " + targetUid), "应用配置 UID 不匹配");
                profileEditing = true;
                MountPolicy.require(!switchOn(nodes, "超级用户"), "已有 Root 授权，停止处理");
                AccessibilityNodeInfo custom = radio(nodes, "自定义");
                if (!custom.isChecked()) { click(custom); Thread.sleep(250); nodes = snapshot(); }
                MountPolicy.require(radio(nodes, "自定义").isChecked(), "未进入自定义模式");
                if (switchOn(nodes, "卸载模块")) { click(clickable(nodes, "卸载模块")); Thread.sleep(250); nodes = snapshot(); }
                MountPolicy.require(!switchOn(nodes, "卸载模块") && !switchOn(nodes, "超级用户"), "界面状态未确认");
                MountPolicy.require(hasText(nodes, "UID " + targetUid), "保存前 UID 变化");
                verifyPackages();
                MountPolicy.verifyPendingSave(latest, MountPolicy.decode(Files.readAllBytes(PROFILES.toPath())), targetUid);
                nodes = snapshot();
                MountPolicy.require(hasText(nodes, "UID " + targetUid) && !switchOn(nodes, "超级用户")
                    && radio(nodes, "自定义").isChecked() && !switchOn(nodes, "卸载模块"), "保存前界面状态变化");
                command("/system/bin/input", "keyevent", "4");
                profileEditing = false;
                byte[] changed = null;
                Map<Integer, byte[]> after = null;
                for (int attempt = 0; attempt < 8; attempt++) {
                    Thread.sleep(250);
                    snapshot();
                    changed = Files.readAllBytes(PROFILES.toPath());
                    after = MountPolicy.decode(changed);
                    byte[] row = after.get(targetUid);
                    if (row != null && row[264] == 0 && row[272] == 0 && row[273] == 0) break;
                }
                MountPolicy.verifyDelta(latest, after, targetUid);
                completed.add(targetUid);
                saveNew("profiles-after-" + targetUid + ".bin", changed);
                latest = after;
                editing = null;
                System.out.println("已保存 UID " + targetUid + " " + targets.get(targetUid));
                previous = ""; stagnant = 0;
                continue;
            }
            String signature = signature(nodes);
            stagnant = signature.equals(previous) ? stagnant + 1 : 0;
            previous = signature;
            MountPolicy.require(stagnant < 2, "列表到底，未找到 UID：" + pending);
            scroll(nodes, true);
            Thread.sleep(450);
        }
        throw new IllegalStateException("超过操作次数限制，停止");
    }

    private void verifyPackages() throws Exception {
        Map<String, Integer> now = packages();
        for (Set<String> group : targets.values()) for (String name : group) {
            MountPolicy.require(installed.get(name).equals(now.get(name)), "包 UID 已改变：" + name);
        }
    }

    private static Map<String, Integer> packages() throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        String output = command("/system/bin/pm", "list", "packages", "-U", "--user", "0");
        for (String line : output.split("\n")) {
            Matcher m = Pattern.compile("^package:(\\S+) uid:(\\d+)$").matcher(line.trim());
            MountPolicy.require(m.matches(), "包 UID 列表不可识别");
            result.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        MountPolicy.require(!result.isEmpty(), "包列表为空");
        return result;
    }

    private void connect() throws Exception {
        Looper.prepareMainLooper();
        thread = new HandlerThread("pudding-ksu-ui"); thread.start();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        Constructor<?> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, connectionType);
        constructor.setAccessible(true);
        ui = (UiAutomation) constructor.newInstance(thread.getLooper(), connection);
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true); connect.invoke(ui, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    }

    private void unlocked() throws Exception {
        MountPolicy.require(read(new File("/proc/sys/kernel/random/boot_id")).trim().equals(boot), "启动已变化");
        String policy = command("/system/bin/dumpsys", "window", "policy");
        MountPolicy.require(Pattern.compile("(?m)^\\s*showing=false\\s*$").matcher(policy).find(), "请先解锁并保持亮屏");
        MountPolicy.require(command("/system/bin/dumpsys", "power").contains("mWakefulness=Awake"), "屏幕未保持亮起");
    }

    private List<AccessibilityNodeInfo> snapshot() throws Exception {
        unlocked();
        MountPolicy.require(ui.clearCache(), "无法刷新界面缓存");
        List<AccessibilityNodeInfo> nodes = flatten(ui.getRootInActiveWindow());
        MountPolicy.require(!nodes.isEmpty(), "读取界面失败");
        for (AccessibilityNodeInfo node : nodes) MountPolicy.require(UiText.matches(MANAGER, node.getPackageName()), "KernelSU 离开前台");
        return nodes;
    }

    private List<AccessibilityNodeInfo> waitForAppList() throws Exception {
        List<AccessibilityNodeInfo> nodes = null;
        for (int i = 0; i < 12; i++) {
            nodes = snapshot();
            if (isAppList(nodes)) return nodes;
            Thread.sleep(150);
        }
        return nodes;
    }

    private static void describe(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) if (!text(n).isEmpty() || n.getContentDescription() != null || n.isScrollable()) {
            System.out.println("UI text=" + text(n) + " desc=" + n.getContentDescription() + " class=" + n.getClassName() + " bounds=" + bounds(n) + " scroll=" + n.isScrollable());
        }
    }

    private static List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes); return nodes;
    }
    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (node == null) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), nodes);
    }
    private static String text(AccessibilityNodeInfo n) { return n.getText() == null ? "" : n.getText().toString(); }
    private static Rect bounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }
    private static boolean hasText(List<AccessibilityNodeInfo> nodes, String label) { for (AccessibilityNodeInfo n : nodes) if (text(n).equals(label)) return true; return false; }
    private boolean isAppList(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) if (text(n).matches("UID [0-9]+")) return false;
        for (AccessibilityNodeInfo n : nodes) if (UiText.matches("搜索", n.getContentDescription())) return true;
        // Some Compose frames omit the toolbar description during scrolling.
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = bounds(n);
            if (!n.isClickable() || r.width() < 1000 || r.top < 540 || r.bottom > 2396 || r.height() < 150) continue;
            for (AccessibilityNodeInfo child : flatten(n)) if (rowIdentities.containsKey(text(child))) return true;
        }
        return false;
    }
    private void click(AccessibilityNodeInfo n) throws Exception {
        unlocked();
        MountPolicy.require(ui.clearCache(), "无法刷新界面缓存");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        MountPolicy.require(root != null && UiText.matches(MANAGER, root.getPackageName()), "KernelSU 离开前台");
        MountPolicy.require(n.refresh(), "目标界面节点已失效");
        if (profileEditing) {
            List<AccessibilityNodeInfo> live = flatten(root);
            MountPolicy.require(hasText(live, "UID " + editing) && !switchOn(live, "超级用户"), "操作前应用配置变化");
            List<AccessibilityNodeInfo> control = flatten(n);
            MountPolicy.require(!hasText(control, "超级用户") &&
                ((n.isCheckable() && hasText(control, "自定义")) || hasText(control, "卸载模块")), "禁止操作非目标控制项");
        } else if (editing != null) {
            Set<String> labels = new LinkedHashSet<>();
            for (AccessibilityNodeInfo child : flatten(n)) labels.add(text(child));
            MountPolicy.require(editing.equals(TargetPlan.visibleRowUid(labels, rowIdentities, java.util.Collections.singleton(editing))), "点击前应用行已变化");
        } else {
            MountPolicy.require(bounds(n).top >= 2396 && hasText(flatten(n), "超级用户"), "禁止把应用授权控件当作导航");
        }
        Rect r = bounds(n);
        MountPolicy.require(n.isEnabled() && n.isClickable() && r.width() > 0 && r.height() > 0 && r.left >= 0 && r.top >= 0 && r.right <= 1220 && r.bottom <= 2656, "界面点击位置不可识别");
        MountPolicy.require(n.performAction(AccessibilityNodeInfo.ACTION_CLICK), "目标节点未接受点击");
    }
    private static AccessibilityNodeInfo clickable(List<AccessibilityNodeInfo> nodes, String label) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) if (text(n).equals(label)) found.add(n);
        MountPolicy.require(found.size() == 1, "界面标签不唯一：" + label);
        AccessibilityNodeInfo n = found.get(0);
        while (n != null && !n.isClickable()) n = n.getParent();
        MountPolicy.require(n != null, "标签不可操作：" + label); return n;
    }
    private void clickNamed(List<AccessibilityNodeInfo> nodes, String label) throws Exception { click(clickable(nodes, label)); }
    private static AccessibilityNodeInfo radio(List<AccessibilityNodeInfo> nodes, String label) {
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        for (AccessibilityNodeInfo n : nodes) if (n.isCheckable() && hasText(flatten(n), label)) matches.add(n);
        MountPolicy.require(matches.size() == 1, "单选按钮结构不可识别"); return matches.get(0);
    }
    private static boolean switchOn(List<AccessibilityNodeInfo> nodes, String label) {
        AccessibilityNodeInfo row = clickable(nodes, label);
        List<AccessibilityNodeInfo> tracks = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            AccessibilityNodeInfo n = row.getChild(i);
            if (text(n).isEmpty() && UiText.matches("android.view.View", n.getClassName()) && bounds(n).left > 900 && n.getChildCount() == 1) tracks.add(n);
        }
        MountPolicy.require(tracks.size() == 1, "开关结构不可识别：" + label);
        Rect track = bounds(tracks.get(0)), thumb = bounds(tracks.get(0).getChild(0));
        MountPolicy.require(track.width() > 140 && track.width() < 190 && thumb.width() > 140 && thumb.width() < 190 && track.top == thumb.top && track.bottom == thumb.bottom, "开关布局变化");
        int delta = thumb.centerX() - track.centerX();
        MountPolicy.require(Math.abs(delta) > 15, "开关状态不明确"); return delta > 0;
    }
    private boolean scroll(List<AccessibilityNodeInfo> nodes, boolean forward) throws Exception {
        boolean scrollable = false;
        for (AccessibilityNodeInfo n : nodes) if (n.isScrollable() && bounds(n).width() > 1000 && bounds(n).height() > 500) scrollable = true;
        MountPolicy.require(scrollable && isAppList(nodes), "没有应用列表滚动容器");
        unlocked();
        MountPolicy.require(ui.clearCache(), "无法刷新界面缓存");
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        MountPolicy.require(root != null && UiText.matches(MANAGER, root.getPackageName()) && isAppList(flatten(root)), "应用列表离开前台");
        // Compose exposes both a horizontal pager and its vertical list as
        // scrollable. Use the verified vertical gesture, never scroll the pager.
        command("/system/bin/input", "touchscreen", "swipe", "1000", forward ? "2180" : "750", "1000", forward ? "750" : "2180", "1200");
        ui.waitForIdle(200, 2500);
        return true; // The caller compares visible rows to detect either end.
    }
    private static String signature(List<AccessibilityNodeInfo> nodes) { StringBuilder b = new StringBuilder(); for (AccessibilityNodeInfo n : nodes) if (!text(n).isEmpty()) b.append(text(n)).append(bounds(n)).append('\n'); return b.toString(); }
    private static void sameProfiles(Map<Integer, byte[]> expected, Map<Integer, byte[]> actual) {
        MountPolicy.require(expected.keySet().equals(actual.keySet()), "配置已被其他操作改变");
        for (Integer uid : expected.keySet()) MountPolicy.require(Arrays.equals(expected.get(uid), actual.get(uid)), "配置已被其他操作改变：" + uid);
    }
    private void saveNew(String name, byte[] data) throws Exception { Files.write(new File(audit, name).toPath(), data, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE); }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static String sha(byte[] data) throws Exception { StringBuilder b = new StringBuilder(); for (byte v : MessageDigest.getInstance("SHA-256").digest(data)) b.append(String.format("%02x", v & 255)); return b.toString(); }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> { try { byte[] buf = new byte[8192]; int n; while ((n = process.getInputStream().read(buf)) >= 0) output.write(buf, 0, n); } catch (Exception ignored) {} });
        reader.start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(2000); throw new IllegalStateException("命令超时：" + args[0]); }
        reader.join(2000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        MountPolicy.require(process.exitValue() == 0, "命令失败：" + args[0] + " " + text); return text;
    }
}
