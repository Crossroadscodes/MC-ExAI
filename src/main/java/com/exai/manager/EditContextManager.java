package com.exai.manager;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EditContextManager {

    public enum Type {
        HISTORY_PROMOTE,
        KB_EDIT
    }

    public static class Context {
        public final Type type;
        public final int targetId;

        public Context(Type type, int targetId) {
            this.type = type;
            this.targetId = targetId;
        }
    }

    private static final Map<UUID, Context> contexts = new HashMap<>();

    public static void set(Player player, Type type, int targetId) {
        contexts.put(player.getUniqueId(), new Context(type, targetId));
    }

    public static Context get(Player player) {
        return contexts.get(player.getUniqueId());
    }

    public static void clear(Player player) {
        contexts.remove(player.getUniqueId());
    }
}
