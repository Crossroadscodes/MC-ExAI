package com.exai.gui;

import com.exai.config.Config;
import com.exai.manager.KnowledgeManager;
import com.exai.utils.MaterialCompat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.Collections;

public class KnowledgeSubmitGUI {
    public static void open(Player player) {
        ItemStack book = new ItemStack(MaterialCompat.WRITABLE_BOOK());
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setDisplayName("§a§l[ 知识上传 ]");
        meta.setPages(Collections.singletonList(KnowledgeManager.getTemplateBookContent()));
        book.setItemMeta(meta);

        player.getInventory().addItem(book);
        player.sendMessage("§a═══════════════════════════");
        player.sendMessage("§e请在背包中找到这本书");
        player.sendMessage("§e编辑内容后签署以提交知识");
        player.sendMessage("§7格式：问：xxx 答：xxx");
        player.sendMessage("§a═══════════════════════════");
    }
}