package com.exai.listener;

import com.exai.config.Config;
import com.exai.manager.KnowledgeManager;
import com.exai.manager.RewardManager;
import com.exai.gui.ChestGUI;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;

public class KnowledgeListener implements Listener {
    private static Economy economy = null;

    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        BookMeta meta = event.getNewBookMeta();
        BookMeta previousMeta = event.getPreviousBookMeta();

        if (meta.getDisplayName() == null || !meta.getDisplayName().equals("§a§l[ 知识上传 ]")) {
            return;
        }

        event.setSigning(true);

        List<String> pages = meta.getPages();
        StringBuilder contentBuilder = new StringBuilder();
        for (String page : pages) {
            contentBuilder.append(page).append("\n");
        }

        String content = contentBuilder.toString();
        String originalTemplate = KnowledgeManager.getTemplateBookContent().replace("\n", "").replace("\r", "");
        String submittedContent = content.replace("\n", "").replace("\r", "");

        if (submittedContent.trim().equals(originalTemplate.trim()) ||
            submittedContent.trim().equals(KnowledgeManager.getTemplateBookContent().trim())) {
            player.sendMessage("§c[" + Config.assistantName + "] 请编辑书本内容后再签署！");
            return;
        }

        com.exai.entity.KnowledgeEntry entry = KnowledgeManager.parseAndValidate(content);
        if (entry == null) {
            player.sendMessage("§c[" + Config.assistantName + "] 格式错误！请使用格式：问：xxx 答：xxx");
            return;
        }

        if (KnowledgeManager.submitKnowledge(entry, player.getName())) {
            player.getInventory().getItemInMainHand().setAmount(0);
            player.sendMessage("§a═══════════════════════════");
            player.sendMessage("§a[" + Config.assistantName + "] 知识已提交成功！");
            player.sendMessage("§e等待OP审核...");
            player.sendMessage("§a═══════════════════════════");
        } else {
            player.sendMessage("§c[" + Config.assistantName + "] 提交失败：此问题已存在或已达到每日上传上限！");
        }
    }
}