package local.pudding.eulocalization;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preflight every captured target, then stop the batch on the first uncertain result. */
public final class CacheRepairBatch {
    private CacheRepairBatch() {}
    public static final class Target {
        public final CacheRepairPlan plan;
        public final String path, sha;
        public Target(CacheRepairPlan plan, String path, String sha) {
            plan.verifyTarget(path, sha);
            this.plan = plan; this.path = path; this.sha = sha;
        }
    }
    public interface Operations {
        void verify(Target target) throws Exception;
        String repair(Target target) throws Exception;
    }
    public static String run(List<Target> targets, Operations operations) {
        if (targets.isEmpty()) return "没有已确认异常的缓存，无需清理。";
        Set<String> paths = new HashSet<>();
        int completed = 0;
        StringBuilder output = new StringBuilder();
        try {
            for (Target target : targets) {
                if (!paths.add(target.path)) throw new IllegalStateException("缓存目标重复，请重新检查");
                operations.verify(target);
            }
            for (Target target : targets) {
                output.append(target.plan.pkg).append("：")
                        .append(operations.repair(target)).append('\n');
                completed++;
            }
            output.append("已完成 ").append(completed).append('/').append(targets.size())
                    .append(" 项。请完整重启后重新检查。");
        } catch (Exception error) {
            output.append("已确认完成 ").append(completed).append('/').append(targets.size())
                    .append(" 项；批量操作已停止：").append(error.getMessage())
                    .append("\n未确认的项目请重新检查；已完成项目的备份保留，不自动重试。");
        }
        return output.toString();
    }
}
