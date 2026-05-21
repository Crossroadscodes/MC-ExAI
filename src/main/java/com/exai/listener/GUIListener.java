package com.exai.listener;

import com.exai.gui.ChestGUI;
import com.exai.gui.ConversationHistoryGUI;
import com.exai.gui.KnowledgeBaseGUI;
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

            int slot = event.getRawSlot();
            if (slot == ChestGUI.SLOT_CHAT) {
                ChestGUI.startDialogue(player);
            } else if (slot == ChestGUI.SLOT_SUBMIT) {
                player.closeInventory();
                KnowledgeSubmitGUI.open(player);
            } else if (slot == ChestGUI.SLOT_REVIEW) {
                player.closeInventory();
                KnowledgeReviewGUI.open(player);
            } else if (slot == ChestGUI.SLOT_HISTORY) {
                player.closeInventory();
                ConversationHistoryGUI.open(player);
            } else if (slot == ChestGUI.SLOT_KB) {
                player.closeInventory();
                KnowledgeBaseGUI.open(player);
            }
            return;
        }

        if (title.equals(KnowledgeReviewGUI.getGUI_TITLE())) {
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
            return;
        }

        if (title.equals(ConversationHistoryGUI.getGUI_TITLE())) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) {
                return;
            }

            int slot = event.getRawSlot();
            if (event.isLeftClick()) {
                ConversationHistoryGUI.handleLeftClick(player, slot, event.isShiftClick());
            } else if (event.isRightClick()) {
                ConversationHistoryGUI.handleRightClick(player, slot);
            }
            return;
        }

        if (title.equals(KnowledgeBaseGUI.getGUI_TITLE())) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) {
                return;
            }

            int slot = event.getRawSlot();
            if (event.isLeftClick()) {
                KnowledgeBaseGUI.handleLeftClick(player, slot, event.isShiftClick());
            } else if (event.isRightClick()) {
                KnowledgeBaseGUI.handleRightClick(player, slot);
            }
        }
    }
}
