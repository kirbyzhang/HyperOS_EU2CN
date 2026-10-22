package local.pudding.eulocalization;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Only the verified Android directory cache key (flags=16); no prefix or wildcard deletion. */
public final class CacheRepairPlan {
    public final String pkg, apk, apkSha, key;
    private static final String ROOT = "/data/system/package_cache";
    public CacheRepairPlan(String pkg, String apk, String apkSha) {
        if (!pkg.matches("[a-zA-Z0-9_.]+") || !apkSha.matches("[a-f0-9]{64}")
                || apk.contains("/../") || apk.contains("/./")
                || !apk.matches("/(system|product|system_ext)/(app|priv-app)/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+\\.apk")
                && !apk.matches("/system/(product|system_ext)/(app|priv-app)/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+\\.apk")) {
            throw new IllegalArgumentException("不支持的路径或包身份；仅诊断");
        }
        this.pkg = pkg; this.apk = apk; this.apkSha = apkSha;
        File directory = new File(apk).getParentFile();
        key = directory.getName() + "-16-" + directory.getPath().hashCode();
    }
    private String identityGuard() {
        return "set -eu\numask 077\n"
                + "[ \"$(pm path " + pkg + ")\" = 'package:" + apk + "' ] || { echo 'APK路径改变或含split，已停止'; exit 1; }\n"
                + "[ \"$(sha256sum '" + apk + "' | cut -d ' ' -f1)\" = '" + apkSha + "' ] || { echo 'APK内容改变，已停止'; exit 1; }\n"
                + "[ -d " + ROOT + " ] && [ ! -L " + ROOT + " ] || exit 1\n";
    }
    private String markerGuard() {
        StringBuilder hex = new StringBuilder();
        for (byte b : pkg.getBytes(StandardCharsets.UTF_16LE)) hex.append(String.format("%02x", b & 255));
        return "od -An -v -tx1 \"$file\" | tr -d ' \\n' | grep -q '" + hex + "'";
    }
    public String preview() {
        return identityGuard() + "for dir in " + ROOT + "/*; do\n"
                + "  [ -d \"$dir\" ] && [ ! -L \"$dir\" ] || continue\n"
                + "  file=\"$dir/" + key + "\"\n"
                + "  [ -f \"$file\" ] && [ ! -L \"$file\" ] || continue\n"
                + "  [ \"$(readlink -f \"$file\")\" = \"$file\" ] || continue\n"
                + "  " + markerGuard() + " || continue\n"
                + "  printf 'CANDIDATE|%s|%s\\n' \"$file\" \"$(sha256sum \"$file\" | cut -d ' ' -f1)\"\n"
                + "done\n";
    }
    public String verifyTarget(String path, String sha) {
        if (!path.matches(ROOT + "/[a-zA-Z0-9_-]+/" + java.util.regex.Pattern.quote(key))
                || !sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("缓存目标不匹配");
        return identityGuard() + "file='" + path + "'\n"
                + "[ -f \"$file\" ] && [ ! -L \"$file\" ] && [ ! -L \"${file%/*}\" ] || exit 1\n"
                + "[ \"$(readlink -f \"$file\")\" = \"$file\" ] || exit 1\n"
                + "[ \"$(sha256sum \"$file\" | cut -d ' ' -f1)\" = '" + sha + "' ] || { echo '缓存已经变化，请重新诊断'; exit 1; }\n"
                + markerGuard() + " || exit 1\n";
    }
    public String repair(String path, String sha) {
        return verifyTarget(path, sha) + "base=/data/adb/pudding-package-cache-backups\n"
                + "[ ! -L \"$base\" ] || exit 1\nmkdir -p \"$base\"\nchmod 700 \"$base\"\n"
                + "backup=$(mktemp -d \"$base/repair-XXXXXXXX\")\n"
                + "cp -p \"$file\" \"$backup/cache\"\n"
                + "[ \"$(sha256sum \"$backup/cache\" | cut -d ' ' -f1)\" = '" + sha + "' ] || exit 1\n"
                + "printf '%s\\n' 'package=" + pkg + "' 'source=" + path + "' 'sha256=" + sha
                + "' 'apk=" + apk + "' 'apk_sha256=" + apkSha + "' > \"$backup/manifest.txt\"\n"
                + "cmp -s \"$file\" \"$backup/cache\" || exit 1\n"
                + "[ \"$(pm path " + pkg + ")\" = 'package:" + apk + "' ] || exit 1\n"
                + "[ \"$(sha256sum '" + apk + "' | cut -d ' ' -f1)\" = '" + apkSha + "' ] || exit 1\n"
                + "rm -f -- \"$file\"\nprintf '已备份并移除精确缓存；备份：%s\\n' \"$backup\"\n";
    }
}
