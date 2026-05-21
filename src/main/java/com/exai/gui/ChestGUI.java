package com.exai.gui;

import com.exai.config.Config;
import com.exai.i18n.Lang;
import com.exai.utils.CDUtils;
import com.exai.utils.MaterialCompat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

public class ChestGUI {
    public static final int GUI_SIZE = 45;

    public static final int SLOT_CHAT = 22;
    public static final int SLOT_SUBMIT = 38;
    public static final int SLOT_REVIEW = 39;
    public static final int SLOT_INFO = 40;
    public static final int SLOT_HISTORY = 41;
    public static final int SLOT_KB = 42;

    public static String getGUI_TITLE() {
        return Config.config.getString("gui.title", Lang.get("gui.default-title", Config.assistantName));
    }

    public static void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());

        boolean cdEnded = CDUtils.isDialogueCDEnd(player);
        long cdRemaining = CDUtils.getDialogueCDRemaining(player);

        inventory.setItem(SLOT_CHAT, createButton(cdEnded, cdRemaining));
        inventory.setItem(SLOT_INFO, createInfoItem());
        inventory.setItem(SLOT_SUBMIT, createSubmitButton());
        inventory.setItem(SLOT_REVIEW, createReviewButton());
        inventory.setItem(SLOT_HISTORY, createHistoryButton());
        inventory.setItem(SLOT_KB, createKnowledgeBaseButton());

        player.openInventory(inventory);
    }

    private static ItemStack createButton(boolean cdEnded, long cdRemaining) {
        ItemStack item = MaterialCompat.steveHead();
        ItemMeta meta = item.getItemMeta();

        if (cdEnded) {
            meta.setDisplayName(Lang.get("gui.start-chat"));
        } else {
            meta.setDisplayName(Lang.get("gui.cooldown", cdRemaining));
        }

        meta.setLore(Collections.singletonList(cdEnded
                ? Lang.get("gui.start-chat-lore", Config.assistantName)
                : Lang.get("gui.cooldown-lore")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createInfoItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.info-title"));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.info-line1"),
                Lang.get("gui.info-line2"),
                Lang.get("gui.info-line3", Config.config.getInt("chatResponseCD"))
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createSubmitButton() {
        ItemStack item = new ItemStack(MaterialCompat.BOOK_AND_QUILL());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.upload-button"));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.upload-lore1"),
                Lang.get("gui.upload-lore2")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createReviewButton() {
        ItemStack item = new ItemStack(MaterialCompat.ITEM_FRAME());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.review-button"));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.review-lore1"),
                Lang.get("gui.review-lore2")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createHistoryButton() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.history-button"));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.history-lore1"),
                Lang.get("gui.history-lore2")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createKnowledgeBaseButton() {
        ItemStack item = new ItemStack(Material.BOOKSHELF);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.kb-button"));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.kb-lore1"),
                Lang.get("gui.kb-lore2")
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static void startDialogue(Player player) {
        GUIManager.enterDialogueMode(player);
        player.closeInventory();
        player.sendMessage(Lang.get("gui.divider"));
        player.sendMessage(Lang.get("gui.dialogue-start-tip1"));
        player.sendMessage(Lang.get("gui.dialogue-start-tip2", Config.assistantName));
        player.sendMessage(Lang.get("gui.divider"));
    }
}
