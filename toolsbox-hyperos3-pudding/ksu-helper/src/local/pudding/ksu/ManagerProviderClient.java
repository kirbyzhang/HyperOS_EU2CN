package local.pudding.ksu;

import android.content.AttributionSource;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Root-only RPC through the manager's existing, unexported FileProvider. */
public final class ManagerProviderClient implements AutoCloseable {
    private final Object activityManager;
    private final Object provider;
    private final Method release;
    private final Method call;
    private final String authority = BridgeProtocol.MANAGER + ".fileprovider";
    private final IBinder token = new Binder();
    private boolean acquired;

    public ManagerProviderClient(int managerUid) throws Exception {
        activityManager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        Class<?> api = Class.forName("android.app.IActivityManager");
        Method selected;
        try {
            selected = api.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class);
        } catch (NoSuchMethodException olderApi) {
            selected = api.getMethod("removeContentProviderExternal", String.class, IBinder.class);
        }
        release = selected;
        call = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource.class,
                String.class, String.class, String.class, Bundle.class);
        Object holder = api.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(activityManager, authority, 0, token, "HyperOS_EU2CN");
        BridgeProtocol.require(holder != null, "系统未提供 KernelSU 后台接口");
        acquired = true;
        try {
            ProviderInfo info = (ProviderInfo) holder.getClass().getField("info").get(holder);
            provider = holder.getClass().getField("provider").get(holder);
            BridgeProtocol.require(info != null && BridgeProtocol.MANAGER.equals(info.packageName)
                    && info.applicationInfo != null && info.applicationInfo.uid == managerUid
                    && !info.exported && provider != null, "KernelSU 后台接口身份不符");
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    public String request(String nonce) throws Exception {
        FutureTask<Bundle> task = new FutureTask<>(() -> (Bundle) call.invoke(provider,
                new AttributionSource.Builder(0).build(), authority, BridgeProtocol.ACTION, nonce, null));
        Thread thread = new Thread(task, "eu2cn-provider-rpc");
        thread.setDaemon(true);
        thread.start();
        Bundle result = task.get(25, TimeUnit.SECONDS);
        BridgeProtocol.require(result != null && result.containsKey("eu2cn.result"),
                "兼容层未提供后台直连接口；请覆盖安装本版兼容 APK，保留原作用域");
        return result.getString("eu2cn.result");
    }

    @Override public void close() throws Exception {
        if (!acquired) return;
        if (release.getParameterTypes().length == 3) release.invoke(activityManager, authority, token, 0);
        else release.invoke(activityManager, authority, token);
        acquired = false;
    }
}
