package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.PendingReward;
import com.exai.i18n.Lang;
import com.exai.utils.DataUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RewardManager {
    private static Economy economy = null;

    public static void init() {
        if (economy == null) {
            economy = ExAI.getInstance().getServer().getServicesManager()
                    .getRegistration(Economy.class).getProvider();
        }
    }

    private static boolean vaultEnabled() {
        return Config.config.getBoolean("knowledge.knowledgeReview.rewards.vault.enabled", true);
    }

    private static double vaultAmount() {
        return Config.config.getDouble("knowledge.knowledgeReview.rewards.vault.amount", 100);
    }

    private static List<Map<?, ?>> itemRewards() {
        return Config.config.getMapList("knowledge.knowledgeReview.rewards.items");
    }

    /**
     * 知识被采纳时给提交者发奖励。
     * source=import 为文档导入，无玩家提交者，不发奖励；
     * 提交者在线则直接发放，离线则金币用 Vault 离线发放，物品与提示入队待其下次登录领取。
     */
    public static void rewardSubmitter(String submitterName, String source) {
        if (submitterName == null || submitterName.trim().isEmpty()) {
            return;
        }
        if ("import".equals(source)) {
            return;
        }
        Player online = Bukkit.getPlayerExact(submitterName);
        if (online != null) {
            giveReward(online);
        } else {
            giveOfflineReward(submitterName);
        }
    }

    /** 直接给在线玩家发放奖励。 */
    public static void giveReward(Player player) {
        init();

        if (economy != null && vaultEnabled()) {
            double amount = vaultAmount();
            economy.depositPlayer(player, amount);
            player.sendMessage(Lang.get("reward.vault", Config.assistantName, formatAmount(amount), Config.currencyName));
        }

        for (Map<?, ?> itemMap : itemRewards()) {
            Material material = parseMaterial(itemMap);
            if (material != null) {
                int amount = ((Number) itemMap.get("amount")).intValue();
                player.getInventory().addItem(new ItemStack(material, amount));
                player.sendMessage(Lang.get("reward.item", Config.assistantName, amount, material.name()));
            }
        }
    }

    /** 提交者离线时：金币用 Vault 离线发放，物品与提示入队(持久化)，待其下次登录领取。 */
    private static void giveOfflineReward(String submitterName) {
        init();

        List<String> items = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        messages.add(Lang.get("reward.queued-notice", Config.assistantName));

        if (economy != null && vaultEnabled()) {
            double amount = vaultAmount();
            OfflinePlayer offline = Bukkit.getOfflinePlayer(submitterName);
            economy.depositPlayer(offline, amount);
            messages.add(Lang.get("reward.vault", Config.assistantName, formatAmount(amount), Config.currencyName));
        }

        for (Map<?, ?> itemMap : itemRewards()) {
            Material material = parseMaterial(itemMap);
            if (material != null) {
                int amount = ((Number) itemMap.get("amount")).intValue();
                items.add(material.name() + ":" + amount);
                messages.add(Lang.get("reward.item", Config.assistantName, amount, material.name()));
            }
        }

        DataUtils.addPendingRewardsAsync(submitterName, items, messages);
    }

    /**
     * 玩家登录时发放其离线期间累计的物品奖励并提示。
     * 读取持久化队列在异步线程完成，再回到主线程发放；若取出后玩家已离线则放回队列，避免丢失。
     */
    public static void deliverQueued(Player player) {
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            PendingReward pending = DataUtils.takePendingRewards(name);
            if (pending.isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
                if (!player.isOnline()) {
                    // 玩家已离线，放回队列下次登录再发
                    DataUtils.addPendingRewardsAsync(name, pending.getItems(), pending.getMessages());
                    return;
                }
                for (String entry : pending.getItems()) {
                    int idx = entry.lastIndexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    Material material = Material.getMaterial(entry.substring(0, idx));
                    if (material == null) {
                        continue;
                    }
                    try {
                        int amount = Integer.parseInt(entry.substring(idx + 1));
                        player.getInventory().addItem(new ItemStack(material, amount));
                    } catch (NumberFormatException ignored) {
                    }
                }
                for (String msg : pending.getMessages()) {
                    player.sendMessage(msg);
                }
            });
        });
    }

    /** 「上传知识」图标展示的「采纳后可获得奖励」lore 行。 */
    public static List<String> getRewardLoreLines() {
        List<String> lore = new ArrayList<>();
        if (vaultEnabled()) {
            lore.add(Lang.get("gui.upload-reward-vault", formatAmount(vaultAmount()), Config.currencyName));
        }
        for (Map<?, ?> itemMap : itemRewards()) {
            Material material = parseMaterial(itemMap);
            if (material != null) {
                int amount = ((Number) itemMap.get("amount")).intValue();
                lore.add(Lang.get("gui.upload-reward-item", amount, material.name()));
            }
        }
        if (!lore.isEmpty()) {
            lore.add(0, Lang.get("gui.upload-reward-header"));
        }
        return lore;
    }

    private static Material parseMaterial(Map<?, ?> itemMap) {
        Object mat = itemMap.get("material");
        Object amt = itemMap.get("amount");
        if (mat == null || !(amt instanceof Number)) {
            return null;
        }
        return Material.getMaterial(String.valueOf(mat));
    }

    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }
}
