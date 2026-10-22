package local.pudding.eulocalization;

import java.util.HashSet;
import java.util.Set;

/** Bounded evidence without notification or message content; never equates a hook to business success. */
public final class HookEvidence {
    public static final String VERSION = "31801";
    private static final Set<String> SEEN = new HashSet<>();
    private HookEvidence() {}
    public static synchronized void record(String feature, String event) {
        if (SEEN.size() >= 128 || !SEEN.add(feature + ":" + event)) return;
        try {
            String start;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/stat"))) {
                String stat = reader.readLine();
                start = stat.substring(stat.lastIndexOf(')') + 2).split(" +")[19];
            }
            android.util.Log.i("PuddingEUState", "v=" + VERSION + " token=" + android.os.Process.myPid()
                    + ":" + start + " feature=" + feature + " event=" + event);
        } catch (Throwable ignored) { }
    }
}
