package com.exai.listener;

import com.exai.gui.ChestGUI;
import com.exai.gui.GUIManager;
import com.exai.gui.KnowledgeReviewGUI;
import com.exai.gui.KnowledgeSubmitGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class GUIListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.equals(ChestGUI.getGUI_TITLE())) {
            event.setCancelled(true);

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) {
                return;
            }

            String displayName = clicked.getItemMeta().getDisplayName();

            if (displayName.contains("开始对话")) {
                ChestGUI.startDialogue(player);
            } else if (displayName.contains("上传知识")) {
                player.closeInventory();
                KnowledgeSubmitGUI.open(player);
            } else if (displayName.contains("审核员审核")) {
                player.closeInventory();
                KnowledgeReviewGUI.open(player);
            }
        }

        if (title.equals("§cExAI 知识审核")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) {
                return;
            }

            int slot = event.getRawSlot();
            if (event.isLeftClick()) {
                KnowledgeReviewGUI.handleLeftClick(player, slot);
            } else if (event.isRightClick()) {
                KnowledgeReviewGUI.handleRightClick(player, slot);
            }
        }
    }
}