package com.exai.gui;

import com.exai.config.Config;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
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
    private static final int PAGE_SIZE = 18;
    public static String getGUI_TITLE() {
        return "§c" + Config.assistantName + " 知识审核";
    }

    private static int currentPage = 0;
    private static Economy economy = null;

    public static void open(Player player) {
        if (!player.hasPermission(Config.config.getString("knowledgeReview.opPermission", "exai.op"))) {
            player.sendMessage("§c您没有权限使用此功能");
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

        Inventory inventory = Bukkit.createInventory(null, 27, getGUI_TITLE());

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

        inventory.setItem(21, createNavigationItem("◀ 上一页", page > 0));
        inventory.setItem(22, createPageInfoItem(page + 1, totalPages));
        inventory.setItem(23, createNavigationItem("下一页 ▶", page < totalPages - 1));
        inventory.setItem(26, createCloseItem());

        player.openInventory(inventory);
    }

    private static ItemStack createKnowledgeItem(KnowledgeEntry entry, int index) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e知识 #" + (index + 1));
        List<String> lore = new ArrayList<>();
        String q = entry.getQuestion();
        String a = entry.getAnswer();
        if (q.length() > 20) q = q.substring(0, 20) + "...";
        if (a.length() > 20) a = a.substring(0, 20) + "...";
        lore.add("§7提交者: §f" + entry.getSubmitter());
        lore.add("");
        lore.add("§6问：§f" + q);
        lore.add("§6答：§f" + a);
        lore.add("");
        lore.add("§a左键：批准");
        lore.add("§c右键：拒绝");
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
        meta.setLore(enabled ? null : java.util.Collections.singletonList("§7无可用页面"));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createPageInfoItem(int current, int total) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e第 " + current + " / " + total + " 页");
        meta.setLore(java.util.Arrays.asList(
                "§7待审核知识数: §f" + KnowledgeQueue.getTotalCount(),
                "§7每页显示: §f" + PAGE_SIZE + " 条"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§c✖ 关闭");
        meta.setLore(java.util.Collections.singletonList("§7返回主界面"));
        item.setItemMeta(meta);
        return item;
    }

    public static void handleClick(Player player, int slot, int rawSlot) {
        int totalPages = KnowledgeQueue.getTotalPages(PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (slot == 18 && currentPage > 0) {
            currentPage--;
            showPage(player, currentPage);
        } else if (slot == 22 && currentPage < totalPages - 1) {
            currentPage++;
            showPage(player, currentPage);
        } else if (slot == 26) {
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

    public static void handleLeftClick(Player player, int slot) {
        int totalPages = KnowledgeQueue.getTotalPages(PAGE_SIZE);
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (slot == 18 && currentPage > 0) {
            currentPage--;
            showPage(player, currentPage);
        } else if (slot == 22 && currentPage < totalPages - 1) {
            currentPage++;
            showPage(player, currentPage);
        } else if (slot == 26) {
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
                KnowledgeManager.rejectKnowledge(currentPage, slot);
                player.sendMessage("§c[" + Config.assistantName + "] 已拒绝知识: " + entry.getQuestion().substring(0, Math.min(20, entry.getQuestion().length())));
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
        KnowledgeManager.approveKnowledge(currentPage, KnowledgeQueue.getPage(currentPage, PAGE_SIZE).indexOf(entry));
        RewardManager.giveReward(player);
        player.sendMessage("§a[" + Config.assistantName + "] 已批准知识并发放奖励！");
        player.closeInventory();
    }

    public static int getCurrentPage() {
        return currentPage;
    }

    public static void refresh(Player player) {
        showPage(player, currentPage);
    }
}