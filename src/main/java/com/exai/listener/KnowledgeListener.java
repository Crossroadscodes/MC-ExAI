package com.exai.listener;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.ReviewResult;
import com.exai.i18n.Lang;
import com.exai.manager.EditContextManager;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.KnowledgeManager;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;
import java.util.UUID;

public class KnowledgeListener implements Listener {

    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        BookMeta meta = event.getNewBookMeta();

        if (meta.getDisplayName() == null) {
            return;
        }

        String displayName = meta.getDisplayName();
        if (displayName.equals(Lang.get("book.title"))) {
            handleSubmitBook(event, player, meta);
        } else if (displayName.equals(Lang.get("book.history-edit-title"))) {
            handleHistoryPromoteBook(event, player, meta);
        } else if (displayName.equals(Lang.get("book.kb-edit-title"))) {
            handleKbEditBook(event, player, meta);
        }
    }

    private void handleSubmitBook(PlayerEditBookEvent event, Player player, BookMeta meta) {
        String content = joinPages(meta.getPages());
        String originalTemplate = KnowledgeManager.getTemplateBookContent().replace("\n", "").replace("\r", "");
        String submittedContent = content.replace("\n", "").replace("\r", "");

        if (submittedContent.trim().equals(originalTemplate.trim()) ||
            submittedContent.trim().equals(KnowledgeManager.getTemplateBookContent().trim())) {
            player.sendMessage(Lang.get("book.edit-first", Config.assistantName));
            return;
        }

        KnowledgeEntry entry = KnowledgeManager.parseAndValidate(content);
        if (entry == null) {
            player.sendMessage(Lang.get("book.format-error", Config.assistantName));
            return;
        }

        // 未开启 AI 初审 → 退回原有同步提交行为
        if (!Config.playerSubmitReviewEnabled) {
            finishSubmit(player, entry);
            return;
        }

        // 廉价预检：重复 / 上限，命中则直接失败，避免浪费 API 调用
        if (KnowledgeQueue.isDuplicate(entry.getQuestion())
                || DataUtils.isPendingKnowledgeDuplicate(entry.getQuestion())) {
            player.sendMessage(Lang.get("book.submit-fail", Config.assistantName));
            return;
        }
        String uuid = player.getUniqueId().toString();
        if (DataContainer.playerPendingKnowledgeCount.getOrDefault(uuid, 0) >= Config.maxPendingKnowledgePerPlayer) {
            player.sendMessage(Lang.get("book.submit-fail", Config.assistantName));
            return;
        }

        // 异步过 AI 初审，结果回主线程处理
        player.sendMessage(Lang.get("book.reviewing", Config.assistantName));
        final UUID puid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            ReviewResult result = Config.reviewer == null ? null
                    : Config.reviewer.review(entry.getQuestion(), entry.getAnswer(), false);
            Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
                Player p = Bukkit.getPlayer(puid);
                if (p == null || !p.isOnline()) {
                    return;
                }
                if (result == null) {
                    // AI 不可用 → fail-closed，拦截并提示重试
                    p.sendMessage(Lang.get("book.review-unavailable", Config.assistantName));
                } else if (!result.isPass()) {
                    p.sendMessage(Lang.get("book.review-rejected", Config.assistantName, result.getReason()));
                } else {
                    finishSubmit(p, entry);
                }
            });
        });
    }

    /** 通过初审(或未开启初审)后真正入队，并消耗书本、反馈结果。须在主线程调用。 */
    private void finishSubmit(Player player, KnowledgeEntry entry) {
        if (KnowledgeManager.submitKnowledge(entry, player.getName())) {
            consumeSubmitBook(player);
            player.sendMessage(Lang.get("gui.divider-green"));
            player.sendMessage(Lang.get("book.submit-success-1", Config.assistantName));
            player.sendMessage(Lang.get("book.submit-success-2"));
            player.sendMessage(Lang.get("gui.divider-green"));
        } else {
            player.sendMessage(Lang.get("book.submit-fail", Config.assistantName));
        }
    }

    /** 仅当主手仍是书本时才消耗，避免异步延迟期间玩家换手导致误删其它物品。 */
    private void consumeSubmitBook(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null) {
            return;
        }
        String type = hand.getType().name();
        if (type.equals("WRITABLE_BOOK") || type.equals("WRITTEN_BOOK") || type.equals("BOOK_AND_QUILL")) {
            hand.setAmount(0);
        }
    }

    private void handleHistoryPromoteBook(PlayerEditBookEvent event, Player player, BookMeta meta) {
        EditContextManager.Context ctx = EditContextManager.get(player);
        if (ctx == null || ctx.type != EditContextManager.Type.HISTORY_PROMOTE) {
            player.sendMessage(Lang.get("book.format-error", Config.assistantName));
            return;
        }

        String content = joinPages(meta.getPages());
        KnowledgeEntry entry = KnowledgeManager.parseAndValidate(content);
        if (entry == null) {
            player.sendMessage(Lang.get("book.format-error", Config.assistantName));
            return;
        }

        if (KnowledgeFileManager.isDuplicateQuestion(entry.getQuestion())) {
            player.sendMessage(Lang.get("book.kb-duplicate", Config.assistantName));
            return;
        }

        KnowledgeFileManager.append(entry);
        KnowledgeFileManager.reloadKnowledgeBaseAsync();
        EditContextManager.clear(player);

        player.getInventory().getItemInMainHand().setAmount(0);
        player.sendMessage(Lang.get("gui.divider-green"));
        player.sendMessage(Lang.get("book.history-promote-success", Config.assistantName));
        player.sendMessage(Lang.get("gui.divider-green"));
    }

    private void handleKbEditBook(PlayerEditBookEvent event, Player player, BookMeta meta) {
        EditContextManager.Context ctx = EditContextManager.get(player);
        if (ctx == null || ctx.type != EditContextManager.Type.KB_EDIT) {
            player.sendMessage(Lang.get("book.format-error", Config.assistantName));
            return;
        }

        String content = joinPages(meta.getPages());
        KnowledgeEntry entry = KnowledgeManager.parseAndValidate(content);
        if (entry == null) {
            player.sendMessage(Lang.get("book.format-error", Config.assistantName));
            return;
        }

        KnowledgeFileManager.updateByIndex(ctx.targetId, entry);
        KnowledgeFileManager.reloadKnowledgeBaseAsync();
        EditContextManager.clear(player);

        player.getInventory().getItemInMainHand().setAmount(0);
        player.sendMessage(Lang.get("gui.divider-green"));
        player.sendMessage(Lang.get("book.kb-edit-success", Config.assistantName, ctx.targetId + 1));
        player.sendMessage(Lang.get("gui.divider-green"));
    }

    private String joinPages(List<String> pages) {
        StringBuilder sb = new StringBuilder();
        for (String page : pages) {
            sb.append(page).append("\n");
        }
        return sb.toString();
    }
}
