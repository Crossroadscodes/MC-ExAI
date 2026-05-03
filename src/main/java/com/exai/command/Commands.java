package com.exai.command;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.gui.ChestGUI;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Commands implements CommandExecutor, TabCompleter {

    private static final String[] SUB_COMMANDS = {"reload", "opengui", "question", "help"};

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("exai")) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            if (sender instanceof Player) {
                if ((subCommand.equals("reload") || subCommand.equals("question")) && !sender.isOp()) {
                    sender.sendMessage("§c【系统提示】 §f你没有权限这么做！");
                    return false;
                }
            }

            switch (subCommand) {
                case "reload":
                    Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                        Config.loadAll();
                        sender.sendMessage("§c【系统提示】§f" + Config.assistantName + "重载完毕");
                    });
                    return true;

                case "opengui":
                    Player p = (Player) sender;
                    ChestGUI.open(p);
                    return true;

                case "question":
                    if (args.length < 3) {
                        sender.sendMessage("§c用法: /exai question <玩家> <问题>");
                        return false;
                    }
                    Player target = Bukkit.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage("§c找不到玩家: " + args[1]);
                        return false;
                    }
                    String playerName = target.getName();
                    if (CDUtils.isCDEnd(target)) {
                        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                            String question = args[2];
                            PlayerQuestion pQuestion = new PlayerQuestion(question);
                            Answer answer = Config.generator.generateAnswer(pQuestion, true);
                            String documents = String.join(", ", answer.getSources());
                            sender.sendMessage("§c【系统提示】§f" + Config.assistantName + "：" + answer.getAnswer());
                            DataUtils.insertLogAsync(playerName, question, answer.getAnswer(), documents, "单独聊天");
                        });
                    }
                    return true;

                case "help":
                    sendHelp(sender);
                    return true;

                default:
                    sendHelp(sender);
                    return true;
            }
        }
        return false;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6════ §e§lExAI 命令帮助 §6════");
        sender.sendMessage("§e/exai help §7- 显示此帮助信息");
        if (sender.isOp()) {
            sender.sendMessage("§e/exai reload §7- 重载插件配置");
            sender.sendMessage("§e/exai opengui §7- 打开ExAI GUI界面");
            sender.sendMessage("§e/exai question <玩家> <问题> §7- 向指定玩家发送问题");
} else {
            sender.sendMessage("§e/exai opengui §7- 打开ExAI GUI界面");
        }
        sender.sendMessage("§6═══════════════════════");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            String[] cmds = sender.isOp() ? SUB_COMMANDS : new String[]{"help", "opengui"};
            return Arrays.stream(cmds)
                    .filter(cmd -> cmd.startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("question")) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}