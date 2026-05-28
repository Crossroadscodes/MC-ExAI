package com.exai.gui;

import com.exai.config.Config;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeManager;
import com.exai.manager.RewardManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeReviewGUI {
    private static final int GUI_SIZE = 45;
    private static final int PAGE_SIZE = 36;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_PAGE_INFO = 40;
    private static final int SLOT_NEXT = 42;
    private static final int SLOT_CLOSE = 44;

    public static String getGUI_TITLE() {
        return Lang.get("gui.review-title", Config.assistantName);
    }

    private static int currentPage = 0;
    private static Economy economy = null;

    public static void open(Player player) {
        if (!player.hasPermission(Config.config.getString("knowledgeReview.opPermission", "exai.op"))) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }

        if (economy == null) {
            economy = Bukkit.getServer().getServicesManager()
                    .getRegistration(Economy.class).getProvider();
        }

        currentPage = 0;
        showPage(player, currentPage);
    }

    private static void showPage(Player player, int page) {
        int totalPages = KnowledgeQueue.getTotalPages(PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());

        List<KnowledgeEntry> entries = KnowledgeQueue.getPage(page, PAGE_SIZE);

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < entries.size()) {
                KnowledgeEntry entry = entries.get(i);
                ItemStack item = createKnowledgeItem(entry, i);
                inventory.setItem(i, item);
            } else {
                inventory.setItem(i, createEmptyItem());
            }
        }

        inventory.setItem(SLOT_PREV, createNavigationItem(Lang.get("gui.prev-page"), page > 0));
        inventory.setItem(SLOT_PAGE_INFO, createPageInfoItem(page + 1, totalPages));
        inventory.setItem(SLOT_NEXT, createNavigationItem(Lang.get("gui.next-page"), page < totalPages - 1));
        inventory.setItem(SLOT_CLOSE, createCloseItem());

        player.openInventory(inventory);
    }

    private static ItemStack createKnowledgeItem(KnowledgeEntry entry, int index) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.knowledge-title", index + 1));
        List<String> lore = new ArrayList<>();
        String q = entry.getQuestion();
        String a = entry.getAnswer();
        if (q.length() > 20) q = q.substring(0, 20) + "...";
        if (a.length() > 20) a = a.substring(0, 20) + "...";
        if ("auto".equals(entry.getSource())) {
            lore.add(Lang.get("gui.knowledge-source-auto"));
            lore.add(Lang.get("gui.knowledge-answerer", entry.getSubmitter()));
            if (entry.isThanked()) {
                lore.add(Lang.get("gui.knowledge-thanked"));
            }
        } else {
            lore.add(Lang.get("gui.knowledge-source-player"));
            lore.add(Lang.get("gui.knowledge-submitter", entry.getSubmitter()));
        }
        lore.add("");
        lore.add(Lang.get("gui.knowledge-question", q));
        lore.add(Lang.get("gui.knowledge-answer", a));
        lore.add("");
        lore.add(Lang.get("gui.knowledge-left-click"));
        lore.add(Lang.get("gui.knowledge-right-click"));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createEmptyItem() {
        ItemStack item = new ItemStack(Material.AIR);
        return item;
    }

    private static ItemStack createNavigationItem(String name, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.ARROW : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(enabled ? "§e" + name : "§7" + name);
        meta.setLore(enabled ? null : java.util.Collections.singletonList(Lang.get("gui.nav-disabled")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createPageInfoItem(int current, int total) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.page-info", current, total));
        meta.setLore(java.util.Arrays.asList(
                Lang.get("gui.page-pending-count", KnowledgeQueue.getTotalCount()),
                Lang.get("gui.page-page-size", PAGE_SIZE)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Lang.get("gui.close"));
        meta.setLore(java.util.Collections.singletonList(Lang.get("gui.close-lore")));
        item.setItemMeta(meta);
        return item;
    }

    public static void handleClick(Player player, int slot, int rawSlot) {
        handleLeftClick(player, slot);
    }

    public static void handleLeftClick(Player player, int slot) {
        int totalPages = KnowledgeQueue.getTotalPages(PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (slot == SLOT_PREV && currentPage > 0) {
            currentPage--;
            showPage(player, currentPage);
        } else if (slot == SLOT_NEXT && currentPage < totalPages - 1) {
            currentPage++;
            showPage(player, currentPage);
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
            ChestGUI.open(player);
        } else if (slot >= 0 && slot < PAGE_SIZE) {
            List<KnowledgeEntry> entries = KnowledgeQueue.getPage(currentPage, PAGE_SIZE);
            if (slot < entries.size()) {
                KnowledgeEntry entry = entries.get(slot);
                approveAndClose(player, entry);
            }
        }
    }

    public static void handleRightClick(Player player, int slot) {
        int totalPages = KnowledgeQueue.getTotalPages(PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (slot >= 0 && slot < PAGE_SIZE) {
            List<KnowledgeEntry> entries = KnowledgeQueue.getPage(currentPage, PAGE_SIZE);
            if (slot < entries.size()) {
                KnowledgeEntry entry = entries.get(slot);
                KnowledgeManager.rejectKnowledge(currentPage, slot, PAGE_SIZE);
                String preview = entry.getQuestion().substring(0, Math.min(20, entry.getQuestion().length()));
                player.sendMessage(Lang.get("gui.rejected", Config.assistantName, preview));
                if (KnowledgeQueue.getTotalCount() == 0) {
                    player.closeInventory();
                    showPage(player, 0);
                } else {
                    showPage(player, currentPage);
                }
            }
        }
    }

    private static void approveAndClose(Player player, KnowledgeEntry entry) {
        KnowledgeManager.approveKnowledge(currentPage, KnowledgeQueue.getPage(currentPage, PAGE_SIZE).indexOf(entry), PAGE_SIZE);
        RewardManager.giveReward(player);
        player.sendMessage(Lang.get("gui.approved", Config.assistantName));
        player.closeInventory();
    }

    public static int getCurrentPage() {
        return currentPage;
    }

    public static void refresh(Player player) {
        showPage(player, currentPage);
    }
}
