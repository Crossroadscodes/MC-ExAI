package com.exai.gui;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIManager {
    private static final Set<UUID> dialoguePlayers = ConcurrentHashMap.newKeySet();

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
