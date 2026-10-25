package com.exai.gui;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.LogEntry;
import com.exai.evaluation.EvaluationAnnotationService;
import com.exai.evaluation.EvaluationCase;
import com.exai.evaluation.EvaluationTestStore;
import com.exai.evaluation.ExpectedBehavior;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** GUI and chat-input editor for one RAG evaluation case. */
public final class RagAnnotationGUI {
    private static final int GUI_SIZE = 27;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_ENABLED = 10;
    private static final int SLOT_BEHAVIOR = 11;
    private static final int SLOT_QUESTION = 12;
    private static final int SLOT_ANSWER = 13;
    private static final int SLOT_IDS = 14;
    private static final int SLOT_SAVE = 16;
    private static final int SLOT_CANCEL = 22;

    private enum InputField {
        QUESTION,
        ANSWER,
        KNOWLEDGE_IDS
    }

    private enum ReturnTarget {
        HISTORY,
        DATASET
    }

    private static final class Session {
        private final String sourceFingerprint;
        private final EvaluationCase draft;
        private final ReturnTarget returnTarget;
        private volatile InputField awaitingInput;

        private Session(String sourceFingerprint, EvaluationCase draft, ReturnTarget returnTarget) {
            this.sourceFingerprint = sourceFingerprint;
            this.draft = draft;
            this.returnTarget = returnTarget;
        }
    }

    private static final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private RagAnnotationGUI() {}

    public static String getGUI_TITLE() {
        return Lang.get("gui.rag-annotation-title");
    }

    public static void open(Player player, LogEntry log) {
        if (!Config.hasOpPermission(player)) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        sessions.put(player.getUniqueId(), new Session(
                EvaluationAnnotationService.logFingerprint(log),
                EvaluationAnnotationService.createDraft(log), ReturnTarget.HISTORY));
        show(player);
    }

