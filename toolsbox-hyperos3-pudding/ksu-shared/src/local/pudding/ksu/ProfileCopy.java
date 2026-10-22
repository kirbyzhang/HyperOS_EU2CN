package local.pudding.ksu;

/** Preserve the manager's immutable profile; change only non-root mount fields. */
public final class ProfileCopy {
    private static final String[] GETTERS = {"getName", "getCurrentUid", "getAllowSu", "getRootUseDefault", "getRootTemplate",
            "getUid", "getGid", "getGroups", "getCapabilities", "getContext", "getNamespace",
            "getNonRootUseDefault", "getUmountModules", "getRules", "getFlags"};
    private static final Class<?>[] TYPES = {String.class, int.class, boolean.class, boolean.class, String.class, int.class,
            int.class, java.util.List.class, java.util.List.class, String.class, int.class,
            boolean.class, boolean.class, String.class, long.class};
    private ProfileCopy() {}
    public static Object field(Object profile, String getter) throws Exception {
        return profile.getClass().getMethod(getter).invoke(profile);
    }
    public static void validate(Object profile) throws Exception {
        Class<?> type = profile.getClass();
        BridgeProtocol.require(type.getMethod("copy", TYPES).getReturnType() == type, "配置复制接口不兼容");
        int copies = 0;
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals("copy")) copies++;
        }
        BridgeProtocol.require(copies == 1, "配置复制接口不明确");
        for (int i = 0; i < GETTERS.length; i++) {
            BridgeProtocol.require(type.getMethod(GETTERS[i]).getReturnType() == TYPES[i], "配置字段类型不兼容：" + GETTERS[i]);
        }
    }
    public static Object withException(Object profile) throws Exception {
        validate(profile);
        Object[] args = new Object[GETTERS.length];
        for (int i = 0; i < GETTERS.length; i++) args[i] = field(profile, GETTERS[i]);
        BridgeProtocol.require(Boolean.FALSE.equals(args[2]), "保留已有 Root 配置，不修改");
        args[11] = false;
        args[12] = false;
        Object result = profile.getClass().getMethod("copy", TYPES).invoke(profile, args);
        for (int i = 0; i < GETTERS.length; i++) {
            BridgeProtocol.require(java.util.Objects.equals(args[i], field(result, GETTERS[i])), "无关配置字段发生变化");
        }
        return result;
    }
}
