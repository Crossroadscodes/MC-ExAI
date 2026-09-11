package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.entity.KnowledgeEntry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeFileManager {
    public static final String FILE_NAME = "knowledge/knowledge.yml";
    /** 旧版本知识库文件位置（plugins/ExAI/knowledge.yml），用于平滑迁移 */
    private static final String LEGACY_FILE_NAME = "knowledge.yml";

    public static File knowledgeFile() {
        return new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
    }

    /**
     * 把旧位置 knowledge.yml 迁移到新的 knowledge/ 目录下。
     * 仅当旧文件存在且新文件尚不存在时执行，避免老服升级后丢数据。
     * 需在知识库加载之前调用（onEnable 早期）。
     */
    public static void migrateLegacyLocation() {
        File legacy = new File(ExAI.getInstance().getDataFolder(), LEGACY_FILE_NAME);
        File current = knowledgeFile();
        if (legacy.exists() && !current.exists()) {
            File dir = current.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            if (legacy.renameTo(current)) {
                ExAI.getInstance().getLogger().info("已迁移 knowledge.yml -> knowledge/knowledge.yml");
            } else {
                ExAI.getInstance().getLogger().warning("迁移 knowledge.yml 到 knowledge/ 目录失败，请手动移动");
            }
        }
    }

    /**
     * 内存缓存：运行时所有同步读取都走它，避免在主线程做阻塞式 IO/JDBC（卡服）。
     * 写操作先更新缓存（立即生效），再把整份快照异步落盘/落库。
     */
    private static volatile List<KnowledgeEntry> cache = null;
    /** 序列化持久化操作，避免并发 TRUNCATE+插入互相覆盖 */
    private static final Object persistLock = new Object();
    /** 序列化读改写（append/update/delete），避免丢更新 */
    private static final Object mutateLock = new Object();

    /** mysql 存储模式下，运行时知识库从数据库 exai_knowledge 表读写，而非本地文件。 */
    private static boolean useMysql() {
        return DataContainer.storage != null && "mysql".equalsIgnoreCase(Config.storageType);
    }

    /** 从底层存储（文件或 DB）强制刷新内存缓存。可能阻塞，应在异步线程或启动时调用。 */
    public static synchronized void loadCache() {
        cache = useMysql() ? DataContainer.storage.readKnowledge() : readAllFromFile();
    }

    /**
     * 让缓存失效，下次读取时从底层存储重新加载。
     * 用于「完整重载」（/exai reload、导入数据库后）以拾取外部对源的改动；
     * 写操作后的轻量重载不应调用，以免覆盖刚写入缓存的内容。
     */
    public static void invalidateCache() {
        cache = null;
    }

    private static List<KnowledgeEntry> ensureCache() {
        List<KnowledgeEntry> c = cache;
        if (c == null) {
            loadCache();
            c = cache;
        }
        return c;
    }

    public static List<KnowledgeEntry> readAll() {
        return new ArrayList<>(ensureCache());
    }

    public static void writeAll(List<KnowledgeEntry> entries) {
        cache = new ArrayList<>(entries);   // 内存即时生效
        persistAsync();                     // 落盘/落库异步进行，不阻塞主线程
    }

    /**
     * 把当前缓存持久化到底层存储；主线程调用时转异步，已在异步线程则直接写。
     * 持久化时读取“执行时刻”的缓存而非调用时快照：即便多次写入的异步任务乱序执行，
     * 最终也都会把数据库/文件收敛到最新缓存，不会出现 DB 落后于内存的情况。
     */
    private static void persistAsync() {
        Runnable task = () -> {
            synchronized (persistLock) {
                List<KnowledgeEntry> current = cache;
                if (current == null) {
                    return; // 已失效（完整重载中），交由重载从源刷新，避免写空
                }
                List<KnowledgeEntry> snapshot = new ArrayList<>(current);
                if (useMysql()) {
                    DataContainer.storage.writeKnowledge(snapshot);
                } else {
                    writeAllToFile(snapshot);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), task);
        } else {
            task.run();
        }
    }

    /** 始终从本地 knowledge.yml 读取（与存储模式无关），用于「文件导入数据库」等场景。 */
    public static List<KnowledgeEntry> readAllFromFile() {
        List<KnowledgeEntry> list = new ArrayList<>();
        File file = knowledgeFile();
        if (!file.exists()) {
            return list;
        }
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> raw = conf.getMapList("entries");
        for (Map<?, ?> m : raw) {
            Object q = m.get("question");
            Object a = m.get("answer");
            if (q == null || a == null) continue;
            list.add(new KnowledgeEntry(q.toString(), a.toString(), "", 0L));
        }
        return list;
    }

    /** 始终写入本地 knowledge.yml（与存储模式无关）。 */
    public static void writeAllToFile(List<KnowledgeEntry> entries) {
        File file = knowledgeFile();
        YamlConfiguration conf = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", e.getQuestion());
            m.put("answer", e.getAnswer());
            list.add(m);
        }
        conf.set("entries", list);
        try {
            conf.save(file);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void append(KnowledgeEntry entry) {
        synchronized (mutateLock) {
            List<KnowledgeEntry> all = readAll();
            all.add(entry);
            writeAll(all);
        }
    }

    public static void deleteByIndex(int index) {
        synchronized (mutateLock) {
            List<KnowledgeEntry> all = readAll();
            if (index < 0 || index >= all.size()) return;
            all.remove(index);
            writeAll(all);
        }
    }

    public static void updateByIndex(int index, KnowledgeEntry entry) {
        synchronized (mutateLock) {
            List<KnowledgeEntry> all = readAll();
            if (index < 0 || index >= all.size()) return;
            all.set(index, entry);
            writeAll(all);
        }
    }

    public static boolean isDuplicateQuestion(String question) {
        String normalized = question.trim().toLowerCase();
        for (KnowledgeEntry entry : readAll()) {
            if (entry.getQuestion().trim().toLowerCase().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static void reloadKnowledgeBaseAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), Config::reloadKnowledgeBaseOnly);
    }
}
