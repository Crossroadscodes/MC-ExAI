package com.exai.gui;

import com.exai.config.Config;
import com.exai.evaluation.EvaluationAnnotationService;
import com.exai.evaluation.EvaluationCase;
import com.exai.evaluation.EvaluationTestStore;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone browser and management entry for the active RAG evaluation dataset. */
public final class RagDatasetGUI {
    private static final int GUI_SIZE = 45;
    private static final int PAGE_SIZE = 36;
    private static final int SLOT_NEW = 36;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_PAGE = 40;
    private static final int SLOT_NEXT = 42;
    private static final int SLOT_BACK = 44;

    private static final Map<UUID, Integer> currentPages = new HashMap<>();
    private static final Map<UUID, List<EvaluationCase>> currentEntries = new HashMap<>();

    private RagDatasetGUI() {}

    public static String getGUI_TITLE() {
        return Lang.get("gui.rag-dataset-title");
    }

    public static void open(Player player) {
        if (!Config.hasOpPermission(player)) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        currentPages.put(player.getUniqueId(), 0);
        showPage(player, 0);
    }

    public static void reopen(Player player) {
        if (!Config.hasOpPermission(player)) {
            player.sendMessage(Lang.get("gui.review-no-permission"));
            return;
        }
        List<EvaluationCase> all = EvaluationTestStore.readAll();
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / PAGE_SIZE));
        int page = Math.max(0, Math.min(
                currentPages.getOrDefault(player.getUniqueId(), 0), totalPages - 1));
        currentPages.put(player.getUniqueId(), page);
        showPage(player, page, all);
    }

    private static void showPage(Player player, int page) {
        showPage(player, page, EvaluationTestStore.readAll());
    }

    private static void showPage(Player player, int page, List<EvaluationCase> all) {
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        currentPages.put(player.getUniqueId(), safePage);

        int from = safePage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<EvaluationCase> pageEntries = from >= all.size()
                ? new ArrayList<>() : new ArrayList<>(all.subList(from, to));
        currentEntries.put(player.getUniqueId(), pageEntries);

        Inventory inventory = Bukkit.createInventory(null, GUI_SIZE, getGUI_TITLE());
        for (int i = 0; i < pageEntries.size(); i++) {
            inventory.setItem(i, createCaseItem(pageEntries.get(i), from + i + 1));
        }
        inventory.setItem(SLOT_NEW, item(Material.PAPER, Lang.get("gui.rag-dataset-new"),
                Collections.singletonList(Lang.get("gui.rag-dataset-new-lore"))));
        inventory.setItem(SLOT_PREV, navItem(Lang.get("gui.prev-page"), safePage > 0));
        inventory.setItem(SLOT_PAGE, item(Material.BOOK,
                Lang.get("gui.page-info", safePage + 1, totalPages),
                java.util.Arrays.asList(
                        Lang.get("gui.rag-dataset-total", all.size()),
                        Lang.get("gui.rag-dataset-storage", Config.storageType.toUpperCase()))));
        inventory.setItem(SLOT_NEXT, navItem(Lang.get("gui.next-page"), safePage < totalPages - 1));
        inventory.setItem(SLOT_BACK, item(Material.BARRIER, Lang.get("gui.rag-dataset-back"),
                Collections.singletonList(Lang.get("gui.rag-dataset-back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack createCaseItem(EvaluationCase evaluationCase, int index) {
        List<String> lore = new ArrayList<>();
        lore.add(Lang.get("gui.rag-dataset-enabled",
                evaluationCase.isEnabled() ? Lang.get("gui.rag-annotation-yes")
                        : Lang.get("gui.rag-annotation-no")));
        lore.add(Lang.get("gui.rag-dataset-behavior", evaluationCase.getExpectedBehavior().name()));
        lore.add(Lang.get("gui.rag-dataset-question", truncate(evaluationCase.getQuestion(), 60)));
        if (evaluationCase.getReferenceAnswer() != null
                && !evaluationCase.getReferenceAnswer().trim().isEmpty()) {
            lore.add(Lang.get("gui.rag-dataset-answer",
                    truncate(evaluationCase.getReferenceAnswer(), 60)));
        }
        lore.add(Lang.get("gui.rag-dataset-ids",
                evaluationCase.getExpectedKnowledgeIds().isEmpty()
                        ? Lang.get("gui.rag-annotation-empty")
                        : String.join(", ", evaluationCase.getExpectedKnowledgeIds())));
        if (evaluationCase.getUpdatedAt() > 0) {
            lore.add(Lang.get("gui.rag-dataset-updated",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(evaluationCase.getUpdatedAt()))));
        }
        lore.add("");
        lore.add(Lang.get("gui.rag-dataset-left-click"));
        lore.add(Lang.get("gui.rag-dataset-right-click"));
        lore.add(Lang.get("gui.rag-dataset-shift-right-click"));
        return item(Material.PAPER,
                Lang.get("gui.rag-dataset-item-title", index, evaluationCase.getId()), lore);
    }

    public static void handleClick(Player player, int slot, boolean rightClick, boolean shiftClick) {
        UUID playerId = player.getUniqueId();
        int page = currentPages.getOrDefault(playerId, 0);
        if (slot == SLOT_NEW) {
            RagAnnotationGUI.openNew(player);
            return;
        }
        if (slot == SLOT_PREV && page > 0) {
            showPage(player, page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            List<EvaluationCase> all = EvaluationTestStore.readAll();
            if ((page + 1) * PAGE_SIZE < all.size()) showPage(player, page + 1, all);
            return;
        }
        if (slot == SLOT_BACK) {
            ChestGUI.open(player);
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;
        List<EvaluationCase> entries = currentEntries.get(playerId);
        if (entries == null || slot >= entries.size()) return;
        EvaluationCase selected = entries.get(slot);
        if (!rightClick) {
            RagAnnotationGUI.open(player, selected);
        } else if (shiftClick) {
            if (EvaluationTestStore.delete(selected.getId())) {
                player.sendMessage(Lang.get("gui.rag-dataset-deleted", selected.getId()));
            }
            reopen(player);
        } else {
            EvaluationCase changed = EvaluationAnnotationService.copyOf(selected);
            changed.setEnabled(!changed.isEnabled());
            EvaluationTestStore.upsert(changed.getSourceLogFingerprint(), changed);
            player.sendMessage(Lang.get("gui.rag-dataset-toggled", changed.getId(),
                    changed.isEnabled() ? Lang.get("gui.rag-annotation-yes")
                            : Lang.get("gui.rag-annotation-no")));
            reopen(player);
        }
    }

    private static ItemStack navItem(String name, boolean enabled) {
        return item(enabled ? Material.ARROW : Material.BARRIER,
                enabled ? name : Lang.get("gui.rag-dataset-nav-disabled", name),
                enabled ? Collections.emptyList() : Collections.singletonList(Lang.get("gui.nav-disabled")));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
