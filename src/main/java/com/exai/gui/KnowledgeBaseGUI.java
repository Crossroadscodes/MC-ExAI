package com.exai.gui;

import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.exai.manager.EditContextManager;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.KnowledgeManager;
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

public class KnowledgeBaseGUI {
    private static final int GUI_SIZE = 45;
    private static final int PAGE_SIZE = 36;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_PAGE_INFO = 40;
    private static final int SLOT_NEXT = 42;
    private static final int SLOT_CLOSE = 44;
    private static final Map<UUID, Integer> currentPages = new HashMap<>();
    private static final Map<UUID, List<KnowledgeEntry>> currentPageEntries = new HashMap<>();
    private static final Map<UUID, List<Integer>> currentPageIndices = new HashMap<>();

    public static String getGUI_TITLE() {
        return Lang.get("gui.kb-title", Config.assistantName);
    }

    public static void open(Player player) {
        if (!player.hasPermission(Config.config.getString("knowledgeReview.opPermission", "exai.op"))) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        currentPages.put(player.getUniqueId(), 0);
        showPage(player, 0);
    }

    private static void showPage(Player player, int page) {
        List<KnowledgeEntry> all = KnowledgeFileManager.readAll();
        int total = all.size();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        List<KnowledgeEntry> entries = start < total ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int i = start; i < end; i++) indices.add(i);

        currentPageEntries.put(player.getUniqueId(), entries);
        currentPageIndices.put(player.getUniqueId(), indices);
        currentPages.put(player.getUniqueId(), page);

        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < entries.size()) {
                inventory.setItem(i, createKbItem(entries.get(i), indices.get(i)));
            }
        }

        inventory.setItem(SLOT_PREV, createNavItem(Lang.get("gui.prev-page"), page > 0));
        inventory.setItem(SLOT_PAGE_INFO, createPageInfoItem(page + 1, totalPages, total));
        inventory.setItem(SLOT_NEXT, createNavItem(Lang.get("gui.next-page"), page < totalPages - 1));
        inventory.setItem(SLOT_CLOSE, createCloseItem());

        player.openInventory(inventory);
    }

    private static ItemStack createKbItem(KnowledgeEntry entry, int absoluteIndex) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.kb-item-title", absoluteIndex + 1));
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("gui.knowledge-question", truncate(entry.getQuestion(), 30)));
        lore.add(Lang.get("gui.knowledge-answer", truncate(entry.getAnswer(), 30)));
        lore.add("");
        lore.add(Lang.get("gui.kb-left-click"));
        lore.add(Lang.get("gui.kb-right-click"));
        lore.add(Lang.get("gui.kb-shift-click"));
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
                Lang.get("gui.kb-total-count", totalCount),
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
        List<KnowledgeEntry> all = KnowledgeFileManager.readAll();
        int total = all.size();
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        if (slot == SLOT_PREV && page > 0) {
            showPage(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT && page < totalPages - 1) {
            showPage(player, page + 1);
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            ChestGUI.open(player);
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE) {
            List<KnowledgeEntry> entries = currentPageEntries.get(uuid);
            List<Integer> indices = currentPageIndices.get(uuid);
            if (entries == null || slot >= entries.size()) return;
            KnowledgeEntry entry = entries.get(slot);
            int absIndex = indices.get(slot);
            if (shift) {
                player.closeInventory();
                player.sendMessage(Lang.get("gui.divider"));
                player.sendMessage(Lang.get("gui.kb-detail-title", absIndex + 1));
                player.sendMessage(Lang.get("gui.knowledge-question", entry.getQuestion()));
                player.sendMessage(Lang.get("gui.knowledge-answer", entry.getAnswer()));
                player.sendMessage(Lang.get("gui.divider"));
            } else {
                openEditBook(player, entry, absIndex);
            }
        }
    }

    public static void handleRightClick(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        int page = currentPages.getOrDefault(uuid, 0);
        if (slot >= 0 && slot < PAGE_SIZE) {
            List<KnowledgeEntry> entries = currentPageEntries.get(uuid);
            List<Integer> indices = currentPageIndices.get(uuid);
            if (entries == null || slot >= entries.size()) return;
            int absIndex = indices.get(slot);
            KnowledgeFileManager.deleteByIndex(absIndex);
            KnowledgeFileManager.reloadKnowledgeBaseAsync();
            player.sendMessage(Lang.get("gui.kb-deleted", Config.assistantName, absIndex + 1));
            showPage(player, page);
        }
    }

    private static void openEditBook(Player player, KnowledgeEntry entry, int absIndex) {
        ItemStack book = new ItemStack(MaterialCompat.WRITABLE_BOOK());
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setDisplayName(Lang.get("book.kb-edit-title"));
        meta.setPages(Collections.singletonList(
                KnowledgeManager.buildBookContent(entry.getQuestion(), entry.getAnswer())
        ));
        book.setItemMeta(meta);

        EditContextManager.set(player, EditContextManager.Type.KB_EDIT, absIndex);
        player.getInventory().addItem(book);
        player.closeInventory();
        player.sendMessage(Lang.get("gui.divider-green"));
        player.sendMessage(Lang.get("book.kb-edit-tip1"));
        player.sendMessage(Lang.get("book.kb-edit-tip2"));
        player.sendMessage(Lang.get("gui.divider-green"));
    }
}
