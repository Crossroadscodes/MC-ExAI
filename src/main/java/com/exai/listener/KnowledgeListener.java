package com.exai.listener;

import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.exai.manager.EditContextManager;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.KnowledgeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;

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

        if (KnowledgeManager.submitKnowledge(entry, player.getName())) {
            player.getInventory().getItemInMainHand().setAmount(0);
            player.sendMessage(Lang.get("gui.divider-green"));
            player.sendMessage(Lang.get("book.submit-success-1", Config.assistantName));
            player.sendMessage(Lang.get("book.submit-success-2"));
            player.sendMessage(Lang.get("gui.divider-green"));
        } else {
            player.sendMessage(Lang.get("book.submit-fail", Config.assistantName));
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
