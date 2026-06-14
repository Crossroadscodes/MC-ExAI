package com.exai.gui;

import com.exai.config.Config;
import com.exai.entity.LogEntry;
import com.exai.i18n.Lang;
import com.exai.manager.EditContextManager;
import com.exai.manager.KnowledgeManager;
import com.exai.utils.DataUtils;
import com.exai.utils.MaterialCompat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConversationHistoryGUI {
    private static final int GUI_SIZE = 45;
    private static final int PAGE_SIZE = 36;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_PAGE_INFO = 40;
    private static final int SLOT_NEXT = 42;
    private static final int SLOT_CLOSE = 44;
    private static final Map<UUID, Integer> currentPages = new HashMap<>();
    private static final Map<UUID, List<LogEntry>> currentPageEntries = new HashMap<>();

    public static String getGUI_TITLE() {
        return Lang.get("gui.history-title", Config.assistantName);
    }

    public static void open(Player player) {
        if (!player.hasPermission(Config.opPermission)) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        currentPages.put(player.getUniqueId(), 0);
        showPage(player, 0);
    }

    private static void showPage(Player player, int page) {
        int total = DataUtils.getLogTotalCount();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());
        List<LogEntry> entries = DataUtils.getLogPage(page, PAGE_SIZE);
        currentPageEntries.put(player.getUniqueId(), entries);

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < entries.size()) {
                inventory.setItem(i, createLogItem(entries.get(i), i));
            }
        }

        inventory.setItem(SLOT_PREV, createNavItem(Lang.get("gui.prev-page"), page > 0));
        inventory.setItem(SLOT_PAGE_INFO, createPageInfoItem(page + 1, totalPages, total));
        inventory.setItem(SLOT_NEXT, createNavItem(Lang.get("gui.next-page"), page < totalPages - 1));
        inventory.setItem(SLOT_CLOSE, createCloseItem());

        player.openInventory(inventory);
    }

    private static ItemStack createLogItem(LogEntry entry, int index) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.history-item-title", index + 1, entry.getPlayerName()));
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("gui.history-time", entry.getCreateTime()));
        lore.add(Lang.get("gui.history-source", entry.getSource() == null ? "" : entry.getSource()));
        lore.add("");
        lore.add(Lang.get("gui.knowledge-question", truncate(entry.getPlayerInput(), 30)));
        lore.add(Lang.get("gui.knowledge-answer", truncate(entry.getAiResponse(), 30)));
        lore.add("");
        lore.add(Lang.get("gui.history-left-click"));
        lore.add(Lang.get("gui.history-right-click"));
        lore.add(Lang.get("gui.history-shift-click"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createNavItem(String name, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(enabled ? "§e" + name : "§7" + name);
        meta.setLore(enabled ? null : Collections.singletonList(Lang.get("gui.nav-disabled")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createPageInfoItem(int current, int total, int totalCount) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.page-info", current, total));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.history-total-count", totalCount),
                Lang.get("gui.page-page-size", PAGE_SIZE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.close"));
        meta.setLore(Collections.singletonList(Lang.get("gui.close-lore")));
        item.setItemMeta(meta);
        return item;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    public static void handleLeftClick(Player player, int slot, boolean shift) {
        UUID uuid = player.getUniqueId();
        int page = currentPages.getOrDefault(uuid, 0);
        int total = DataUtils.getLogTotalCount();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        if (slot == SLOT_PREV && page > 0) {
            currentPages.put(uuid, page - 1);
            showPage(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < totalPages - 1) {
            currentPages.put(uuid, page + 1);
            showPage(player, page + 1);
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            ChestGUI.open(player);
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE) {
            List<LogEntry> entries = currentPageEntries.get(uuid);
            if (entries == null || slot >= entries.size()) return;
            LogEntry entry = entries.get(slot);
            if (shift) {
                player.closeInventory();
                player.sendMessage(Lang.get("gui.divider"));
                player.sendMessage(Lang.get("gui.history-detail-player", entry.getPlayerName(), entry.getCreateTime()));
                player.sendMessage(Lang.get("gui.knowledge-question", entry.getPlayerInput()));
                player.sendMessage(Lang.get("gui.knowledge-answer", entry.getAiResponse()));
                player.sendMessage(Lang.get("gui.divider"));
            } else {
                openEditBook(player, entry);
            }
        }
    }

    public static void handleRightClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        int page = currentPages.getOrDefault(uuid, 0);
        if (slot >= 0 && slot < PAGE_SIZE) {
            List<LogEntry> entries = currentPageEntries.get(uuid);
            if (entries == null || slot >= entries.size()) return;
            LogEntry entry = entries.get(slot);
            DataUtils.deleteLogAsync(entry.getId());
            player.sendMessage(Lang.get("gui.history-deleted", Config.assistantName, entry.getId()));
            showPage(player, page);
        }
    }

    private static void openEditBook(Player player, LogEntry entry) {
        ItemStack book = new ItemStack(MaterialCompat.WRITABLE_BOOK());
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setDisplayName(Lang.get("book.history-edit-title"));
        meta.setPages(Collections.singletonList(
                KnowledgeManager.buildBookContent(entry.getPlayerInput(), entry.getAiResponse())
        ));
        book.setItemMeta(meta);

        EditContextManager.set(player, EditContextManager.Type.HISTORY_PROMOTE, entry.getId());
        player.getInventory().addItem(book);
        player.closeInventory();
        player.sendMessage(Lang.get("gui.divider-green"));
        player.sendMessage(Lang.get("book.history-edit-tip1"));
        player.sendMessage(Lang.get("book.history-edit-tip2"));
        player.sendMessage(Lang.get("gui.divider-green"));
    }
}
