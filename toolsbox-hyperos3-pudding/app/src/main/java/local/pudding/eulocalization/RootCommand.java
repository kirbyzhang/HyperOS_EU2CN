package local.pudding.eulocalization;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Drain while waiting so a full output pipe cannot deadlock a root command. */
public final class RootCommand {
    private RootCommand() {}
    public static String run(String script) throws Exception {
        Process process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    synchronized (output) {
                        int keep = Math.min(n, 262144 - output.size());
                        if (keep > 0) output.write(buffer, 0, keep);
                    }
                }
            } catch (Exception ignored) { }
        }, "pudding-root-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("请求超时；操作结果未知，请重新诊断，勿重复清理");
        }
        reader.join(3000);
        String result;
        synchronized (output) { result = new String(output.toByteArray(), StandardCharsets.UTF_8); }
        if (process.exitValue() != 0) throw new IllegalStateException("退出码 " + process.exitValue() + "\n" + result);
        return result;
    }
}
