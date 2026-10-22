package local.pudding.ksu;

import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Root-side planner and verifier. Never writes KernelSU's allowlist file. */
public final class KsuBackgroundClient {
    private static final File PROFILES = new File("/data/adb/ksu/.allowlist");
    private final List<Integer> completed = new ArrayList<>();
    private File audit;
    private File directory;
    private int managerUid;
    private String boot;
    private String managerSha;

    public static void main(String[] args) {
        KsuBackgroundClient client = new KsuBackgroundClient();
        int result = 1;
        try {
            MountPolicy.require(args.length == 3 && ("--plan".equals(args[0]) || "--apply".equals(args[0])), "参数无效");
            File module = new File(args[1]);
            String canonical = module.getCanonicalPath();
            MountPolicy.require(canonical.equals("/data/adb/modules/HyperOS_EU2CN")
                    || canonical.equals("/data/adb/modules/pudding_eu_localization"), "只支持活动本地化模块");
            client.audit = new File(args[2]);
            MountPolicy.require(client.audit.isDirectory() && client.audit.getCanonicalPath().startsWith(
                    "/data/adb/pudding-eu-localization-state/ksu-mount-actions/"), "日志目录无效");
            File lockPath = new File(client.audit.getParentFile(), "helper.lock");
            MountPolicy.require(!Files.isSymbolicLink(lockPath.toPath()), "锁文件是链接");
            try (FileChannel channel = new FileOutputStream(lockPath, true).getChannel(); FileLock lock = channel.tryLock()) {
                MountPolicy.require(lock != null, "已有例外任务正在运行");
                client.run(module, args[0]);
            }
            result = 0;
        } catch (Throwable error) {
            System.out.println("停止：" + error.getMessage());
            error.printStackTrace(System.out);
            System.out.println("已完成 UID：" + client.completed + "；保留已保存项，请查看本次快照，不自动回滚用户配置。");
        }
        System.exit(result);
    }

