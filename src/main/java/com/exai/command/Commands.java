package com.exai.command;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.entity.RagStatus;
import com.exai.evaluation.EvaluationTestStore;
import com.exai.evaluation.RagEvaluationService;
import com.exai.gui.ChestGUI;
import com.exai.gui.RagDatasetGUI;
import com.exai.i18n.Lang;
import com.exai.service.DocumentImportService;
import com.exai.service.QuestionClassifier;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.KnowledgeIdService;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import com.exai.web.WebAuth;
import com.exai.web.WebServer;
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

    private static final String[] SUB_COMMANDS = {"reload", "opengui", "question", "import", "webpasswd", "rag", "help"};

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("exai")) {
            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            if (sender instanceof Player) {
                if ((subCommand.equals("reload") || subCommand.equals("question")
                        || subCommand.equals("import") || subCommand.equals("rag"))
                        && !Config.hasOpPermission(sender)) {
                    sender.sendMessage(Lang.get("command.no-permission"));
                    return false;
                }
            }

            switch (subCommand) {
                case "reload":
                    if (KnowledgeIdService.isRunning() || RagEvaluationService.isRunning()) {
                        sender.sendMessage(Lang.get("rag.reload-busy"));
                        return true;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                        Config.loadAll();
                        sender.sendMessage(Lang.get("command.reload-success", Config.assistantName));
                    });
                    return true;

                case "opengui":
                    Player p = (Player) sender;
                    ChestGUI.open(p);
                    return true;

                case "question":
                    if (args.length < 3) {
                        sender.sendMessage(Lang.get("command.question-usage"));
                        return false;
                    }
                    Player target = Bukkit.getServer().getPlayerExact(args[1]);
                    if (target == null) {
                        sender.sendMessage(Lang.get("command.player-not-found", args[1]));
                        return false;
                    }
                    String playerName = target.getName();
                    String question = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                    if (CDUtils.isCommandCDEnd(target)) {
                        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                            QuestionClassifier.Result classification = new QuestionClassifier(Config.llm).classify(question);
                            if (classification == QuestionClassifier.Result.NOT_QUESTION) {
                                String output = Lang.get("chat.not-a-question-empty");
                                sendSync(sender, Lang.get("command.question-prefix", Config.assistantName, output));
                                DataUtils.insertLogAsync(playerName, question, output, "",
                                        Lang.get("log.source-command"), RagStatus.NOT_QUESTION);
                                return;
                            }
                            if (classification == QuestionClassifier.Result.UNAVAILABLE) {
                                String output = Lang.get("chat.question-classifier-unavailable");
                                sendSync(sender, Lang.get("command.question-prefix", Config.assistantName, output));
                                DataUtils.insertLogAsync(playerName, question, output, "",
                                        Lang.get("log.source-command"), RagStatus.MODEL_ERROR);
                                return;
                            }
                            PlayerQuestion pQuestion = new PlayerQuestion(question);
                            Answer answer = Config.generator.generateAnswer(pQuestion, true);
                            if (answer.getStatus() == RagStatus.ANSWERED
                                    && !answer.isUnknown()
                                    && !CDUtils.tryStartCommandCD(target)) {
                                return;
                            }
                            String documents = String.join(", ", answer.getSources());
                            sendSync(sender, Lang.get("command.question-prefix", Config.assistantName, answer.getAnswer()));
                            DataUtils.insertLogAsync(playerName, question, answer.getAnswer(), documents,
                                    Lang.get("log.source-command"), answer.getStatus());
                        });
                    }
                    return true;

                case "import":
                    if (args.length < 2) {
                        sender.sendMessage(Lang.get("command.import-usage"));
                        return false;
                    }
                    String importFile = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(),
                            () -> DocumentImportService.importFile(sender, importFile));
                    return true;

                case "webpasswd":
                    if ((sender instanceof Player && !sender.isOp()) || args.length != 3) {
                        sender.sendMessage(Lang.get("command.webpasswd-usage"));
                        return false;
                    }
                    String webUsername = args[1].trim();
                    String webPassword = args[2];
                    if (!webUsername.matches("[A-Za-z0-9_.-]{3,32}")) {
                        sender.sendMessage(Lang.get("command.webpasswd-invalid-username"));
                        return false;
                    }
                    if (webPassword.length() < 12) {
                        sender.sendMessage(Lang.get("command.webpasswd-short-password"));
                        return false;
                    }
                    try {
                        String hash = WebAuth.hashPassword(webPassword);
                        ExAI.getInstance().getConfig().set("webui.auth.username", webUsername);
                        ExAI.getInstance().getConfig().set("webui.auth.passwordHash", hash);
                        ExAI.getInstance().saveConfig();
                        Config.webuiAuthUsername = webUsername;
                        Config.webuiAuthPasswordHash = hash;
                        WebServer.invalidateWebSessions();
                        WebServer.apply();
                        sender.sendMessage(Lang.get("command.webpasswd-success"));
                    } catch (Exception e) {
                        sender.sendMessage(Lang.get("command.webpasswd-error", e.getMessage()));
                    }
                    return true;

                case "rag":
                    return handleRag(sender, args);

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
        sender.sendMessage(Lang.get("command.help-header"));
        sender.sendMessage(Lang.get("command.help-help"));
        if (Config.hasOpPermission(sender)) {
            sender.sendMessage(Lang.get("command.help-reload"));
            sender.sendMessage(Lang.get("command.help-opengui"));
            sender.sendMessage(Lang.get("command.help-question"));
            sender.sendMessage(Lang.get("command.help-import"));
            sender.sendMessage(Lang.get("command.help-webpasswd"));
            sender.sendMessage(Lang.get("command.help-rag"));
        } else {
            sender.sendMessage(Lang.get("command.help-opengui"));
        }
        sender.sendMessage(Lang.get("command.help-footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            String[] cmds = Config.hasOpPermission(sender) ? SUB_COMMANDS : new String[]{"help", "opengui"};
            return Arrays.stream(cmds)
                    .filter(cmd -> cmd.startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("question")) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("import") && sender.isOp()) {
            String input = args[1].toLowerCase();
            String[] files = DocumentImportService.importFolder().list(
                    (dir, name) -> DocumentImportService.isSupported(name));
            if (files == null) {
                return Collections.emptyList();
            }
            return Arrays.stream(files)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rag") && Config.hasOpPermission(sender)) {
            String input = args[1].toLowerCase();
            return Arrays.stream(new String[]{"dataset", "ids", "idregen", "test", "status", "cancel", "report"})
                    .filter(name -> name.startsWith(input))
                    .collect(Collectors.toList());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("rag")
                && args[1].equalsIgnoreCase("idregen") && Config.hasOpPermission(sender)) {
            String input = args[2].toLowerCase();
            return KnowledgeFileManager.readAll().stream()
                    .map(e -> e.getKnowledgeId())
                    .filter(id -> id != null && id.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean handleRag(CommandSender sender, String[] args) {
        if (!Config.hasOpPermission(sender)) {
            sender.sendMessage(Lang.get("command.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Lang.get("rag.usage"));
            return true;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "dataset":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Lang.get("rag.dataset-player-only"));
                    return true;
                }
                RagDatasetGUI.open((Player) sender);
                return true;
            case "ids":
                if (!Config.isLlmConfigured()) {
                    sender.sendMessage(Lang.get("service.llm.not-configured"));
                    return true;
                }
                if (RagEvaluationService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.test-running"));
                    return true;
                }
                if (KnowledgeIdService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.id-running"));
                    return true;
                }
                sender.sendMessage(Lang.get("rag.id-started"));
                Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                    KnowledgeIdService.ensureMissingIds(KnowledgeFileManager.readAll());
                    Config.reloadKnowledgeBaseOnly();
                    sendSync(sender, Lang.get("rag.id-finished", KnowledgeIdService.getLastGenerated(),
                            KnowledgeIdService.getLastError()));
                });
                return true;
            case "idregen":
                if (args.length < 3) {
                    sender.sendMessage(Lang.get("rag.idregen-usage"));
                    return true;
                }
                if (!Config.isLlmConfigured()) {
                    sender.sendMessage(Lang.get("service.llm.not-configured"));
                    return true;
                }
                if (RagEvaluationService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.test-running"));
                    return true;
                }
                String oldId = args[2];
                Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                    String newId = KnowledgeIdService.regenerate(oldId);
                    if (newId == null) {
                        sendSync(sender, Lang.get("rag.idregen-failed", KnowledgeIdService.getLastError()));
                        return;
                    }
                    int changed = EvaluationTestStore.replaceKnowledgeId(oldId, newId);
                    Config.reloadKnowledgeBaseOnly();
                    sendSync(sender, Lang.get("rag.idregen-success", oldId, newId, changed));
                });
                return true;
            case "test":
                if (!Config.isLlmConfigured()) {
                    sender.sendMessage(Lang.get("service.llm.not-configured"));
                } else if (Config.knowledgeBase == null || !Config.knowledgeBase.isReady()) {
                    sender.sendMessage(Lang.get("rag.knowledge-not-ready"));
                } else if (KnowledgeIdService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.id-running"));
                } else if (RagEvaluationService.start(sender)) {
                    sender.sendMessage(Lang.get("rag.test-started"));
                } else {
                    sender.sendMessage(Lang.get("rag.test-running"));
                }
                return true;
            case "status":
                if (KnowledgeIdService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.id-status", KnowledgeIdService.getCompleted(),
                            KnowledgeIdService.getTotal()));
                } else if (RagEvaluationService.isRunning()) {
                    sender.sendMessage(Lang.get("rag.test-status", RagEvaluationService.getCompleted(),
                            RagEvaluationService.getTotal()));
                } else {
                    sender.sendMessage(Lang.get("rag.idle"));
                }
                return true;
            case "cancel":
                sender.sendMessage(RagEvaluationService.cancel()
                        ? Lang.get("rag.cancel-requested") : Lang.get("rag.no-running-test"));
                return true;
            case "report":
                if (RagEvaluationService.getLastReportPath().isEmpty()) {
                    sender.sendMessage(Lang.get("rag.no-report"));
                } else {
                    for (String line : RagEvaluationService.getLastSummary().split("\\n")) {
                        sender.sendMessage(line);
                    }
                    sender.sendMessage(Lang.get("rag.test-report", RagEvaluationService.getLastReportPath()));
                }
                return true;
            default:
                sender.sendMessage(Lang.get("rag.usage"));
                return true;
        }
    }

    private void sendSync(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> sender.sendMessage(message));
    }
}
