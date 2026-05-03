package com.exai.gui;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.utils.CDUtils;
import com.exai.utils.MaterialCompat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;

public class ChestGUI {
    public static String getGUI_TITLE() {
        return Config.config.getString("gui.title", Config.assistantName + " 对话助手");
    }

    public static void open(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, getGUI_TITLE());

        boolean cdEnded = CDUtils.isDialogueCDEnd(player);
        long cdRemaining = CDUtils.getDialogueCDRemaining(player);

        ItemStack button = createButton(cdEnded, cdRemaining);
        inventory.setItem(13, button);

        ItemStack info = createInfoItem();
        inventory.setItem(22, info);

        ItemStack submitButton = createSubmitButton();
        inventory.setItem(19, submitButton);

        ItemStack reviewButton = createReviewButton();
        inventory.setItem(20, reviewButton);

        player.openInventory(inventory);
    }

    private static ItemStack createButton(boolean cdEnded, long cdRemaining) {
        ItemStack item = MaterialCompat.steveHead();
        ItemMeta meta = item.getItemMeta();

        if (cdEnded) {
            meta.setDisplayName("§a§l[ 开始对话 ]");
        } else {
            meta.setDisplayName("§c§l[ 冷却中: " + cdRemaining + "秒 ]");
        }

        meta.setLore(Collections.singletonList(cdEnded ? "§7点击开始与" + Config.assistantName + "对话" : "§7请等待冷却结束"));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createInfoItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e⚠ 单轮对话须知");
        meta.setLore(java.util.Arrays.asList(
                "§7仅支持单轮对话",
                "§7输入问题后自动结束",
                "§7CD: " + Config.config.getInt("chatResponseCD") + "秒"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createSubmitButton() {
        ItemStack item = new ItemStack(MaterialCompat.BOOK_AND_QUILL());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a§l[ 上传知识 ]");
        meta.setLore(java.util.Arrays.asList(
                "§7点击获取知识上传书本",
                "§7编辑后签署即可提交"
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack createReviewButton() {
        ItemStack item = new ItemStack(MaterialCompat.ITEM_FRAME());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§l[ 审核员审核 ]");
        meta.setLore(java.util.Arrays.asList(
                "§7点击打开知识审核界面",
                "§7仅拥有权限可用"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static void startDialogue(Player player) {
        GUIManager.enterDialogueMode(player);
        player.closeInventory();
        player.sendMessage("§e═══════════════════════════");
        player.sendMessage("§a请在聊天栏输入您的问题");
        player.sendMessage("§7" + Config.assistantName + "将在回复后自动退出对话模式");
        player.sendMessage("§e═══════════════════════════");
    }
}