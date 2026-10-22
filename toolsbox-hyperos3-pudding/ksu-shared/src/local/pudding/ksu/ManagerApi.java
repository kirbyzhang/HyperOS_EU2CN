package local.pudding.ksu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolve the manager's own API by its contract, not an obfuscated singleton name. */
public final class ManagerApi {
    private final Object instance;
    private final Method get;
    private final Method set;

    public ManagerApi(ClassLoader loader) throws Exception {
        Class<?> type = Class.forName("me.weishu.kernelsu.Natives", true, loader);
        get = type.getMethod("getAppProfile", String.class, int.class);
        Class<?> profile = get.getReturnType();
        BridgeProtocol.require(profile != void.class && !profile.isPrimitive(), "配置读取接口不兼容");
        set = type.getMethod("setAppProfile", profile);
        Method identity = type.getMethod("isManager");
        BridgeProtocol.require(set.getReturnType() == boolean.class && identity.getReturnType() == boolean.class
                && !Modifier.isStatic(get.getModifiers()) && !Modifier.isStatic(set.getModifiers())
                && !Modifier.isStatic(identity.getModifiers()), "配置接口签名不兼容");
        Object found = null;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value == null) continue;
            BridgeProtocol.require(found == null || found == value, "管理器接口实例不唯一");
            found = value;
        }
        BridgeProtocol.require(found != null && Boolean.TRUE.equals(identity.invoke(found)), "未取得管理器身份");
        instance = found;
    }

    public Object read(String name, int uid) throws Exception {
        Object profile = get.invoke(instance, name, uid);
        BridgeProtocol.require(profile != null && ((Number) ProfileCopy.field(profile, "getCurrentUid")).intValue() == uid,
                "配置 UID 不符");
        ProfileCopy.validate(profile);
        return profile;
    }

    public void save(Object profile) throws Exception {
        BridgeProtocol.require(Boolean.TRUE.equals(set.invoke(instance, profile)), "管理器保存失败");
    }
}
