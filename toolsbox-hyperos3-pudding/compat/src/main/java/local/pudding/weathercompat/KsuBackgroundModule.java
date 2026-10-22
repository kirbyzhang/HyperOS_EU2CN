package local.pudding.weathercompat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import org.json.JSONObject;
import local.pudding.ksu.BridgeProtocol;
import local.pudding.ksu.ProfileCopy;
import local.pudding.ksu.ManagerApi;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Manager-native saves, only for explicit root requests through a DUMP-protected receiver. */
public final class KsuBackgroundModule implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam app) {
        if (!BridgeProtocol.MANAGER.equals(app.packageName) || !BridgeProtocol.MANAGER.equals(app.processName)) return;
        try {
            XposedHelpers.findAndHookMethod("androidx.profileinstaller.ProfileInstallReceiver", app.classLoader,
                    "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[1];
                    if (intent == null || !BridgeProtocol.ACTION.equals(intent.getAction())) return;
                    param.setResult(null);
                    JSONObject result = dispatch((Context) param.args[0], intent, app.classLoader);
                    BroadcastReceiver receiver = (BroadcastReceiver) param.thisObject;
                    if (receiver.isOrderedBroadcast()) receiver.setResultCode(result.optBoolean("ok") ? 0 : 1);
                }
            });
            XposedBridge.log("EU2CNKsuBridge: ready");
        } catch (Throwable error) {
            XposedBridge.log("EU2CNKsuBridge: unavailable " + error.getClass().getSimpleName());
        }
    }

    private static synchronized JSONObject dispatch(Context context, Intent intent, ClassLoader loader) {
        JSONObject output = new JSONObject();
        File directory = new File(context.getFilesDir(), BridgeProtocol.DIRECTORY);
        File request = new File(directory, "request.json");
        File consumed = new File(directory, "consumed.json");
        boolean ownedRequest = false;
        int writes = 0;
        String nonce = null;
        try {
            BridgeProtocol.require(BridgeProtocol.MANAGER.equals(context.getPackageName()), "管理器上下文不符");
            BridgeProtocol.require(!Files.isSymbolicLink(directory.toPath())
                    && directory.getCanonicalFile().getParentFile().equals(context.getFilesDir().getCanonicalFile()), "请求目录无效");
            BridgeProtocol.require(request.isFile() && !Files.isSymbolicLink(request.toPath())
                    && request.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                    && request.length() > 0 && request.length() <= 4096, "没有有效请求");
            JSONObject input = new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8));
            nonce = input.getString("nonce");
            String operation = input.getString("operation");
            String name = input.getString("lookupName");
            int uid = input.getInt("uid");
            BridgeProtocol.validate(nonce, operation, name, uid, input.getLong("expiresElapsed"), SystemClock.elapsedRealtime());
            BridgeProtocol.require(input.getInt("protocol") == BridgeProtocol.VERSION && nonce.equals(intent.getStringExtra("nonce")), "请求版本或标识不符");
            BridgeProtocol.require(!consumed.exists() && !Files.isSymbolicLink(consumed.toPath()) && request.renameTo(consumed), "请求已消费或正在处理");
            ownedRequest = true;
            output.put("nonce", nonce).put("uid", uid).put("protocol", BridgeProtocol.VERSION);
            ActivityInfo receiverInfo = context.getPackageManager().getReceiverInfo(new ComponentName(
                    BridgeProtocol.MANAGER, "androidx.profileinstaller.ProfileInstallReceiver"), 0);
            BridgeProtocol.require("android.permission.DUMP".equals(receiverInfo.permission), "后台接收器权限不兼容");
            byte[] apk = Files.readAllBytes(new File(context.getApplicationInfo().sourceDir).toPath());
            String expectedSha = input.getString("managerSha256");
            BridgeProtocol.require(expectedSha.matches("[0-9a-f]{64}")
                    && expectedSha.equals(hex(MessageDigest.getInstance("SHA-256").digest(apk))), "管理器在任务期间发生变化，请重新运行");
            ManagerApi api = new ManagerApi(loader);
            Object profile = api.read(name, uid);
            // Exercise immutable copying before any write, even for read-only probes.
            if (Boolean.FALSE.equals(ProfileCopy.field(profile, "getAllowSu"))) ProfileCopy.withException(profile);
            output.put("before", describe(profile));
            if ("apply".equals(operation)) {
                BridgeProtocol.require(Boolean.FALSE.equals(ProfileCopy.field(profile, "getAllowSu")), "已有 Root 配置，停止且保留");
                if (input.getBoolean("hasRecord")) {
                    BridgeProtocol.require(input.getBoolean("expectedUseDefault") == (Boolean) ProfileCopy.field(profile, "getNonRootUseDefault")
                            && input.getBoolean("expectedUmount") == (Boolean) ProfileCopy.field(profile, "getUmountModules"), "应用配置已变化，请重新生成计划");
                } else {
                    BridgeProtocol.require(Boolean.TRUE.equals(ProfileCopy.field(profile, "getNonRootUseDefault")), "配置已在其他位置修改，请重新生成计划");
                }
                Object desired = ProfileCopy.withException(profile);
                Object latest = api.read(name, uid);
                BridgeProtocol.require(profile.equals(latest), "保存前检测到并发修改");
                if (!desired.equals(latest)) {
                    writes++;
                    api.save(desired);
                }
                profile = api.read(name, uid);
                BridgeProtocol.require(desired.equals(profile), "保存后的配置回读不符");
            }
            output.put("after", describe(profile)).put("ok", true).put("writes", writes).put("pid", android.os.Process.myPid());
        } catch (Throwable error) {
            try { output.put("ok", false).put("error", error.toString()).put("writes", writes); } catch (Exception ignored) { }
            XposedBridge.log("EU2CNKsuBridge: stopped " + error.getClass().getSimpleName());
        }
        if (ownedRequest) try {
            File pending = new File(directory, "result-" + nonce + ".pending");
            File result = new File(directory, "result.json");
            BridgeProtocol.require(!Files.isSymbolicLink(result.toPath()), "响应路径无效");
            Files.write(pending.toPath(), output.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(pending.toPath(), result.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable error) { XposedBridge.log("EU2CNKsuBridge: result save failed"); }
        return output;
    }

    private static JSONObject describe(Object profile) throws Exception {
        return new JSONObject().put("name", ProfileCopy.field(profile, "getName"))
                .put("uid", ProfileCopy.field(profile, "getCurrentUid"))
                .put("allowSu", ProfileCopy.field(profile, "getAllowSu"))
                .put("useDefault", ProfileCopy.field(profile, "getNonRootUseDefault"))
                .put("umount", ProfileCopy.field(profile, "getUmountModules"));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
