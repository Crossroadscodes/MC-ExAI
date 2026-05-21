package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.i18n.Lang;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class RewardManager {
    private static Economy economy = null;

    public static void init() {
        if (economy == null) {
            economy = ExAI.getInstance().getServer().getServicesManager()
                    .getRegistration(Economy.class).getProvider();
        }
    }

    public static void giveReward(Player player) {
        init();

        if (economy != null) {
            boolean vaultEnabled = Config.config.getBoolean("knowledgeReview.rewards.vault.enabled", true);
            if (vaultEnabled) {
                double amount = Config.config.getDouble("knowledgeReview.rewards.vault.amount", 100);
                economy.depositPlayer(player, amount);
                player.sendMessage(Lang.get("reward.vault", Config.assistantName, amount, Config.currencyName));
            }
        }

        List<?> itemsConfig = Config.config.getMapList("knowledgeReview.rewards.items");
        for (Object item : itemsConfig) {
            if (item instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> itemMap = (java.util.Map<String, Object>) item;
                String materialName = (String) itemMap.get("material");
                int amount = ((Number) itemMap.get("amount")).intValue();
                Material material = Material.getMaterial(materialName);
                if (material != null) {
                    ItemStack itemStack = new ItemStack(material, amount);
                    player.getInventory().addItem(itemStack);
                    player.sendMessage(Lang.get("reward.item", Config.assistantName, amount, materialName));
                }
            }
        }
    }
}
