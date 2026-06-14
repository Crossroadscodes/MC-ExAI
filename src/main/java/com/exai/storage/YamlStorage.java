package com.exai.storage;

import com.exai.ExAI;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;
import com.exai.i18n.Lang;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class YamlStorage implements DataStorage {

    private static final String LOG_FILE = "data/ai_log.yml";
    private static final String PENDING_FILE = "data/pending_knowledge.yml";
    private static final String COUNT_FILE = "data/pending_count.yml";
    private static final String REWARD_FILE = "data/pending_rewards.yml";

    private final Object logLock = new Object();
    private final Object pendingLock = new Object();
    private final Object countLock = new Object();
    private final Object rewardLock = new Object();

    private File logFile;
    private File pendingFile;
    private File countFile;
    private File rewardFile;

    private YamlConfiguration logConf;
    private YamlConfiguration pendingConf;
    private YamlConfiguration countConf;
    private YamlConfiguration rewardConf;

    @Override
    public void initialize() {
        File dataDir = new File(ExAI.getInstance().getDataFolder(), "data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        logFile = new File(ExAI.getInstance().getDataFolder(), LOG_FILE);
        pendingFile = new File(ExAI.getInstance().getDataFolder(), PENDING_FILE);
        countFile = new File(ExAI.getInstance().getDataFolder(), COUNT_FILE);
        rewardFile = new File(ExAI.getInstance().getDataFolder(), REWARD_FILE);

        logConf = YamlConfiguration.loadConfiguration(logFile);
        pendingConf = YamlConfiguration.loadConfiguration(pendingFile);
        countConf = YamlConfiguration.loadConfiguration(countFile);
        rewardConf = YamlConfiguration.loadConfiguration(rewardFile);

        if (!logConf.contains("next-id")) {
            logConf.set("next-id", 1);
            saveQuietly(logConf, logFile);
        }
        if (!pendingConf.contains("next-id")) {
            pendingConf.set("next-id", 1);
            saveQuietly(pendingConf, pendingFile);
        }

        System.out.println(Lang.get("log.yml-store-ready"));
        loadAllPendingKnowledge();
    }

    @Override
    public void shutdown() {
        synchronized (logLock) {
            saveQuietly(logConf, logFile);
        }
        synchronized (pendingLock) {
            saveQuietly(pendingConf, pendingFile);
        }
        synchronized (countLock) {
            saveQuietly(countConf, countFile);
        }
        synchronized (rewardLock) {
            saveQuietly(rewardConf, rewardFile);
        }
    }

    private void saveQuietly(YamlConfiguration conf, File file) {
        try {
            conf.save(file);
        } catch (IOException e) {
            System.err.println("Failed to save yml: " + file.getName() + " -> " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public int getPendingCount(String uuid) {
        synchronized (countLock) {
            return countConf.getInt("players." + uuid + ".count", 0);
        }
    }

    @Override
    public void addPendingCount(String uuid, String playerName) {
        synchronized (countLock) {
            String base = "players." + uuid;
            int current = countConf.getInt(base + ".count", 0);
            countConf.set(base + ".name", playerName);
            countConf.set(base + ".count", current + 1);
            saveQuietly(countConf, countFile);
        }
    }

    @Override
    public void subPendingCount(String uuid) {
        synchronized (countLock) {
            String base = "players." + uuid;
            int current = countConf.getInt(base + ".count", 0);
            int next = Math.max(0, current - 1);
            countConf.set(base + ".count", next);
            saveQuietly(countConf, countFile);
        }
    }

    @Override
    public int insertPendingKnowledge(KnowledgeEntry entry) {
        synchronized (pendingLock) {
            int id = pendingConf.getInt("next-id", 1);
            String base = "entries." + id;
            pendingConf.set(base + ".question", entry.getQuestion());
            pendingConf.set(base + ".answer", entry.getAnswer());
            pendingConf.set(base + ".submitter", entry.getSubmitter());
            pendingConf.set(base + ".timestamp", entry.getTimestamp());
            pendingConf.set(base + ".source", entry.getSource());
            pendingConf.set(base + ".thanked", entry.isThanked());
            pendingConf.set("next-id", id + 1);
            saveQuietly(pendingConf, pendingFile);
            return id;
        }
    }

    @Override
    public void deletePendingKnowledge(int id) {
        synchronized (pendingLock) {
            pendingConf.set("entries." + id, null);
            saveQuietly(pendingConf, pendingFile);
        }
    }

    @Override
    public void loadAllPendingKnowledge() {
        KnowledgeQueue.clearAll();
        synchronized (pendingLock) {
            ConfigurationSection section = pendingConf.getConfigurationSection("entries");
            if (section != null) {
                List<Integer> ids = new ArrayList<>();
                for (String key : section.getKeys(false)) {
                    try {
                        ids.add(Integer.parseInt(key));
                    } catch (NumberFormatException ignored) {
                    }
                }
                Collections.sort(ids);
                for (Integer id : ids) {
                    String base = "entries." + id;
                    String question = pendingConf.getString(base + ".question", "");
                    String answer = pendingConf.getString(base + ".answer", "");
                    String submitter = pendingConf.getString(base + ".submitter", "");
                    long timestamp = pendingConf.getLong(base + ".timestamp", 0L);
                    KnowledgeEntry entry = new KnowledgeEntry(question, answer, submitter, timestamp);
                    entry.setSource(pendingConf.getString(base + ".source", "player"));
                    entry.setThanked(pendingConf.getBoolean(base + ".thanked", false));
                    KnowledgeQueue.addWithId(entry, id);
                }
            }
            System.out.println(Lang.get("log.loaded-pending", KnowledgeQueue.getTotalCount()));
        }

        rebuildMemoryState();
    }

    private void rebuildMemoryState() {
        DataContainer.playerPendingKnowledgeCount.clear();
        DataContainer.submitterToUuid.clear();
        synchronized (countLock) {
            ConfigurationSection section = countConf.getConfigurationSection("players");
            if (section != null) {
                for (String uuid : section.getKeys(false)) {
                    String name = countConf.getString("players." + uuid + ".name", "");
                    int count = countConf.getInt("players." + uuid + ".count", 0);
                    DataContainer.playerPendingKnowledgeCount.put(uuid, count);
                    if (!name.isEmpty()) {
                        DataContainer.submitterToUuid.put(name, uuid);
                    }
                }
            }
            System.out.println(Lang.get("log.rebuilt-state"));
        }
    }

    @Override
    public boolean isPendingKnowledgeDuplicate(String question) {
        if (question == null) return false;
        synchronized (pendingLock) {
            ConfigurationSection section = pendingConf.getConfigurationSection("entries");
            if (section == null) return false;
            for (String key : section.getKeys(false)) {
                String stored = pendingConf.getString("entries." + key + ".question", "");
                if (stored.equals(question)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void insertLog(String playerName, String playerInput, String aiResponse,
                          String documentId, String source) {
        synchronized (logLock) {
            int id = logConf.getInt("next-id", 1);
            String base = "entries." + id;
            logConf.set(base + ".player-name", playerName);
            logConf.set(base + ".player-input", playerInput);
            logConf.set(base + ".ai-response", aiResponse);
            logConf.set(base + ".document-id", documentId == null ? "" : documentId);
            logConf.set(base + ".source", source == null ? "" : source);
            logConf.set(base + ".create-time",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            logConf.set("next-id", id + 1);
            saveQuietly(logConf, logFile);
        }
    }

    @Override
    public int getLogTotalCount() {
        synchronized (logLock) {
            ConfigurationSection section = logConf.getConfigurationSection("entries");
            if (section == null) return 0;
            return section.getKeys(false).size();
        }
    }

    @Override
    public List<LogEntry> getLogPage(int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        synchronized (logLock) {
            ConfigurationSection section = logConf.getConfigurationSection("entries");
            if (section == null) return result;

            List<Integer> ids = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                try {
                    ids.add(Integer.parseInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
            ids.sort(Comparator.reverseOrder());

            int start = page * pageSize;
            int end = Math.min(start + pageSize, ids.size());
            if (start >= ids.size()) return result;

            for (int i = start; i < end; i++) {
                int id = ids.get(i);
                String base = "entries." + id;
                LogEntry entry = new LogEntry(
                        id,
                        logConf.getString(base + ".player-name", ""),
                        logConf.getString(base + ".player-input", ""),
                        logConf.getString(base + ".ai-response", ""),
                        logConf.getString(base + ".document-id", ""),
                        logConf.getString(base + ".source", ""),
                        logConf.getString(base + ".create-time", "")
                );
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public void deleteLog(int id) {
        synchronized (logLock) {
            logConf.set("entries." + id, null);
            saveQuietly(logConf, logFile);
        }
    }

    private static String rewardKey(String playerName) {
        return playerName.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void addPendingRewards(String playerName, List<String> items, List<String> messages) {
        synchronized (rewardLock) {
            String base = "players." + rewardKey(playerName);
            rewardConf.set(base + ".name", playerName);
            if (items != null && !items.isEmpty()) {
                List<String> existing = rewardConf.getStringList(base + ".items");
                existing.addAll(items);
                rewardConf.set(base + ".items", existing);
            }
            if (messages != null && !messages.isEmpty()) {
                List<String> existing = rewardConf.getStringList(base + ".messages");
                existing.addAll(messages);
                rewardConf.set(base + ".messages", existing);
            }
            saveQuietly(rewardConf, rewardFile);
        }
    }

    @Override
    public PendingReward takePendingRewards(String playerName) {
        synchronized (rewardLock) {
            String base = "players." + rewardKey(playerName);
            if (!rewardConf.contains(base)) {
                return new PendingReward(new ArrayList<>(), new ArrayList<>());
            }
            List<String> items = new ArrayList<>(rewardConf.getStringList(base + ".items"));
            List<String> messages = new ArrayList<>(rewardConf.getStringList(base + ".messages"));
            rewardConf.set(base, null);
            saveQuietly(rewardConf, rewardFile);
            return new PendingReward(items, messages);
        }
    }
}
