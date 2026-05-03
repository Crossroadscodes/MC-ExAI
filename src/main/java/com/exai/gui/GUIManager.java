package com.exai.gui;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GUIManager {
    private static Set<UUID> dialoguePlayers = new HashSet<>();

    public static boolean isInDialogueMode(Player player) {
        return dialoguePlayers.contains(player.getUniqueId());
    }

    public static void enterDialogueMode(Player player) {
        dialoguePlayers.add(player.getUniqueId());
    }

    public static void exitDialogueMode(Player player) {
        dialoguePlayers.remove(player.getUniqueId());
    }
}