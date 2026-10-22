package local.pudding.weathercompat;

import java.util.*;
import java.util.regex.*;

/** Pure parser: discard exited/reused PIDs and keep old module versions visibly separate. */
public final class EvidenceReport {
    private EvidenceReport() {}
    /** A loaded hook is weaker evidence than a handled call; missing logs stay unknown. */
    public static String summarize(String input, String currentVersion) {
        Map<String, String> live = new LinkedHashMap<>();
        for (String line : input.split("\n")) {
            String[] f = line.split("\\|");
            if (f.length == 4 && f[0].equals("LIVE")) live.put(f[1] + ":" + f[2], f[3]);
        }
        Map<String, Set<String>> events = new LinkedHashMap<>();
        Set<String> old = new HashSet<>();
        Pattern pattern = Pattern.compile("v=(\\d+) token=(\\d+:\\d+) feature=(\\S+) event=(.+)$");
        for (String line : input.split("\n")) {
            Matcher m = pattern.matcher(line);
            if (!m.find() || !live.containsKey(m.group(2))) continue;
            if (!m.group(1).equals(currentVersion)) { old.add(m.group(2)); continue; }
            Set<String> values = events.get(m.group(2));
            if (values == null) { values = new HashSet<>(); events.put(m.group(2), values); }
            values.add(m.group(4));
        }
        StringBuilder out = new StringBuilder("检查结果\n\n");
        boolean foundState = false;
        int marker = input.indexOf("STATE_BEGIN\n");
        if (marker >= 0) {
            for (String line : input.substring(marker + 12).split("\n")) {
                if (!line.contains("action=") || !line.contains("status=")) continue;
                if (line.contains("status=completed")) out.append("最近的启动恢复任务：已完成。\n");
                else if (line.contains("status=running")) out.append("最近的启动恢复任务：正在处理，请稍后再检查。\n");
                else out.append("最近的启动恢复任务：尚未完成，需查看详细记录。\n");
                foundState = true;
                break;
            }
        }
        if (!foundState) out.append("最近的启动恢复任务：暂无记录。\n");
        out.append("这是最近一次任务记录，不表示所有应用功能都正常。\n\n兼容层加载情况\n");
        for (Map.Entry<String,String> e : live.entrySet()) {
            Set<String> values = events.get(e.getKey());
            out.append(appName(e.getValue())).append("：");
            if (old.contains(e.getKey())) out.append(values == null ? "仍有旧版加载记录，请完整重启。" : "新旧版本记录同时存在，请完整重启后复查。");
            else if (values == null) out.append("暂无当前进程记录，暂不能判断。");
            else if (values.contains("APPLIED") || values.contains("HIT")) out.append("已执行兼容处理。");
            else out.append("已有加载记录，尚未看到实际处理。");
            if (values != null && values.contains("UNAVAILABLE"))
                out.append("部分接口不支持，需查看详细记录；不等于整个模块失效。");
            out.append('\n');
        }
        if (live.isEmpty()) out.append("未能取得正在运行的目标应用，暂不能判断。\n");
        out.append("\n下一步\n正常打开要检查的应用，再点一次检查。暂无记录也可能是日志已覆盖，并不代表未启用。\n")
            .append("若刚更新兼容层或修改作用域，请完整重启。若仍有闪退、名称错乱等现象，请记录出问题的应用和操作。\n")
            .append("这里只检查加载记录和恢复任务，不检测所有业务，也不会清理缓存或数据。\n");
        return out.toString();
    }

    private static String appName(String process) {
        String base = process.split(":", 2)[0];
        String name;
        switch (base) {
            case "system_server": name = "系统服务"; break;
            case "com.android.systemui": name = "状态栏与通知界面"; break;
            case "com.android.settings": name = "设置"; break;
            case "com.android.mms": name = "短信"; break;
            case "com.android.phone": name = "电话服务"; break;
            case "com.android.contacts": name = "电话与联系人"; break;
            case "com.android.providers.downloads":
            case "com.android.providers.downloads.ui": name = "下载管理"; break;
            case "com.android.thememanager": name = "主题"; break;
            case "com.miui.themestore": name = "独立主题商店入口"; break;
            case "com.miui.core": name = "小米系统服务"; break;
            case "com.miui.securitycore": name = "双开与系统功能服务"; break;
            case "com.miui.home": name = "桌面"; break;
            case "com.miui.powerkeeper": name = "后台与电量管理"; break;
            case "com.miui.weather2": name = "天气"; break;
            case "com.miui.yellowpage": name = "生活黄页"; break;
            case "com.miui.notification": name = "通知管理"; break;
            case "com.miui.securitycenter": name = "手机管家"; break;
            case "com.xiaomi.mirror": name = "设备互联"; break;
            case "local.pudding.weathercompat": name = "本地化兼容层"; break;
            default: name = process; break;
        }
        return process.contains(":") ? name + "（后台进程）" : name;
    }

    public static String render(String input) {
        Map<String, String> live = new LinkedHashMap<>();
        for (String line : input.split("\n")) {
            String[] f = line.split("\\|");
            if (f.length == 4 && f[0].equals("LIVE")) live.put(f[1] + ":" + f[2], f[3]);
        }
        Pattern pattern = Pattern.compile("v=(\\d+) token=(\\d+:\\d+) feature=(\\S+) event=(.+)$");
        Set<String> observed = new HashSet<>();
        Set<String> lines = new LinkedHashSet<>();
        for (String line : input.split("\n")) {
            Matcher m = pattern.matcher(line);
            if (!m.find() || !live.containsKey(m.group(2))) continue;
            observed.add(m.group(2));
            String version = m.group(1).equals(local.pudding.eulocalization.HookEvidence.VERSION) ? "当前版本" : "旧版本 " + m.group(1) + "，需重启";
            lines.add(live.get(m.group(2)) + " [" + version + "] " + m.group(3) + "：" + m.group(4));
        }
        StringBuilder out = new StringBuilder("仅列当前存活进程且启动标识相符的日志。\nENTRY=已加载入口；REGISTERED=接口已挂接；HIT/APPLIED=已执行对应处理；UNAVAILABLE=接口不可用。均不代表业务验收成功。\n");
        for (String line : lines) out.append(line).append('\n');
        for (Map.Entry<String,String> e : live.entrySet()) if (!observed.contains(e.getKey()))
            out.append(e.getValue()).append("：未见当前进程记录，请核对作用域/版本；也可能日志已轮转。\n");
        if (live.isEmpty()) out.append("未取得目标进程列表，状态未知。\n");
        out.append("未运行的应用需正常打开后再读取；修改开关后先重启对应进程，框架或旧版本切换需完整重启。\n");
        int state = input.indexOf("STATE_BEGIN\n");
        if (state >= 0) out.append("\n模块启动/恢复记录：\n").append(input.substring(state + 12));
        return out.toString();
    }
}
