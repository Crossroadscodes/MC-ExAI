package com.exai.service;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 文档导入服务：把 plugins/ExAI/import/ 下的文档分片送入大模型，
 * 抽取成一条条「问/答」知识，写入待审核队列交由管理员复核。
 *
 * <p>支持格式：
 * <ul>
 *   <li>txt / md：直接读取纯文本；</li>
 *   <li>docx：用 JDK 内置 zip 解析 word/document.xml 抽取纯文本（不依赖第三方库，老 .doc 不支持）；</li>
 *   <li>图片(png/jpg/jpeg/gif/bmp/webp)：转 base64 交给视觉模型(需配置 visionModel)看图抽取。</li>
 * </ul>
 * 文本类按字符分片后逐片调模型；图片整张调一次视觉模型。两条路径共用下游入队与计数逻辑。
 *
 * <p>整体在异步线程执行（由命令层调度），网络调用不阻塞主线程；发给 sender 的消息统一回主线程发送。
 */
public class DocumentImportService {

    public static final String IMPORT_DIR = "import";

    private DocumentImportService() {}

    /** 导入结果计数。 */
    private static class Counters {
        int added, skipped, failed;
    }

    /** 确保导入文件夹存在，返回该目录。 */
    public static File importFolder() {
        File dir = new File(ExAI.getInstance().getDataFolder(), IMPORT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** tab 补全/校验用：是否为受支持的导入文件名。 */
    public static boolean isSupported(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".docx") || isImage(n);
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    /**
     * 导入指定文档。应在异步线程调用。
     */
    public static void importFile(CommandSender sender, String fileName) {
        try {
            String lower = fileName.toLowerCase();
            if (!isSupported(lower)) {
                send(sender, Lang.get("command.import-bad-ext", fileName));
                return;
            }
            File file = new File(importFolder(), fileName);
            if (!file.exists() || !file.isFile()) {
                send(sender, Lang.get("command.import-not-found", fileName));
                return;
            }
            if (Config.llm == null) {
                send(sender, Lang.get("command.import-not-found", fileName));
                return;
            }

            if (isImage(lower)) {
                importImage(sender, file, fileName, lower);
            } else {
                importText(sender, file, fileName, lower);
            }
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("Document import failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** txt / md / docx → 纯文本 → 分片 → 逐片抽取。 */
    private static void importText(CommandSender sender, File file, String fileName, String lower) throws Exception {
        String content = lower.endsWith(".docx")
                ? extractDocxText(file)
                : new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        List<String> chunks = splitIntoChunks(content, Config.documentImportChunkSize);
        if (chunks.isEmpty()) {
            send(sender, Lang.get("command.import-empty", fileName));
            return;
        }

        send(sender, Lang.get("command.import-started", fileName));

        Counters c = new Counters();
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            List<JsonObject> qaList = extractQaFromText(chunks.get(i));
            processQaList(qaList, fileName, c);
            send(sender, Lang.get("command.import-progress", i + 1, total, c.added));
        }
        finish(sender, fileName, c);
    }

    /** 图片 → base64 data URL → 视觉模型整张抽取一次。 */
    private static void importImage(CommandSender sender, File file, String fileName, String lower) throws Exception {
        if (Config.visionLlm == null) {
            send(sender, Lang.get("command.import-vision-disabled"));
            return;
        }
        send(sender, Lang.get("command.import-started", fileName));

        byte[] bytes = Files.readAllBytes(file.toPath());
        String dataUrl = "data:" + mimeOf(lower) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        String prompt = Lang.get("import.import-image");
        String raw = Config.visionLlm.completeVision(
                prompt, dataUrl, Config.documentImportTemperature, Config.documentImportMaxTokens);

        Counters c = new Counters();
        processQaList(parseQaArray(raw), fileName, c);
        finish(sender, fileName, c);
    }

    /** 把一批抽取出的问答写入待审核队列并累计计数（qaList 为 null 计一次失败）。 */
    private static void processQaList(List<JsonObject> qaList, String fileName, Counters c) {
        if (qaList == null) {
            c.failed++;
            return;
        }
        for (JsonObject qa : qaList) {
            String q = optString(qa, "question");
            String a = optString(qa, "answer");
            if (q == null || a == null || q.trim().isEmpty() || a.trim().isEmpty()) {
                c.failed++;
                continue;
            }
            if (KnowledgeManager.submitImported(q, a, fileName)) {
                c.added++;
            } else {
                c.skipped++;
            }
        }
    }

    private static void finish(CommandSender sender, String fileName, Counters c) {
        // import-done 汇总已提示「请到审核界面复核」，不再复用公屏自动采集的提醒(避免显示"公屏自动收集到一条问答")
        send(sender, Lang.get("command.import-done", fileName, c.added, c.skipped, c.failed));
    }

    /**
     * 用 JDK 内置 zip 从 .docx 抽取纯文本：读取 word/document.xml，
     * 以段落/换行为边界、剥离所有 XML 标签、还原实体。表格等结构会丢失，但足够抽取知识点。
     */
    static String extractDocxText(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                return "";
            }
            String xml;
            try (InputStream in = zip.getInputStream(entry)) {
                xml = new String(readAll(in), StandardCharsets.UTF_8);
            }
            xml = xml.replaceAll("(?i)</w:p>", "\n");          // 段落 → 换行
            xml = xml.replaceAll("(?i)<w:br[^>]*/?>", "\n");    // 换行符
            xml = xml.replaceAll("(?i)<w:tab[^>]*/?>", "\t");   // 制表符
            xml = xml.replaceAll("<[^>]+>", "");               // 剥离所有标签（保留 <w:t> 内文本）
            return unescapeXml(xml);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String mimeOf(String lower) {
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // jpg/jpeg
    }

    /**
     * 按段落/换行边界把文本切成不超过 maxChars 的片段，避免在句子中间切断。
     * 单个超长段落会按字符硬切。
     */
    static List<String> splitIntoChunks(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        int size = maxChars <= 0 ? 1500 : maxChars;
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\r?\\n")) {
            // 超长单段：先冲刷已累积内容，再硬切该段
            if (paragraph.length() > size) {
                flush(chunks, current);
                for (int start = 0; start < paragraph.length(); start += size) {
                    chunks.add(paragraph.substring(start, Math.min(start + size, paragraph.length())));
                }
                continue;
            }
            if (current.length() + paragraph.length() + 1 > size) {
                flush(chunks, current);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.length() > 0 && !current.toString().trim().isEmpty()) {
            chunks.add(current.toString().trim());
        }
        current.setLength(0);
    }

    /**
     * 调文本大模型抽取一个片段的问答列表。
     * @return 问答 JsonObject 列表；模型不可达或输出无法解析为数组时返回 {@code null}（计入 failed）。
     */
    private static List<JsonObject> extractQaFromText(String chunk) {
        String prompt = Lang.get("import.prompt") + "\n" + chunk;
        String raw = Config.llm.complete(prompt, Config.documentImportTemperature, Config.documentImportMaxTokens);
        return parseQaArray(raw);
    }

    /** 容错解析模型输出中的问答 JSON 数组；无法解析返回 null。 */
    private static List<JsonObject> parseQaArray(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            String json = extractJsonArray(raw);
            if (json == null) {
                return null;
            }
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<JsonObject> result = new ArrayList<>();
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            }
            return result;
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("Document import parse error: " + e.getMessage() + " raw=" + raw);
            return null;
        }
    }

    /** 容错地从模型输出中截取 JSON 数组主体（去掉 ```json 代码块或前后多余文字）。 */
    private static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String optString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /** 在主线程把消息发给命令发起者（异步线程不直接操作 Bukkit API）。 */
    private static void send(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> sender.sendMessage(message));
    }
}
