package com.exai.evaluation;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.PlayerQuestion;
import com.exai.entity.RagResult;
import com.exai.entity.RagStatus;
import com.exai.entity.RetrievedDocument;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.PluginDescriptionManager;
import com.exai.manager.GameKnowledgeBase;
import com.exai.generators.AnswerGenerator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单任务、可取消的离线 RAG 回归评估器。 */
public final class RagEvaluationService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean cancelRequested;
    private static volatile int total;
    private static volatile int completed;
    private static volatile String lastReportPath = "";
    private static volatile String lastSummary = "";

    private RagEvaluationService() {}

    public static boolean start(CommandSender sender) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        cancelRequested = false;
        completed = 0;
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> run(sender));
        return true;
    }

    private static void run(CommandSender sender) {
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> details = new ArrayList<>();
        int valid = 0;
        int invalid = 0;
        int passed = 0;
        int answerCases = 0;
        int retrievalHits = 0;
        int expectedSourceCount = 0;
        int foundSourceCount = 0;
        double reciprocalRankSum = 0.0;
        int refusalCases = 0;
        int refusalPasses = 0;
        int unsupportedClaims = 0;
        int judgeCount = 0;
        int judgeErrors = 0;
        int correctnessSum = 0;
        int faithfulnessSum = 0;
        int relevanceSum = 0;
        long retrievalMillis = 0L;
        long generationMillis = 0L;
        try {
            List<EvaluationCase> cases = new ArrayList<>();
            for (EvaluationCase item : EvaluationTestStore.readAll()) if (item.isEnabled()) cases.add(item);
            total = cases.size();
            GameKnowledgeBase knowledgeSnapshot = Config.knowledgeBase;
            AnswerGenerator generatorSnapshot = Config.generator;
            Set<String> knownIds = new HashSet<>();
            for (KnowledgeEntry entry : KnowledgeFileManager.readAll()) {
                if (entry.getKnowledgeId() != null && !entry.getKnowledgeId().trim().isEmpty()) {
                    knownIds.add(entry.getKnowledgeId());
                }
            }
            PluginDescriptionManager.readAll().forEach(entry -> {
                if (entry.isEnabled() && entry.getDescription() != null
                        && !entry.getDescription().trim().isEmpty()) {
                    knownIds.add("plugin_" + entry.getName());
                }
            });

            for (EvaluationCase item : cases) {
                if (cancelRequested) break;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("caseId", item.getId());
                detail.put("question", item.getQuestion());
                detail.put("expectedBehavior", item.getExpectedBehavior().name());
                detail.put("expectedKnowledgeIds", item.getExpectedKnowledgeIds());

                if (item.getExpectedBehavior() == ExpectedBehavior.ANSWER
                        && !knownIds.containsAll(item.getExpectedKnowledgeIds())) {
                    detail.put("status", "INVALID_CASE");
                    detail.put("reason", "预期知识 ID 不存在");
                    invalid++;
                    details.add(detail);
                    completed++;
                    continue;
                }

                valid++;
                RagResult result = generatorSnapshot.generateRagAnswer(
                        new PlayerQuestion(item.getQuestion()), false, false, knowledgeSnapshot);
                retrievalMillis += result.getRetrievalMillis();
                generationMillis += result.getGenerationMillis();
                detail.put("ragStatus", result.getAnswer().getStatus().name());
                detail.put("answer", result.getAnswer().getAnswer());
                detail.put("retrievalMillis", result.getRetrievalMillis());
                detail.put("generationMillis", result.getGenerationMillis());
                if (result.getAnswerability() != null) {
                    Map<String, Object> gate = new LinkedHashMap<>();
                    gate.put("answerable", result.getAnswerability().isAnswerable());
                    gate.put("parseStatus", result.getAnswerability().getParseStatus());
                    gate.put("rawResponse", result.getAnswerability().getRawResponse());
                    gate.put("evidence", result.getAnswerability().getEvidence());
                    gate.put("reason", result.getAnswerability().getReason());
                    detail.put("answerability", gate);
                }

                List<Map<String, Object>> retrieved = new ArrayList<>();
                Map<String, Integer> ranks = new LinkedHashMap<>();
                for (RetrievedDocument doc : result.getDocuments()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", doc.getDocument().getId());
                    row.put("rank", doc.getRank());
                    row.put("similarity", doc.getSimilarity());
                    row.put("bm25Score", doc.getBm25Score());
                    row.put("fusedScore", doc.getFusedScore());
                    row.put("embeddingRank", doc.getEmbeddingRank());
                    row.put("bm25Rank", doc.getBm25Rank());
                    retrieved.add(row);
                    ranks.put(doc.getDocument().getId(), doc.getRank());
                }
                detail.put("retrieved", retrieved);

                boolean casePass;
                if (item.getExpectedBehavior() == ExpectedBehavior.REFUSE) {
                    refusalCases++;
                    casePass = result.getAnswer().getStatus() == RagStatus.NO_DOCUMENT
                            || result.getAnswer().getStatus() == RagStatus.MODEL_REFUSED;
                    if (casePass) refusalPasses++;
                    detail.put("retrievalFalsePositive", !result.getDocuments().isEmpty());
                } else {
                    answerCases++;
                    expectedSourceCount += item.getExpectedKnowledgeIds().size();
                    int bestRank = Integer.MAX_VALUE;
                    int found = 0;
                    for (String expected : item.getExpectedKnowledgeIds()) {
                        Integer rank = ranks.get(expected);
                        if (rank != null) {
                            found++;
                            bestRank = Math.min(bestRank, rank);
                        }
                    }
                    foundSourceCount += found;
                    boolean hit = found > 0;
                    if (hit) {
                        retrievalHits++;
                        reciprocalRankSum += 1.0 / bestRank;
                    }
                    detail.put("retrievalHit", hit);
                    detail.put("retrievalRecall", item.getExpectedKnowledgeIds().isEmpty()
                            ? 0.0 : (double) found / item.getExpectedKnowledgeIds().size());

                    JudgeResult judge = judge(item, result);
                    detail.put("judge", judge.toMap());
                    detail.put("judgeStatus", judge.valid ? "OK" : "JUDGE_ERROR");
                    if (judge.valid) {
                        judgeCount++;
                        correctnessSum += judge.correctness;
                        faithfulnessSum += judge.faithfulness;
                        relevanceSum += judge.relevance;
                    } else {
                        judgeErrors++;
                    }
                    if (judge.unsupportedClaim) unsupportedClaims++;
                    casePass = hit && judge.valid
                            && judge.correctness >= Config.evaluationPassScore
                            && judge.faithfulness >= Config.evaluationPassScore
                            && judge.relevance >= Config.evaluationPassScore
                            && !judge.unsupportedClaim;
                }
                detail.put("status", casePass ? "PASS" : "FAIL");
                if (casePass) passed++;
                details.add(detail);
                completed++;
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("cancelled", cancelRequested);
            summary.put("total", total);
            summary.put("completed", completed);
            summary.put("valid", valid);
            summary.put("invalid", invalid);
            summary.put("passed", passed);
            summary.put("passRate", valid == 0 ? 0.0 : (double) passed / valid);
            summary.put("hitAtK", answerCases == 0 ? 0.0 : (double) retrievalHits / answerCases);
            summary.put("recallAtK", expectedSourceCount == 0 ? 0.0 : (double) foundSourceCount / expectedSourceCount);
            summary.put("mrr", answerCases == 0 ? 0.0 : reciprocalRankSum / answerCases);
            summary.put("refusalAccuracy", refusalCases == 0 ? 0.0 : (double) refusalPasses / refusalCases);
            summary.put("unsupportedClaims", unsupportedClaims);
            summary.put("judgeErrors", judgeErrors);
            summary.put("averageCorrectness", judgeCount == 0 ? 0.0 : (double) correctnessSum / judgeCount);
            summary.put("averageFaithfulness", judgeCount == 0 ? 0.0 : (double) faithfulnessSum / judgeCount);
            summary.put("averageRelevance", judgeCount == 0 ? 0.0 : (double) relevanceSum / judgeCount);
            summary.put("averageRetrievalMillis", valid == 0 ? 0L : retrievalMillis / valid);
            summary.put("averageGenerationMillis", valid == 0 ? 0L : generationMillis / valid);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("startedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(startedAt)));
            report.put("durationMillis", System.currentTimeMillis() - startedAt);
            report.put("model", Config.llmModel);
            report.put("embeddingModel", Config.embeddingModel);
            report.put("datasetStorage", Config.storageType);
            report.put("minSimilarity", Config.minSimilarity);
            report.put("maxDocs", Config.maxDocs);
            Map<String, Object> hybrid = new LinkedHashMap<>();
            hybrid.put("embeddingWeight", Config.hybridEmbeddingWeight);
            hybrid.put("bm25Weight", Config.hybridBm25Weight);
            hybrid.put("rrfK", Config.hybridRrfK);
            hybrid.put("candidatePoolSize", Config.hybridCandidatePoolSize);
            report.put("hybridRetrieval", hybrid);
            report.put("knowledgeFingerprint", knowledgeFingerprint());
            report.put("promptFingerprint", promptFingerprint());
            report.put("summary", summary);
            report.put("cases", details);
            lastReportPath = writeReport(report);
            List<String> summaryLines = Arrays.asList(
                    Lang.get("rag.test-summary", completed, total, valid, passed, invalid,
                            valid == 0 ? "N/A" : percent((Double) summary.get("passRate"))),
                    Lang.get("rag.test-metrics-retrieval",
                            answerCases == 0 ? "N/A" : percent((Double) summary.get("hitAtK")),
                            answerCases == 0 ? "N/A" : percent((Double) summary.get("recallAtK")),
                            answerCases == 0 ? "N/A" : decimal((Double) summary.get("mrr")),
                            refusalCases == 0 ? "N/A" : percent((Double) summary.get("refusalAccuracy"))),
                    Lang.get("rag.test-metrics-quality",
                            judgeCount == 0 ? "N/A" : decimal((Double) summary.get("averageCorrectness")),
                            judgeCount == 0 ? "N/A" : decimal((Double) summary.get("averageFaithfulness")),
                            judgeCount == 0 ? "N/A" : decimal((Double) summary.get("averageRelevance"))),
                    Lang.get("rag.test-metrics-risk", unsupportedClaims, judgeErrors),
                    Lang.get("rag.test-metrics-latency",
                            valid == 0 ? "N/A" : summary.get("averageRetrievalMillis") + "ms",
                            valid == 0 ? "N/A" : summary.get("averageGenerationMillis") + "ms")
            );
            lastSummary = String.join("\n", summaryLines);
            for (String line : summaryLines) send(sender, line);
            send(sender, Lang.get("rag.test-report", lastReportPath));
        } catch (Exception e) {
            lastSummary = Lang.get("rag.test-error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            send(sender, lastSummary);
        } finally {
            RUNNING.set(false);
        }
    }

    private static JudgeResult judge(EvaluationCase item, RagResult result) {
        if (result.getAnswer().getStatus() != RagStatus.ANSWERED) {
            return JudgeResult.invalid("RAG 未生成有效答案");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("question", item.getQuestion());
        payload.addProperty("referenceAnswer", item.getReferenceAnswer());
        payload.addProperty("actualAnswer", result.getAnswer().getAnswer());
        com.google.gson.JsonArray docs = new com.google.gson.JsonArray();
        for (RetrievedDocument doc : result.getDocuments()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", doc.getDocument().getId());
            value.addProperty("content", doc.getDocument().getContent());
            docs.add(value);
        }
        payload.add("retrievedDocuments", docs);
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw = Config.llm.complete(Lang.get("prompt.rag-judge") + "\n" + payload, 0.0, 500);
            JudgeResult parsed = parseJudge(raw);
            if (parsed != null) return parsed;
        }
        return JudgeResult.invalid("JUDGE_ERROR");
    }

    private static JudgeResult parseJudge(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            JsonElement element = new JsonParser().parse(raw.substring(start, end + 1));
            if (!element.isJsonObject()) return null;
            JsonObject o = element.getAsJsonObject();
            JudgeResult result = new JudgeResult();
            result.correctness = score(o, "correctness");
            result.faithfulness = score(o, "faithfulness");
            result.relevance = score(o, "relevance");
            result.unsupportedClaim = o.has("unsupportedClaim") && o.get("unsupportedClaim").getAsBoolean();
            result.reason = o.has("reason") ? o.get("reason").getAsString() : "";
            result.valid = result.correctness >= 0 && result.faithfulness >= 0 && result.relevance >= 0;
            return result.valid ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int score(JsonObject o, String key) {
        if (!o.has(key)) return -1;
        int value = o.get(key).getAsInt();
        return value < 0 || value > 5 ? -1 : value;
    }

    private static String writeReport(Map<String, Object> report) throws Exception {
        File dir = new File(ExAI.getInstance().getDataFolder(), "evaluation/results");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, new SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS").format(new Date()) + ".json");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(report, writer);
        }
        prune(dir);
        return file.getAbsolutePath();
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length <= Config.evaluationResultRetention) return;
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = Math.max(1, Config.evaluationResultRetention); i < files.length; i++) files[i].delete();
    }

    private static String knowledgeFingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (KnowledgeEntry entry : KnowledgeFileManager.readAll()) {
            String row = String.valueOf(entry.getKnowledgeId()) + "\u0000" + entry.getQuestion()
                    + "\u0000" + entry.getAnswer() + "\n";
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        }
        PluginDescriptionManager.readAll().forEach(entry -> {
            String row = "plugin_" + entry.getName() + "\u0000" + entry.isEnabled()
                    + "\u0000" + String.valueOf(entry.getDescription()) + "\n";
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        });
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String promptFingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String prompts = Lang.get("prompt.base-role") + Lang.get("prompt.strict-with-docs")
                + Lang.get("prompt.grounding-reminder") + Lang.get("prompt.answerability-check")
                + Lang.get("prompt.rag-judge");
        digest.update(prompts.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static void send(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> sender.sendMessage(message));
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public static boolean cancel() {
        if (!RUNNING.get()) return false;
        cancelRequested = true;
        return true;
    }

    public static boolean isRunning() { return RUNNING.get(); }
    public static int getTotal() { return total; }
    public static int getCompleted() { return completed; }
    public static String getLastReportPath() { return lastReportPath; }
    public static String getLastSummary() { return lastSummary; }

    private static final class JudgeResult {
        boolean valid;
        int correctness = -1;
        int faithfulness = -1;
        int relevance = -1;
        boolean unsupportedClaim;
        String reason = "";

        static JudgeResult invalid(String reason) {
            JudgeResult result = new JudgeResult();
            result.reason = reason;
            return result;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", valid);
            out.put("correctness", correctness);
            out.put("faithfulness", faithfulness);
            out.put("relevance", relevance);
            out.put("unsupportedClaim", unsupportedClaim);
            out.put("reason", reason);
            return out;
        }
    }
}