    public static void open(Player player, EvaluationCase evaluationCase) {
        if (!Config.hasOpPermission(player)) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        String fingerprint = evaluationCase.getSourceLogFingerprint();
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            fingerprint = "manual_" + UUID.randomUUID().toString().replace("-", "");
        }
        sessions.put(player.getUniqueId(), new Session(fingerprint,
                EvaluationAnnotationService.copyOf(evaluationCase), ReturnTarget.DATASET));
        show(player);
    }

    public static void openNew(Player player) {
        EvaluationCase draft = new EvaluationCase();
        draft.setEnabled(true);
        draft.setQuestion("");
        draft.setExpectedBehavior(null);
        draft.setReferenceAnswer("");
        draft.setExpectedKnowledgeIds(new ArrayList<>());
        open(player, draft);
    }

    private static void show(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            ConversationHistoryGUI.reopen(player);
            return;
        }
        EvaluationCase draft = session.draft;
        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());

        inventory.setItem(SLOT_INFO, item(Material.PAPER,
                Lang.get("gui.rag-annotation-summary"), Arrays.asList(
                        Lang.get("gui.rag-annotation-question-value", truncate(draft.getQuestion(), 60)),
                        Lang.get("gui.rag-annotation-behavior-value", behaviorName(draft.getExpectedBehavior())))));
        inventory.setItem(SLOT_ENABLED, item(Material.LEVER,
                Lang.get("gui.rag-annotation-enabled-title"), Arrays.asList(
                        Lang.get("gui.rag-annotation-enabled-value",
                                draft.isEnabled() ? Lang.get("gui.rag-annotation-yes")
                                        : Lang.get("gui.rag-annotation-no")),
                        Lang.get("gui.rag-annotation-toggle-lore"))));
        inventory.setItem(SLOT_BEHAVIOR, item(Material.REDSTONE,
                Lang.get("gui.rag-annotation-behavior-title"), Arrays.asList(
                        Lang.get("gui.rag-annotation-behavior-value", behaviorName(draft.getExpectedBehavior())),
                        Lang.get("gui.rag-annotation-toggle-lore"))));
        inventory.setItem(SLOT_QUESTION, editableItem(Material.NAME_TAG,
                "gui.rag-annotation-question-title", draft.getQuestion(), true));

        boolean answerCase = draft.getExpectedBehavior() != ExpectedBehavior.REFUSE;
        inventory.setItem(SLOT_ANSWER, editableItem(Material.BOOK,
                "gui.rag-annotation-answer-title", draft.getReferenceAnswer(), answerCase));
        inventory.setItem(SLOT_IDS, editableItem(Material.ITEM_FRAME,
                "gui.rag-annotation-ids-title", joinIds(draft.getExpectedKnowledgeIds()), answerCase));

        inventory.setItem(SLOT_SAVE, item(Material.EMERALD_BLOCK,
                Lang.get("gui.rag-annotation-save-title"),
                Collections.singletonList(Lang.get("gui.rag-annotation-save-lore"))));
        inventory.setItem(SLOT_CANCEL, item(Material.BARRIER,
                Lang.get("gui.rag-annotation-cancel-title"),
                Collections.singletonList(Lang.get("gui.rag-annotation-cancel-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack editableItem(Material material, String titleKey, String value, boolean editable) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("gui.rag-annotation-current-value",
                value == null || value.trim().isEmpty()
                        ? Lang.get("gui.rag-annotation-empty") : truncate(value, 80)));
        lore.add(editable ? Lang.get("gui.rag-annotation-edit-lore")
                : Lang.get("gui.rag-annotation-refuse-field-lore"));
        return item(material, Lang.get(titleKey), lore);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static void handleClick(Player player, int slot) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (slot == SLOT_ENABLED) {
            session.draft.setEnabled(!session.draft.isEnabled());
            show(player);
        } else if (slot == SLOT_BEHAVIOR) {
            ExpectedBehavior current = session.draft.getExpectedBehavior();
            if (current == ExpectedBehavior.ANSWER) {
                session.draft.setExpectedBehavior(ExpectedBehavior.REFUSE);
                session.draft.setReferenceAnswer("");
                session.draft.setExpectedKnowledgeIds(new ArrayList<>());
            } else {
                session.draft.setExpectedBehavior(ExpectedBehavior.ANSWER);
            }
            show(player);
        } else if (slot == SLOT_QUESTION) {
            beginInput(player, session, InputField.QUESTION, "gui.rag-annotation-input-question");
        } else if (slot == SLOT_ANSWER) {
            if (session.draft.getExpectedBehavior() == ExpectedBehavior.REFUSE) {
                player.sendMessage(Lang.get("gui.rag-annotation-refuse-field-disabled"));
                return;
            }
            beginInput(player, session, InputField.ANSWER, "gui.rag-annotation-input-answer");
        } else if (slot == SLOT_IDS) {
            if (session.draft.getExpectedBehavior() == ExpectedBehavior.REFUSE) {
                player.sendMessage(Lang.get("gui.rag-annotation-refuse-field-disabled"));
                return;
            }
            beginInput(player, session, InputField.KNOWLEDGE_IDS, "gui.rag-annotation-input-ids");
        } else if (slot == SLOT_SAVE) {
            save(player, session);
        } else if (slot == SLOT_CANCEL) {
            sessions.remove(player.getUniqueId());
            returnToPrevious(player, session);
        }
    }

    private static void beginInput(Player player, Session session, InputField field, String promptKey) {
        session.awaitingInput = field;
        player.closeInventory();
        player.sendMessage(Lang.get(promptKey));
        player.sendMessage(Lang.get("gui.rag-annotation-input-hint"));
    }

    public static boolean isAwaitingInput(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.awaitingInput != null;
    }

    /** Called from AsyncPlayerChatEvent; Bukkit UI work is moved back to the main thread. */
    public static void acceptChatInput(Player player, String message) {
        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        if (session == null || session.awaitingInput == null) return;

        String value = message == null ? "" : message.trim();
        boolean cancelled = "cancel".equalsIgnoreCase(value) || "取消".equals(value);
        synchronized (session) {
            InputField field = session.awaitingInput;
            session.awaitingInput = null;
            if (!cancelled) {
                boolean clear = "clear".equalsIgnoreCase(value) || "清空".equals(value);
                if (field == InputField.QUESTION) {
                    if (!value.isEmpty()) session.draft.setQuestion(value);
                } else if (field == InputField.ANSWER) {
                    session.draft.setReferenceAnswer(clear ? "" : value);
                } else if (field == InputField.KNOWLEDGE_IDS) {
                    session.draft.setExpectedKnowledgeIds(clear
                            ? new ArrayList<>() : EvaluationAnnotationService.parseKnowledgeIds(value));
                }
            }
        }

        Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline() || sessions.get(playerId) != session) return;
            online.sendMessage(Lang.get(cancelled
                    ? "gui.rag-annotation-input-cancelled"
                    : "gui.rag-annotation-input-updated"));
            show(online);
        });
    }

    private static void save(Player player, Session session) {
        if (!EvaluationAnnotationService.isValid(session.draft)) {
            player.sendMessage(Lang.get("book.rag-test-invalid"));
            return;
        }
        EvaluationCase saved = EvaluationTestStore.upsert(session.sourceFingerprint, session.draft);
        sessions.remove(player.getUniqueId());
        player.sendMessage(Lang.get("book.rag-test-saved", saved.getId()));
        returnToPrevious(player, session);
    }

    private static void returnToPrevious(Player player, Session session) {
        if (session.returnTarget == ReturnTarget.DATASET) {
            RagDatasetGUI.reopen(player);
        } else {
            ConversationHistoryGUI.reopen(player);
        }
    }

    public static void clear(Player player) {
        sessions.remove(player.getUniqueId());
    }

    private static String behaviorName(ExpectedBehavior behavior) {
        return behavior == null ? Lang.get("gui.rag-annotation-unselected") : behavior.name();
    }

    private static String joinIds(List<String> ids) {
        return ids == null || ids.isEmpty() ? "" : String.join(", ", ids);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
