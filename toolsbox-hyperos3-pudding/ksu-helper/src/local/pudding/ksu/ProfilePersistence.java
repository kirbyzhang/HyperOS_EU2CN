package local.pudding.ksu;

import java.util.Map;

/** Confirm asynchronous manager saves without accepting unrelated profile changes. */
public final class ProfilePersistence {
    public interface Access {
        byte[] read() throws Exception;
        void wakeInit() throws Exception;
        long now();
        void pause(long millis) throws InterruptedException;
    }

    private ProfilePersistence() {}

    public static byte[] await(Map<Integer, byte[]> before, int uid, Access access, long timeoutMillis) throws Exception {
        MountPolicy.require(timeoutMillis > 0, "持久化等待时间无效");
        long deadline = access.now() + timeoutMillis;
        long nextWake = access.now();
        Exception lastReadError = null;
        while (true) {
            byte[] raw = null;
            Map<Integer, byte[]> current = null;
            try {
                raw = access.read();
                current = MountPolicy.decode(raw);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception transientRead) {
                // The kernel may temporarily truncate/rewrite the profile file.
                lastReadError = transientRead;
            }
            if (current != null) {
                // Changes to another UID, Root or unrelated bytes are fatal,
                // rather than being mistaken for a slow asynchronous save.
                MountPolicy.verifyPendingSave(before, current, uid);
                byte[] row = current.get(uid);
                if (row != null && row[264] == 0 && row[272] == 0 && row[273] == 0) {
                    MountPolicy.verifyDelta(before, current, uid);
                    return raw;
                }
            }
            long now = access.now();
            if (now >= deadline) throw new IllegalStateException(
                    "等待 KernelSU 持久化确认超时；停止后续项，未宣布全部补齐，请查看本次日志", lastReadError);
            if (now >= nextWake) {
                access.wakeInit();
                nextWake = access.now() + 2000;
            }
            access.pause(Math.min(100, Math.max(1, deadline - access.now())));
        }
    }
}
