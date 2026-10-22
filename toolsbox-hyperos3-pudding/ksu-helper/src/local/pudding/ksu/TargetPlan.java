package local.pudding.ksu;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Targets are package names from selected, present APKs, never saved UIDs. */
public final class TargetPlan {
    private TargetPlan() {}

    /** Background saves support native app shells; pure resource overlays have no process. */
    public static Set<String> backgroundPackages(File module) throws Exception {
        Set<String> result = selectedPackages(module);
        Set<String> overlays = new LinkedHashSet<>();
        for (String line : Files.readAllLines(new File(module, "payload-manifest.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (cells.length >= 6 && cells[0].contains("/overlay/") && cells[0].endsWith(".apk")) overlays.add(cells[3]);
        }
        result.removeAll(overlays);
        return result;
    }

    public static Set<String> selectedPackages(File module) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (String line : Files.readAllLines(new File(module, "payload-manifest.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (cells.length < 6 || !cells[0].endsWith(".apk")) continue;
            String path = cells[0];
            MountPolicy.require(path.startsWith("system/") && !path.contains("..") && !path.contains("//"), "无效载荷路径");
            File apk = new File(module, path);
            MountPolicy.require(apk.getCanonicalPath().startsWith(module.getCanonicalPath() + "/"), "载荷链接越界");
            if (apk.isFile()) {
                MountPolicy.require(cells[3].matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"), "载荷包名无效");
                result.add(cells[3]);
            }
        }
        if (!result.isEmpty()) {
            result.add("android");
            // android itself has no HAS_CODE; Settings represents the system UID.
            result.add("com.android.settings");
        }
        addDependencies(module, result, "Wallet", "com.android.nfc", "com.mipay.wallet", "com.xiaomi.payment", "com.miui.nextpay");
        addDependencies(module, result, "MmsYellowPage", "com.android.phone", "com.android.smspush", "com.android.mms");
        addDependencies(module, result, "NewCall", "com.android.phone");
        addDependencies(module, result, "AiCall", "com.android.phone");
        addDependencies(module, result, "AppStore", "com.android.permissioncontroller");
        addDependencies(module, result, "CnSearch", "com.miui.home");
        addDependencies(module, result, "HookWidgets", "com.miui.home", "com.miui.personalassistant");
        return result;
    }

    private static void addDependencies(File module, Set<String> targets, String marker, String... packages) {
        if (!new File(module, "markers/" + marker).isFile()) return;
        java.util.Collections.addAll(targets, packages);
    }

    public static Map<Integer, Set<String>> group(Set<String> selected, Map<String, Integer> installed, Set<String> hasCode) {
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        for (String name : selected) {
            Integer uid = installed.get(name);
            if (uid == null || !hasCode.contains(name)) continue;
            MountPolicy.require(uid >= 0 && uid < 100000, "目标不是用户 0");
            result.computeIfAbsent(uid, ignored -> new LinkedHashSet<>()).add(name);
        }
        return result;
    }

    public static Integer visibleRowUid(Set<String> labels, Map<String, Integer> installed, Set<Integer> pending) {
        Set<Integer> all = new LinkedHashSet<>();
        for (String label : labels) if (installed.containsKey(label)) all.add(installed.get(label));
        if (all.size() != 1) return null;
        Integer uid = all.iterator().next();
        return pending.contains(uid) ? uid : null;
    }

    /** Manager rows for shared UIDs may show their platform name, not a package. */
    public static void addSharedIdentity(Map<String, Integer> identities, String dump, int expectedUid) {
        Matcher match = Pattern.compile("(?m)^\\s*sharedUser=SharedUserSetting\\{[^\\s}]+ ([A-Za-z0-9_.]+)/([0-9]+)\\}").matcher(dump);
        if (!match.find()) return;
        int uid = Integer.parseInt(match.group(2));
        MountPolicy.require(uid == expectedUid, "共享 UID 身份不匹配");
        Integer old = identities.get(match.group(1));
        MountPolicy.require(old == null || old == uid, "共享 UID 名称冲突");
        identities.put(match.group(1), uid);
    }
}
