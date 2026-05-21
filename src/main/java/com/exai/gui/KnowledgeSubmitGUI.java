package com.exai.gui;

import com.exai.i18n.Lang;
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
        meta.setDisplayName(Lang.get("book.title"));
        meta.setPages(Collections.singletonList(KnowledgeManager.getTemplateBookContent()));
        book.setItemMeta(meta);

        player.getInventory().addItem(book);
        player.sendMessage(Lang.get("gui.divider-green"));
        player.sendMessage(Lang.get("book.upload-tip1"));
        player.sendMessage(Lang.get("book.upload-tip2"));
        player.sendMessage(Lang.get("book.upload-tip3"));
        player.sendMessage(Lang.get("gui.divider-green"));
    }
}
