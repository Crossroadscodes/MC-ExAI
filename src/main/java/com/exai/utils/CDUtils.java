package com.exai.utils;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.i18n.Lang;
import org.bukkit.entity.Player;

public class CDUtils {
    public static boolean isCDEnd(Player player) {
        String playerUuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerCDMap.getOrDefault(playerUuid, 0L);
        long cooldown = 10000L;
        if (currentTime - lastTime >= cooldown) {
            DataContainer.playerCDMap.put(playerUuid, currentTime);
            return true;
        } else {
            player.sendMessage(Lang.get("runtime.rate-limit", Config.assistantName));
            return false;
        }
    }

    public static boolean isDialogueCDEnd(Player player) {
        String playerUuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.dialogueCDMap.getOrDefault(playerUuid, 0L);
        long cooldown = Config.chatResponseCD * 1000L;
        return currentTime - lastTime >= cooldown;
    }

    public static long getDialogueCDRemaining(Player player) {
        String playerUuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.dialogueCDMap.getOrDefault(playerUuid, 0L);
        long cooldown = Config.chatResponseCD * 1000L;
        long elapsed = currentTime - lastTime;
        if (elapsed >= cooldown) {
            return 0;
        }
        return (cooldown - elapsed) / 1000;
    }

    public static void startDialogueCD(Player player) {
        String playerUuid = player.getUniqueId().toString();
        DataContainer.dialogueCDMap.put(playerUuid, System.currentTimeMillis());
    }

    public static boolean isPlayerChatCDEnd() {
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerChatResponseCD;
        long cooldown = Config.chatResponseCD * 1000L;
        if (currentTime - lastTime >= cooldown) {
            DataContainer.playerChatResponseCD = currentTime;
            return true;
        } else {
            return false;
        }
    }
}