    private void run(File module, String mode) throws Exception {
        MountPolicy.require(command("/system/bin/id", "-u").trim().equals("0"), "请从模块入口运行");
        MountPolicy.require(!new File(module, "disable").exists() && !new File(module, "remove").exists(), "模块已停用或待卸载");
        MountPolicy.require(command("/system/bin/am", "get-current-user").trim().equals("0"), "只支持用户 0");
        String path = command("/system/bin/pm", "path", BridgeProtocol.MANAGER).trim();
        MountPolicy.require(path.startsWith("package:/data/app/") && !path.contains("\n"), "管理器路径无效");
        // Bind each request to the manager installed now, rather than a release hash/version.
        managerSha = sha(Files.readAllBytes(new File(path.substring(8)).toPath()));
        save("manager-sha256.txt", managerSha.getBytes(StandardCharsets.UTF_8));
        boot = read(new File("/proc/sys/kernel/random/boot_id")).trim();
        Map<String, Integer> installed = packages();
        MountPolicy.require(installed.containsKey(BridgeProtocol.MANAGER), "未找到管理器 UID");
        managerUid = installed.get(BridgeProtocol.MANAGER);
        Set<String> selected = TargetPlan.backgroundPackages(module);
        Map<Integer, Set<String>> targets = TargetPlan.group(selected, installed, selected);
        byte[] initial = stableProfiles();
        Map<Integer, byte[]> baseline = MountPolicy.decode(initial);
        save("profiles-before.bin", initial);
        StringBuilder plan = new StringBuilder();
        int count = 0;
        for (Map.Entry<Integer, Set<String>> target : targets.entrySet()) {
            int uid = target.getKey();
            BridgeProtocol.validate("00000000000000000000000000000000", "get", target.getValue().iterator().next(), uid, 1, 0);
            boolean pending = MountPolicy.needsException(baseline, uid);
            if (pending) count++;
            plan.append(pending ? "待补齐 " : "保留 ").append(uid).append(' ').append(target.getValue()).append('\n');
        }
        save("plan.txt", plan.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(plan);
        System.out.println("待处理 " + count + " 个 UID 组；后台配置，不授予 Root，不改变默认卸载开关。");
        if ("--plan".equals(mode)) {
            System.out.println("BACKGROUND_COMPLETE changed=0 pending=" + count);
            return;
        }
        File files = new File("/data/user/0/" + BridgeProtocol.MANAGER + "/files");
        directory = new File(files, BridgeProtocol.DIRECTORY);
        MountPolicy.require(files.isDirectory() && !Files.isSymbolicLink(directory.toPath()), "管理器私有目录无效");
        if (!directory.exists()) MountPolicy.require(directory.mkdir(), "创建请求目录失败");
        MountPolicy.require(directory.isDirectory() && directory.getCanonicalFile().getParentFile().equals(files.getCanonicalFile()), "请求目录越界");
        command("/system/bin/chown", managerUid + ":" + managerUid, directory.getAbsolutePath());
        command("/system/bin/chmod", "700", directory.getAbsolutePath());
        command("/system/bin/restorecon", directory.getAbsolutePath());
        // Validate every pending profile's native API and immutable-copy contract before
        // saving anything. With no pending groups, still verify background availability.
        boolean probed = false;
        for (Map.Entry<Integer, Set<String>> target : targets.entrySet()) {
            int uid = target.getKey();
            if (!MountPolicy.needsException(baseline, uid) && (count != 0 || probed)) continue;
            byte[] old = baseline.get(uid);
            String lookup = old == null ? target.getValue().iterator().next() : recordName(old);
            JSONObject response = call(new JSONObject().put("protocol", BridgeProtocol.VERSION)
                    .put("operation", "get").put("lookupName", lookup).put("uid", uid), uid);
            MountPolicy.require(response.optBoolean("ok") && response.optInt("writes", -1) == 0,
                    response.optString("error", "后台接口预检失败"));
            probed = true;
        }
        MountPolicy.require(probed, "未找到可验证的目标应用");
        sameProfiles(baseline, MountPolicy.decode(stableProfiles()));
        System.out.println("后台接口及配置结构预检通过；不依赖固定 KernelSU 版本。");
        Map<Integer, byte[]> latest = baseline;
        for (Map.Entry<Integer, Set<String>> target : targets.entrySet()) {
            int uid = target.getKey();
            if (!MountPolicy.needsException(latest, uid)) continue;
            MountPolicy.require(read(new File("/proc/sys/kernel/random/boot_id")).trim().equals(boot), "启动已变化");
            Map<String, Integer> currentPackages = packages();
            for (String name : target.getValue()) MountPolicy.require(Integer.valueOf(uid).equals(currentPackages.get(name)), "应用 UID 已变化，请重新生成计划");
            sameProfiles(latest, MountPolicy.decode(stableProfiles()));
            byte[] old = latest.get(uid);
            String lookup = old == null ? target.getValue().iterator().next() : recordName(old);
            JSONObject request = new JSONObject().put("protocol", BridgeProtocol.VERSION).put("operation", "apply")
                    .put("lookupName", lookup).put("uid", uid).put("hasRecord", old != null);
            if (old != null) request.put("expectedUseDefault", old[272] == 1).put("expectedUmount", old[273] == 1);
            JSONObject response = call(request, uid);
            MountPolicy.require(response.optBoolean("ok"), response.optString("error", "后台接口失败"));
            byte[] persisted = null;
            Exception last = null;
            for (int i = 0; i < 50; i++) {
                try {
                    persisted = stableProfiles();
                    MountPolicy.verifyDelta(latest, MountPolicy.decode(persisted), uid);
                    last = null;
                    break;
                } catch (Exception error) { last = error; Thread.sleep(100); }
            }
            if (last != null) throw last;
            latest = MountPolicy.decode(persisted);
            save("profiles-after-" + uid + ".bin", persisted);
            completed.add(uid);
            System.out.println("后台已保存 UID " + uid + " " + target.getValue() + "；其他配置保持。");
        }
        sameProfiles(latest, MountPolicy.decode(stableProfiles()));
        save("profiles-after.bin", stableProfiles());
        System.out.println("BACKGROUND_COMPLETE changed=" + completed.size() + " pending=0；新进程挂载需完整重启。");
    }

    private JSONObject call(JSONObject input, int uid) throws Exception {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder();
        for (byte b : random) token.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String nonce = token.toString();
        input.put("nonce", nonce).put("expiresElapsed", SystemClock.elapsedRealtime() + 120000)
                .put("managerSha256", managerSha);
        File request = new File(directory, "request.json");
        for (String leaf : new String[]{"request.json", "consumed.json", "result.json"}) {
            File previous = new File(directory, leaf);
            MountPolicy.require(!Files.isSymbolicLink(previous.toPath()), "请求文件是链接");
            if (previous.exists()) Files.move(previous.toPath(), new File(directory, nonce + "-previous-" + leaf).toPath());
        }
        File pending = new File(directory, nonce + ".request");
        Files.write(pending.toPath(), input.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        command("/system/bin/chown", managerUid + ":" + managerUid, pending.getAbsolutePath());
        command("/system/bin/chmod", "600", pending.getAbsolutePath());
        Files.move(pending.toPath(), request.toPath(), StandardCopyOption.ATOMIC_MOVE);
        command("/system/bin/restorecon", request.getAbsolutePath());
        String prefix = input.getString("operation") + "-" + uid;
        save("request-" + prefix + ".json", input.toString(2).getBytes(StandardCharsets.UTF_8));
        try {
            String broadcast = command("/system/bin/am", "broadcast", "--user", "0", "-f", "0x01000020", "-n",
                    BridgeProtocol.RECEIVER, "-a", BridgeProtocol.ACTION, "--es", "nonce", nonce);
            save("broadcast-" + prefix + ".txt", broadcast.getBytes(StandardCharsets.UTF_8));
            File result = new File(directory, "result.json");
            for (int i = 0; i < 50; i++) {
                if (result.isFile() && !Files.isSymbolicLink(result.toPath())) {
                    JSONObject response = new JSONObject(read(result));
                    if (nonce.equals(response.optString("nonce"))) {
                        MountPolicy.require(response.getInt("protocol") == BridgeProtocol.VERSION && response.getInt("uid") == uid, "响应身份不符");
                        save("response-" + prefix + ".json", response.toString(2).getBytes(StandardCharsets.UTF_8));
                        return response;
                    }
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("后台接口未响应；请在 LSPosed 启用 HyperOS_EU2CN 兼容层并勾选 KernelSU，重启管理器后重试");
        } finally {
            if (request.isFile() && !Files.isSymbolicLink(request.toPath())) {
                JSONObject remaining = new JSONObject(read(request));
                if (nonce.equals(remaining.optString("nonce"))) Files.move(request.toPath(), new File(directory, nonce + "-cancelled.json").toPath());
            }
        }
    }

    private static String recordName(byte[] row) {
        int length = 0;
        while (length < 256 && row[4 + length] != 0) length++;
        return new String(row, 4, length, StandardCharsets.UTF_8);
    }
    private static void sameProfiles(Map<Integer, byte[]> before, Map<Integer, byte[]> after) {
        MountPolicy.require(before.keySet().equals(after.keySet()), "配置记录集合已变化");
        for (Integer uid : before.keySet()) MountPolicy.require(Arrays.equals(before.get(uid), after.get(uid)), "配置已在其他位置修改：" + uid);
    }
    private static byte[] stableProfiles() throws Exception {
        for (int i = 0; i < 10; i++) {
            byte[] first = Files.readAllBytes(PROFILES.toPath());
            Thread.sleep(50);
            byte[] second = Files.readAllBytes(PROFILES.toPath());
            if (Arrays.equals(first, second)) { MountPolicy.decode(second); return second; }
        }
        throw new IllegalStateException("配置仍在保存，请稍后重试");
    }
    private void save(String name, byte[] raw) throws Exception {
        Files.write(new File(audit, name).toPath(), raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static Map<String, Integer> packages() throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String line : command("/system/bin/pm", "list", "packages", "-U", "--user", "0").split("\n")) {
            Matcher match = Pattern.compile("^package:(\\S+) uid:(\\d+)$").matcher(line.trim());
            MountPolicy.require(match.matches(), "包列表不可识别");
            result.put(match.group(1), Integer.parseInt(match.group(2)));
        }
        MountPolicy.require(!result.isEmpty(), "包列表为空");
        return result;
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static String sha(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> { try { byte[] buf = new byte[8192]; int n; while ((n = process.getInputStream().read(buf)) >= 0) output.write(buf, 0, n); } catch (Exception ignored) { } });
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(2000); throw new IllegalStateException("命令超时：" + args[0]); }
        reader.join(2000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        MountPolicy.require(process.exitValue() == 0, "命令失败：" + args[0] + " " + text);
        return text;
    }
}
