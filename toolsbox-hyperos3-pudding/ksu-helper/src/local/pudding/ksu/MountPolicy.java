package local.pudding.ksu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only decoding and strict verification of manager-owned profile changes. */
public final class MountPolicy {
    public static final int RECORD_SIZE = 784;
    private MountPolicy() {}

    public static Map<Integer, byte[]> decode(byte[] raw) {
        require(raw.length >= 8 && (raw.length - 8) % RECORD_SIZE == 0, "未知配置长度");
        ByteBuffer bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        require(bytes.getInt(0) == 2135642965 && bytes.getInt(4) == 4, "不支持的配置格式");
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        for (int offset = 8; offset < raw.length; offset += RECORD_SIZE) {
            require(bytes.getInt(offset) == 4, "不支持的记录版本");
            int uid = bytes.getInt(offset + 260);
            byte[] row = Arrays.copyOfRange(raw, offset, offset + RECORD_SIZE);
            require(row[264] == 0 || row[264] == 1, "未知 Root 状态");
            require(row[272] == 0 || row[272] == 1, "未知默认状态");
            // In a Root profile this byte belongs to a template name, not nrp.
            if (row[264] == 0) require(row[273] == 0 || row[273] == 1, "未知卸载状态");
            require(!result.containsKey(uid), "配置中有重复 UID");
            result.put(uid, row);
        }
        require(result.containsKey(9999), "无法确认全局默认配置");
        return result;
    }

    public static boolean needsException(Map<Integer, byte[]> profiles, int uid) {
        require(uid >= 0 && uid < 100000, "只支持用户 0");
        byte[] row = profiles.get(uid);
        if (row != null && row[264] == 1) return false; // Preserve all Root profiles.
        if (row == null || row[272] == 1) row = profiles.get(9999);
        require(row != null && row[264] == 0, "默认配置不可识别");
        return row[273] == 1;
    }

    public static void verifyDelta(Map<Integer, byte[]> before, Map<Integer, byte[]> after, int uid) {
        byte[] row = after.get(uid);
        require(row != null && row[264] == 0 && row[272] == 0 && row[273] == 0, "例外尚未正确保存");
        verifyTargetFields(before, after, uid);
    }

    private static void verifyTargetFields(Map<Integer, byte[]> before, Map<Integer, byte[]> after, int uid) {
        byte[] row = after.get(uid);
        require(row != null && row[264] == 0, "不得授予 Root");
        for (Map.Entry<Integer, byte[]> item : before.entrySet()) {
            byte[] updated = after.get(item.getKey());
            require(updated != null, "配置记录被删除");
            if (item.getKey() != uid) {
                require(Arrays.equals(item.getValue(), updated), "其他 UID 配置发生变化：" + item.getKey());
            } else {
                require(item.getValue()[264] == 0, "不得修改已有 Root 授权");
                for (int i = 0; i < RECORD_SIZE; i++) {
                    if (i != 272 && i != 273) require(item.getValue()[i] == updated[i], "无关配置字段发生变化");
                }
            }
        }
        for (Integer key : after.keySet()) require(before.containsKey(key) || key == uid, "新增了其他 UID 配置");
        if (!before.containsKey(uid)) {
            // A newly created non-root profile has no pre-existing settings.
            for (int i = 264; i < RECORD_SIZE; i++) {
                if (i != 272 && i != 273) require(row[i] == 0, "新增记录含无关配置字段");
            }
        }
    }

    /** Some manager versions persist immediately when a control is toggled. */
    public static void verifyPendingSave(Map<Integer, byte[]> before, Map<Integer, byte[]> current, int uid) {
        boolean unchanged = before.keySet().equals(current.keySet());
        if (unchanged) for (Integer key : before.keySet()) {
            if (!Arrays.equals(before.get(key), current.get(key))) { unchanged = false; break; }
        }
        // The two controls can be persisted asynchronously and independently.
        // Only their bytes may differ here; verifyDelta still requires both off
        // after returning from the profile page.
        if (!unchanged) verifyTargetFields(before, current, uid);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
