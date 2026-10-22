package local.pudding.ksu;

/** Shared wire constants and guards for the explicit, root-initiated bridge. */
public final class BridgeProtocol {
    public static final String MANAGER = "me.weishu.kernelsu";
    public static final String ACTION = "local.pudding.weathercompat.KSU_MOUNT";
    public static final String DIRECTORY = "eu2cn-mount-bridge";
    public static final String RECEIVER = MANAGER + "/androidx.profileinstaller.ProfileInstallReceiver";
    public static final int VERSION = 2;
    private BridgeProtocol() {}
    public static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    public static void validate(String nonce, String operation, String name, int uid, long expiry, long now) {
        require(nonce != null && nonce.matches("[0-9a-f]{32}"), "请求标识无效");
        require("get".equals(operation) || "apply".equals(operation), "操作无效");
        require(name != null && name.length() <= 255 && (name.equals("android") || name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")), "配置名称无效");
        require(uid >= 1000 && uid < 100000 && uid != 9999, "只处理用户 0 应用，不修改全局默认");
        require(expiry > now && expiry - now <= 120000, "请求已过期");
    }
}
