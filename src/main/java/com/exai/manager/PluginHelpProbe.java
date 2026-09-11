package com.exai.manager;

import com.exai.ExAI;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 以「服务器后台（控制台）」身份执行目标插件的 help 命令并抓取其输出，供自动生成插件描述使用。
 *
 * <p>相比早期的「虚拟玩家」方案：玩家代理不在服务器在线列表里，很多插件会按 UUID/名字把消息重新
 * 发给真实玩家对象，导致代理收不到。控制台发送者是插件从不会重新解析的合法接收者——插件直接
 * {@code sender.sendMessage(...)}，因此能稳定拦截。这里用一个实现 {@link ConsoleCommandSender}
 * 的动态代理捕获输出：sendMessage 系列收进缓冲区，isOp/hasPermission 返回 true，其余方法返回安全默认值。
 *
 * <p><b>线程模型</b>：{@link #captureHelp(Plugin)} 应在<b>异步线程</b>（如 Web 线程池）调用，
 * 内部只把每次 {@link Bukkit#dispatchCommand} 通过 {@code callSyncMethod} 短暂切到主线程执行，
 * 等待与清洗都在调用线程上完成，不长时间占用主线程。help 命令通常只读，副作用风险低。
 */
public final class PluginHelpProbe {

    private static final String CONSOLE_NAME = "CONSOLE";

    /** 单次 dispatch 后等待异步/延迟输出的最长时间（毫秒）。 */
    private static final long MAX_WAIT_MS = 1500L;
    /** 轮询缓冲区的间隔（毫秒）。 */
    private static final long POLL_MS = 50L;
    /** 命令执行后若始终没有任何输出，超过此时间就放弃该候选命令，快速试下一个。 */
    private static final long GRACE_MS = 300L;

    /**
     * plugin.yml 未声明权限时，无法可靠判断命令是否会造成管理操作。这些词命中时宁可不展示给普通玩家。
     * 对代码中动态注册、因而查不到默认值的权限节点则默认放行，兼容未在 plugin.yml 声明权限的插件。
     */
    private static final String[] DANGEROUS_COMMAND_WORDS = {
            "admin", "op", "deop", "reload", "restart", "shutdown", "stop",
            "ban", "pardon", "kick", "mute", "jail", "warn", "give", "item",
            "clear", "gamemode", "teleport", "tp", "vanish", "invsee", "sudo"
    };
    private static final String[] DANGEROUS_TEXT_WORDS = {
            "管理员", "管理命令", "后台", "权限", "重载", "重启", "关闭服务器",
            "封禁", "踢出", "禁言", "给予物品", "清空背包", "游戏模式", "传送玩家"
    };

    private PluginHelpProbe() {}

    /**
     * 异步线程调用：以控制台身份按优先级依次尝试若干候选命令（同名命令的 help、首个声明命令的 help、
     * 最后 Bukkit 内置 {@code /help <插件名>} 索引），用 dispatch 返回值跳过不存在的命令，等待异步
     * 输出落定；命中「像完整 help」的输出即返回，否则取最长的一份；全部抓不到时回退 plugin.yml 元数据。
     */
    public static String captureHelp(Plugin plugin) {
        if (plugin == null) {
            return "";
        }
        boolean primary = Bukkit.isPrimaryThread();
        Set<String> candidates = candidateCommands(plugin);
        String best = "";
        for (String cmd : candidates) {
            Buffer buffer = new Buffer();
            ConsoleCommandSender sender = newConsoleSender(buffer);
            boolean found = dispatch(sender, cmd, primary);
            if (!found) {
                continue; // 该命令不存在，试下一个候选
            }
            awaitOutput(buffer, primary);
            String got = clean(buffer.snapshot());
            if (got.isEmpty()) {
                continue;
            }
            if (looksSubstantial(got)) {
                return got; // 多行/较长，判定为完整 help，直接采用
            }
            if (got.length() > best.length()) {
                best = got; // 只有一行短输出（可能是用法/提示）：先留作兜底，继续试更完整的
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        ExAI.getInstance().getLogger().info(
                "[help] 未能从命令抓到 " + plugin.getName() + " 的输出，已回退 plugin.yml 元数据；尝试过: " + candidates);
        return fromDescription(plugin);
    }

    /** 多行或达到一定长度即视为「像完整 help」，可直接采用。 */
    private static boolean looksSubstantial(String text) {
        return text.indexOf('\n') >= 0 || text.length() >= 80;
    }

    /**
     * 构造按优先级排序、去重的候选命令行。只执行 help 形式，绝不为了抓取说明而直接执行插件命令，
     * 避免误触发带副作用的第三方命令。
     * 优先与插件名同名的命令的 {@code help}，其次第一个声明命令的 help，最后用 Bukkit 内置
     * {@code /help <插件名>}——它依据 plugin.yml 自动生成命令索引，几乎对所有插件都有输出，作为最通用兜底。
     */
    private static Set<String> candidateCommands(Plugin plugin) {
        Set<String> out = new LinkedHashSet<>();
        String pluginName = plugin.getName();
        String pn = pluginName.toLowerCase();
        Map<String, Map<String, Object>> commands = plugin.getDescription().getCommands();

        String named = null;
        String first = null;
        if (commands != null && !commands.isEmpty()) {
            first = commands.keySet().iterator().next();
            for (String k : commands.keySet()) {
                if (k.equalsIgnoreCase(pluginName)) {
                    named = k;
                    break;
                }
            }
        }
        if (named != null) {
            out.add(named + " help");
        }
        out.add(pn + " help");
        if (first != null) {
            out.add(first + " help");
        }
        out.add("help " + pluginName); // Bukkit 内置帮助索引，最通用兜底
        return out;
    }

    /**
     * 在主线程上分发一条命令；调用线程已是主线程时直接执行，否则切到主线程并等待其完成。
     * 返回该命令是否被找到（{@code Bukkit.dispatchCommand} 的结果），用于跳过不存在的候选命令。
     */
    private static boolean dispatch(CommandSender sender, String commandLine, boolean primary) {
        if (primary) {
            return runDispatch(sender, commandLine);
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(ExAI.getInstance(),
                    () -> runDispatch(sender, commandLine)).get();
        } catch (Exception ignored) {
            return false; // 主线程任务被拒/中断
        }
    }

    private static boolean runDispatch(CommandSender sender, String commandLine) {
        try {
            return Bukkit.dispatchCommand(sender, commandLine);
        } catch (Throwable ignored) {
            // 命令被找到但执行中抛错：算作已找到，保留已收集到的部分
            return true;
        }
    }

    /**
     * 在调用线程上等待缓冲区里的输出落定：有内容且连续两次轮询长度不变即认为结束，最长 {@link #MAX_WAIT_MS}；
     * 过了 {@link #GRACE_MS} 仍无任何输出则快速放弃。主线程禁止 sleep，故主线程调用时直接返回。
     */
    private static void awaitOutput(Buffer buffer, boolean primary) {
        if (primary) {
            return;
        }
        long start = System.currentTimeMillis();
        long deadline = start + MAX_WAIT_MS;
        int stable = 0;
        int last = -1;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS);
                int len = buffer.length();
                if (len == 0) {
                    if (System.currentTimeMillis() - start >= GRACE_MS) {
                        return;
                    }
                    continue;
                }
                if (len == last) {
                    if (++stable >= 2) {
                        break;
                    }
                } else {
                    stable = 0;
                }
                last = len;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 返回适合普通玩家阅读的命令说明。声明了 permission 的命令会根据其默认权限过滤；
     * 未声明权限而名称/说明看起来具有管理风险的命令也会保守过滤。
     */
    public static String publicCommandSummary(Plugin plugin) {
        StringBuilder sb = new StringBuilder();
        Map<String, Map<String, Object>> commands = plugin.getDescription().getCommands();
        if (commands != null) {
            for (Map.Entry<String, Map<String, Object>> e : commands.entrySet()) {
                Map<String, Object> meta = e.getValue();
                if (!isAvailableToRegularPlayer(e.getKey(), meta)) {
                    continue;
                }
                sb.append('/').append(e.getKey());
                Object desc = meta == null ? null : meta.get("description");
                if (desc != null && !desc.toString().trim().isEmpty()) {
                    sb.append(" - ").append(desc.toString().trim());
                }
                sb.append('\n');
                Object usage = meta == null ? null : meta.get("usage");
                if (usage != null && !usage.toString().trim().isEmpty()) {
                    sb.append("  ").append(usage.toString().trim().replace("\n", " ")).append('\n');
                }
            }
        }
        return clean(sb.toString());
    }

    /** 抓不到 help 时的兜底：插件简介加上仅普通玩家可用的命令说明。 */
    private static String fromDescription(Plugin plugin) {
        StringBuilder sb = new StringBuilder();
        String d = plugin.getDescription().getDescription();
        if (d != null && !d.trim().isEmpty()) {
            sb.append(d.trim()).append('\n');
        }
        String commands = publicCommandSummary(plugin);
        if (!commands.isEmpty()) {
            sb.append(commands);
        }
        return clean(sb.toString());
    }

    /** 从 help 输出删除可识别的非普通玩家命令行，避免它们进入大模型的描述素材。 */
    public static String filterForRegularPlayers(Plugin plugin, String rawHelp) {
        if (rawHelp == null || rawHelp.trim().isEmpty()) {
            return "";
        }
        Map<String, Map<String, Object>> commands = plugin.getDescription().getCommands();
        if (commands == null || commands.isEmpty()) {
            return clean(rawHelp);
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : commands.entrySet()) {
            if (!isAvailableToRegularPlayer(entry.getKey(), entry.getValue())) {
                hidden.add(entry.getKey().toLowerCase(Locale.ROOT));
            }
        }
        if (hidden.isEmpty()) {
            return clean(rawHelp);
        }
        StringBuilder kept = new StringBuilder();
        for (String line : rawHelp.split("\\r?\\n")) {
            if (!mentionsHiddenCommand(line, hidden)) {
                kept.append(line).append('\n');
            }
        }
        return clean(kept.toString());
    }

    private static boolean isAvailableToRegularPlayer(String command, Map<String, Object> meta) {
        Object permissionValue = meta == null ? null : meta.get("permission");
        if (permissionValue != null && !permissionValue.toString().trim().isEmpty()) {
            Permission permission = Bukkit.getPluginManager().getPermission(permissionValue.toString().trim());
            if (permission == null) {
                return !looksDangerous(command, meta); // 兼容代码动态注册/未声明默认值的权限节点
            }
            PermissionDefault def = permission.getDefault();
            return def == PermissionDefault.TRUE || def == PermissionDefault.NOT_OP;
        }
        return !looksDangerous(command, meta);
    }

    private static boolean looksDangerous(String command, Map<String, Object> meta) {
        StringBuilder text = new StringBuilder(command == null ? "" : command);
        if (meta != null) {
            Object desc = meta.get("description");
            Object usage = meta.get("usage");
            if (desc != null) text.append(' ').append(desc);
            if (usage != null) text.append(' ').append(usage);
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        for (String word : DANGEROUS_TEXT_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        for (String word : DANGEROUS_COMMAND_WORDS) {
            if (lower.matches(".*(?:^|[^a-z])" + word + "(?:$|[^a-z]).*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsHiddenCommand(String line, Set<String> hidden) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String command : hidden) {
            if (lower.matches(".*(?:^|[^a-z0-9_])/?" + java.util.regex.Pattern.quote(command)
                    + "(?:$|[\\s:<]).*")) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = ChatColor.stripColor(raw);
        StringBuilder out = new StringBuilder();
        boolean prevBlank = false;
        for (String line : stripped.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!prevBlank && out.length() > 0) {
                    out.append('\n');
                }
                prevBlank = true;
            } else {
                out.append(t).append('\n');
                prevBlank = false;
            }
        }
        return out.toString().trim();
    }

    /** 收集 sendMessage 文本的缓冲区；插件可能从异步线程发消息，故线程安全。 */
    private static final class Buffer {
        private final StringBuilder sb = new StringBuilder();

        synchronized void append(String s) {
            if (s != null && !s.isEmpty()) {
                sb.append(s).append('\n');
            }
        }

        synchronized int length() {
            return sb.length();
        }

        synchronized String snapshot() {
            return sb.toString();
        }
    }

    private static ConsoleCommandSender newConsoleSender(Buffer buffer) {
        InvocationHandler handler = (proxy, method, args) -> handle(buffer, proxy, method, args);
        return (ConsoleCommandSender) Proxy.newProxyInstance(
                PluginHelpProbe.class.getClassLoader(),
                new Class[]{ConsoleCommandSender.class},
                handler);
    }

    private static Object handle(Buffer buffer, Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "sendMessage":
            case "sendRawMessage":
                appendArg(buffer, args);
                return null;
            case "spigot":
                return new VirtualSpigot(buffer);
            case "getName":
                return CONSOLE_NAME;
            case "isOp":
            case "hasPermission":
            case "isPermissionSet":
                return Boolean.TRUE;
            case "isConversing":
                return Boolean.FALSE;
            case "getServer":
                return Bukkit.getServer();
            case "getEffectivePermissions":
                return Collections.emptySet();
            case "equals":
                return proxy == (args != null ? args[0] : null);
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return CONSOLE_NAME;
            default:
                return defaultValue(method.getReturnType());
        }
    }

    private static void appendArg(Buffer buffer, Object[] args) {
        if (args == null) {
            return;
        }
        for (Object a : args) {
            if (a instanceof String) {
                buffer.append((String) a);
            } else if (a instanceof String[]) {
                for (String s : (String[]) a) {
                    buffer.append(s);
                }
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return (char) 0;
        if (type == void.class) return null;
        // 数字类型
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        return 0;
    }

    /** 捕获通过 spigot() 发送的富文本消息（控制台 Spigot 只有 BaseComponent 两个重载）。 */
    private static final class VirtualSpigot extends CommandSender.Spigot {
        private final Buffer buffer;

        VirtualSpigot(Buffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void sendMessage(BaseComponent... components) {
            if (components != null) {
                for (BaseComponent c : components) {
                    if (c != null) {
                        buffer.append(c.toLegacyText());
                    }
                }
            }
        }

        @Override
        public void sendMessage(BaseComponent component) {
            if (component != null) {
                buffer.append(component.toLegacyText());
            }
        }
    }
}
