package com.exai.manager;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EditContextManager {

    public enum Type {
        HISTORY_PROMOTE,
        KB_EDIT,
        RAG_TEST_EDIT
    }

    public static class Context {
        public final Type type;
        public final int targetId;
        public final String targetKey;

        public Context(Type type, int targetId) {
            this.type = type;
            this.targetId = targetId;
            this.targetKey = null;
        }

        public Context(Type type, String targetKey) {
            this.type = type;
            this.targetId = -1;
            this.targetKey = targetKey;
        }
    }

    private static final Map<UUID, Context> contexts = new HashMap<>();

    public static void set(Player player, Type type, int targetId) {
        contexts.put(player.getUniqueId(), new Context(type, targetId));
    }

    public static void set(Player player, Type type, String targetKey) {
        contexts.put(player.getUniqueId(), new Context(type, targetKey));
    }

    public static Context get(Player player) {
        return contexts.get(player.getUniqueId());
    }

    public static void clear(Player player) {
        contexts.remove(player.getUniqueId());
    }
}
