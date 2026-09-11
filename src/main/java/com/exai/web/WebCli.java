package com.exai.web;

import com.exai.config.Config;
import com.exai.service.LLMService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页终端的自然语言配置助手。
 *
 * <p>终端输入默认作为自然语言交给 LLM，由 {@link PluginConfigTool} 暴露的受限工具读取/提出
 * YAML 配置修改；写入前必须先生成预览和确认 ID，再由用户输入 {@code 确认 <id>} 执行。
 */
public final class WebCli {

    private WebCli() {}

    public interface StreamSink {
        void meta(Map<String, Object> data) throws IOException;
        void status(String message) throws IOException;
        void delta(String text) throws IOException;
        void done(Map<String, Object> data) throws IOException;
        void error(Map<String, Object> data) throws IOException;
    }

    /** 执行一条终端输入，返回 {ok, cwd, output, aiSessionId}。 */
    public static Map<String, Object> exec(String cmdLine, String cwd) {
        return exec(cmdLine, cwd, null);
    }

    public static Map<String, Object> exec(String cmdLine, String cwd, String aiSessionId) {
        String normCwd = normCwd(cwd);
        String sid = WebAiSessionStore.ensure(aiSessionId);
        Map<String, Object> res = baseResult(true, normCwd, sid);
        try {
            String input = cmdLine == null ? "" : cmdLine.trim();
            if (input.isEmpty()) {
                return res;
            }

            String control = handleControl(input, sid);
            if (control != null) {
                res.put("output", control);
                return res;
            }

            res.put("output", doAi(normCwd, normalizeRequest(input), sid));
        } catch (CliException ce) {
            res.put("ok", false);
            res.put("output", ce.getMessage());
        } catch (SecurityException se) {
            res.put("ok", false);
            res.put("output", "拒绝: " + se.getMessage());
        } catch (Exception e) {
            res.put("ok", false);
            res.put("output", "错误: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return res;
    }

    public static void execStream(String cmdLine, String cwd, String aiSessionId, StreamSink sink) throws IOException {
        String normCwd = normCwd(cwd);
        String sid = WebAiSessionStore.ensure(aiSessionId);
        sink.meta(baseResult(true, normCwd, sid));
        try {
            String input = cmdLine == null ? "" : cmdLine.trim();
            if (input.isEmpty()) {
                sink.done(baseResult(true, normCwd, sid));
                return;
            }

            String control = handleControl(input, sid);
            if (control != null) {
                Map<String, Object> done = baseResult(true, normCwd, sid);
                done.put("output", control);
                sink.delta(control);
                sink.done(done);
                return;
            }

            List<String> confirmationIds = new ArrayList<>();
            String output = doAiStream(normCwd, normalizeRequest(input), sid, sink, confirmationIds);
            Map<String, Object> done = baseResult(true, normCwd, sid);
            done.put("output", output);
            done.put("confirmationIds", confirmationIds);
            sink.done(done);
        } catch (CliException ce) {
            sink.error(errorResult(normCwd, sid, ce.getMessage()));
        } catch (SecurityException se) {
            sink.error(errorResult(normCwd, sid, "拒绝: " + se.getMessage()));
        } catch (Exception e) {
            sink.error(errorResult(normCwd, sid, "错误: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static Map<String, Object> baseResult(boolean ok, String cwd, String sessionId) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", ok);
        res.put("cwd", cwd);
        res.put("aiSessionId", sessionId);
        res.put("output", "");
        return res;
    }

    private static Map<String, Object> errorResult(String cwd, String sessionId, String message) {
        Map<String, Object> res = baseResult(false, cwd, sessionId);
        res.put("output", message);
        res.put("error", message);
        return res;
    }

    private static String handleControl(String input, String sessionId) {
        List<Tok> toks = tokenize(input);
        if (toks.isEmpty()) {
            return "";
        }
        int i = "ai".equalsIgnoreCase(toks.get(0).text) ? 1 : 0;
        if (i >= toks.size()) {
            return help();
        }
        String rawOp = toks.get(i).text;
        if (!rawOp.startsWith("/")) {
            return null;
        }
        String op = rawOp.substring(1);
        if (op.isEmpty()) {
            return help();
        }
        String lower = op.toLowerCase();

        if ("help".equals(lower) || "?".equals(op) || "帮助".equals(op)) {
            return help();
        }
        if ("reset".equals(lower) || "重置".equals(op) || "重置对话".equals(op) || "清空对话".equals(op)) {
            WebAiSessionStore.reset(sessionId);
            return "对话已重置";
        }
        if ("history".equals(lower) || "历史".equals(op) || "对话历史".equals(op)) {
            return "当前对话已记住 " + WebAiSessionStore.size(sessionId)
                    + " 轮；可撤回轮次: " + WebAiSessionStore.turnIds(sessionId);
        }
        if (i + 1 < toks.size() && isRewindWord(op) && isTurnId(toks.get(i + 1).text)) {
            return rewindFromTurn(sessionId, Long.parseLong(toks.get(i + 1).text));
        }
        if (i + 1 < toks.size() && isConfirmWord(op) && isLikelyId(toks.get(i + 1).text)) {
            return PluginConfigTool.confirmPending(toks.get(i + 1).text);
        }
        if (i + 1 < toks.size() && isCancelWord(op) && isLikelyId(toks.get(i + 1).text)) {
            return PluginConfigTool.cancelPending(toks.get(i + 1).text);
        }
        return null;
    }

    private static boolean isConfirmWord(String s) {
        return "confirm".equalsIgnoreCase(s)
                || "确认".equals(s)
                || "执行".equals(s)
                || "应用".equals(s)
                || "保存".equals(s);
    }

    private static boolean isCancelWord(String s) {
        return "cancel".equalsIgnoreCase(s)
                || "取消".equals(s)
                || "撤销".equals(s)
                || "放弃".equals(s);
    }

    private static boolean isRewindWord(String s) {
        return "rewind".equalsIgnoreCase(s)
                || "rollback".equalsIgnoreCase(s)
                || "撤回".equals(s)
                || "撤回到".equals(s);
    }

    private static boolean isTurnId(String s) {
        try {
            return s != null && Long.parseLong(s) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLikelyId(String s) {
        return s != null && s.matches("[A-Za-z0-9_-]{4,}");
    }

    private static String rewindFromTurn(String sessionId, long turnId) {
        if (!WebAiSessionStore.turnIds(sessionId).contains(turnId)) {
            return "轮次不存在或已不在当前会话记忆中: " + turnId;
        }
        PluginConfigTool.RollbackResult changes = PluginConfigTool.rollbackFromTurn(sessionId, turnId);
        if (!changes.isOk()) {
            return changes.getError();
        }
        WebAiSessionStore.RewindResult conversation = WebAiSessionStore.rewindFrom(sessionId, turnId);
        if (!conversation.isOk()) {
            return conversation.getError();
        }
        return "已撤回第 " + turnId + " 轮及后续对话 " + conversation.getRemovedTurns()
                + " 轮，并恢复配置修改 " + changes.getRevertedChanges() + " 项";
    }

    private static String doAi(String cwd, String request, String sessionId) {
        if (Config.llm == null) {
            throw new CliException("LLM 未初始化，请检查 llm 配置");
        }
        if (request.trim().isEmpty()) {
            throw new CliException("请输入自然语言需求，例如：帮我找一下远征插件的配置文件有哪些");
        }
        JsonArray history = WebAiSessionStore.history(sessionId);
        long turnId = WebAiSessionStore.beginTurn(sessionId);
        LLMService.ToolChatResult r = Config.llm.completeWithTools(
                PluginConfigTool.buildSystemPrompt(cwd),
                history,
                request,
                PluginConfigTool.toolSchemas(),
                toolExecutor(cwd, sessionId, turnId),
                0.2,
                1000,
                10);
        if (!r.isOk()) {
            throw new CliException("AI 调用失败: " + r.getError());
        }
        WebAiSessionStore.appendExchange(sessionId, turnId, request, r.getContent());
        return r.getContent();
    }

    private static String doAiStream(final String cwd, String request, String sessionId, final StreamSink sink, final List<String> confirmationIds) {
        if (Config.llm == null) {
            throw new CliException("LLM 未初始化，请检查 llm 配置");
        }
        if (request.trim().isEmpty()) {
            throw new CliException("请输入自然语言需求，例如：帮我找一下远征插件的配置文件有哪些");
        }
        JsonArray history = WebAiSessionStore.history(sessionId);
        final long turnId = WebAiSessionStore.beginTurn(sessionId);
        LLMService.ToolChatResult r = Config.llm.completeWithToolsStreaming(
                PluginConfigTool.buildSystemPrompt(cwd),
                history,
                request,
                PluginConfigTool.toolSchemas(),
                toolExecutor(cwd, sessionId, turnId),
                new LLMService.ToolStreamListener() {
                    @Override
                    public void onContentDelta(String text) throws IOException {
                        sink.delta(text);
                    }

                    @Override
                    public void onStatus(String message) throws IOException {
                        sink.status(message);
                    }

                    @Override
                    public void onToolCallStarted(String name) throws IOException {
                        sink.status("正在调用工具: " + name);
                    }

                    @Override
                    public void onToolCallFinished(String name, JsonObject result) throws IOException {
                        if (result != null && result.has("confirmation_id") && !result.get("confirmation_id").isJsonNull()) {
                            confirmationIds.add(result.get("confirmation_id").getAsString());
                            sink.status("已生成修改预览: " + result.get("confirmation_id").getAsString());
                        } else {
                            sink.status("工具完成: " + name);
                        }
                    }
                },
                0.2,
                1000,
                10);
        if (!r.isOk()) {
            throw new CliException("AI 调用失败: " + r.getError());
        }
        WebAiSessionStore.appendExchange(sessionId, turnId, request, r.getContent());
        return r.getContent();
    }

    private static LLMService.ToolExecutor toolExecutor(final String cwd, final String sessionId, final long turnId) {
        return new LLMService.ToolExecutor() {
            @Override
            public JsonObject execute(String name, JsonObject arguments) {
                return PluginConfigTool.executeTool(cwd, name, arguments, sessionId, turnId);
            }
        };
    }

    /** 兼容旧输入：如果用户仍输入 ai <需求>，去掉 ai 前缀后作为自然语言处理。 */
    private static String normalizeRequest(String input) {
        List<Tok> toks = tokenize(input);
        if (!toks.isEmpty() && "ai".equalsIgnoreCase(toks.get(0).text)) {
            String s = input.trim();
            return s.length() <= 2 ? "" : s.substring(2).trim();
        }
        return input;
    }

    private static String help() {
        return "直接输入自然语言即可与配置助手对话。控制指令统一使用 / 前缀：\n"
                + "  /help                 显示本帮助\n"
                + "  /history              查看可撤回轮次\n"
                + "  /reset                清空对话记忆\n"
                + "  /confirm <id>         确认配置修改预览\n"
                + "  /cancel <id>          取消配置修改预览\n"
                + "  /rewind <turnId>      撤回该轮及后续对话和修改\n\n"
                + "安全范围：只访问 plugins/ 下的 .yml/.yaml；保存前自动备份 .bak。";
    }

    /** 归一化 cwd：反斜杠转正斜杠，去首尾斜杠。 */
    private static String normCwd(String cwd) {
        if (cwd == null) {
            return "";
        }
        String s = cwd.replace('\\', '/').trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 分词：空白分隔，支持单/双引号包裹带空格的参数，并记录该 token 是否被引号包裹。 */
    private static List<Tok> tokenize(String line) {
        List<Tok> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inTok = false;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inTok = true;
                quoted = true;
            } else if (c == ' ' || c == '\t') {
                if (inTok) {
                    out.add(new Tok(cur.toString(), quoted));
                    cur.setLength(0);
                    inTok = false;
                    quoted = false;
                }
            } else {
                cur.append(c);
                inTok = true;
            }
        }
        if (inTok) {
            out.add(new Tok(cur.toString(), quoted));
        }
        return out;
    }

    private static final class Tok {
        final String text;
        final boolean quoted;

        Tok(String text, boolean quoted) {
            this.text = text;
            this.quoted = quoted;
        }
    }

    /** 面向用户的命令错误：消息直接作为终端输出（ok=false）。 */
    private static final class CliException extends RuntimeException {
        CliException(String message) {
            super(message);
        }
    }
}
