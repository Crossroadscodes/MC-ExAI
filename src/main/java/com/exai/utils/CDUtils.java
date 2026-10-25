package com.exai.utils;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.i18n.Lang;
import org.bukkit.entity.Player;

public class CDUtils {
    private static final long COMMAND_COOLDOWN_MS = 10000L;

    /** @deprecated Use the explicit command cooldown check/start methods. */
    @Deprecated
    public static boolean isCDEnd(Player player) {
        if (!isCommandCDEnd(player)) {
            return false;
        }
        return tryStartCommandCD(player);
    }

    /** Checks the command cooldown without consuming it. */
    public static synchronized boolean isCommandCDEnd(Player player) {
        String playerUuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerCDMap.getOrDefault(playerUuid, 0L);
        if (currentTime - lastTime >= COMMAND_COOLDOWN_MS) {
            return true;
        }
        player.sendMessage(Lang.get("runtime.rate-limit", Config.assistantName));
        return false;
    }

    /** Atomically starts the command cooldown only after a valid answer is ready. */
    public static synchronized boolean tryStartCommandCD(Player player) {
        String playerUuid = player.getUniqueId().toString();
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerCDMap.getOrDefault(playerUuid, 0L);
        if (currentTime - lastTime < COMMAND_COOLDOWN_MS) {
            return false;
        }
        DataContainer.playerCDMap.put(playerUuid, currentTime);
        return true;
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

    /** 只检查公屏回答冷却，不修改冷却开始时间。 */
    public static synchronized boolean isPlayerChatCDEnd() {
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerChatResponseCD;
        long cooldown = Config.chatResponseCD * 1000L;
        return currentTime - lastTime >= cooldown;
    }

    /**
     * 有效答案即将广播时原子地启动公屏回答冷却。
     * 并发生成答案时仅允许第一个答案成功，避免同一冷却窗口广播多条回复。
     */
    public static synchronized boolean tryStartPlayerChatCD() {
        long currentTime = System.currentTimeMillis();
        long lastTime = DataContainer.playerChatResponseCD;
        long cooldown = Config.chatResponseCD * 1000L;
        if (currentTime - lastTime >= cooldown) {
            DataContainer.playerChatResponseCD = currentTime;
            return true;
        }
        return false;
    }
}
