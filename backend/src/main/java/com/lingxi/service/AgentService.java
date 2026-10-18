package com.lingxi.service;

import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ChatSessionEntity;
import com.lingxi.rag.ChineseTokenizer;
import com.lingxi.rag.RetrievalService;
import com.lingxi.rag.TextTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final List<String> DB_INTENT_WORDS = List.of(
            "销售额", "营收", "业绩", "订单量", "订单数", "客单价", "销售数据", "经营数据",
            "环比", "同比", "毛利率", "收入", "回款", "区域排名", "销售排行", "季度销售"
    );
    private static final List<String> WEB_INTENT_WORDS = List.of(
            "联网", "网上", "搜索一下", "查一下最新", "最新政策", "实时", "新闻", "外部资料", "互联网"
    );

    private final SessionService sessions;
    private final KnowledgeService knowledge;
    private final RetrievalService retrievalService;
    private final DatabaseTool databaseTool;
    private final WebSearchTool webSearchTool;
    private final LlmClient llm;
    private final TextTools textTools;
    private final ChineseTokenizer tokenizer;
    private final LingxiProperties properties;

    public AgentService(
            SessionService sessions,
            KnowledgeService knowledge,
            RetrievalService retrievalService,
            DatabaseTool databaseTool,
            WebSearchTool webSearchTool,
            LlmClient llm,
            TextTools textTools,
            ChineseTokenizer tokenizer,
            LingxiProperties properties
    ) {
        this.sessions = sessions;
        this.knowledge = knowledge;
        this.retrievalService = retrievalService;
        this.databaseTool = databaseTool;
        this.webSearchTool = webSearchTool;
        this.llm = llm;
        this.textTools = textTools;
        this.tokenizer = tokenizer;
        this.properties = properties;
    }

    public Dtos.ChatResult run(String rawQuestion, String requestedSessionId, boolean useWeb,
                               boolean streaming, EventSink sink) throws IOException {
        long started = System.currentTimeMillis();
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        String title = textTools.makeTitle(question, 24);
        ChatSessionEntity session = sessions.getOrCreate(requestedSessionId, title);
        boolean isNew = requestedSessionId == null || requestedSessionId.isBlank()
                || !requestedSessionId.equals(session.getId());
        List<Dtos.MessageDto> history = sessions.history(session.getId(), 8);
        Dtos.MessageDto userMessage = sessions.addMessage(session.getId(), "user", question, List.of(), List.of(), 0);

        sink.emit("meta", mapOf(
                "session_id", session.getId(),
                "user_message_id", userMessage.id(),
                "is_new_session", isNew,
                "title", session.getTitle(),
                "model", llm.modelLabel(),
                "mode", llm.mode()
        ));

        List<String> answerParts = new ArrayList<>();
        List<Dtos.Citation> citations = new ArrayList<>();
        List<Dtos.ToolTrace> traces = new ArrayList<>();
        RetrievalService.RetrievalResult retrieval = null;
        DatabaseTool.DatabaseResult databaseResult = null;
        WebSearchTool.WebSearchResponse webResponse = null;
        boolean saved = false;
        String mode = "extractive";

        try {
            if (textTools.isSmalltalk(question)) {
                sink.emit("status", mapOf("stage", "generating", "label", "正在准备回答"));
                String greeting = smalltalkAnswer();
                streamDemoText(greeting, streaming, answerParts, sink);
                int elapsed = elapsed(started);
                Dtos.MessageDto assistant = sessions.addMessage(
                        session.getId(), "assistant", greeting, List.of(), List.of(), elapsed
                );
                saved = true;
                emitDone(sink, assistant.id(), session.getId(), elapsed, llm.modelLabel(), "extractive",
                        List.of("员工年假怎么休？", "2026Q3 各区域销售额怎么样？", "灵犀的 RAG 检索链路是怎样的？"));
                return new Dtos.ChatResult(session.getId(), assistant.id(), greeting, List.of(), List.of(),
                        elapsed, llm.modelLabel(), "extractive");
            }

            List<ToolPlan> plans = plan(question, useWeb);
            sink.emit("status", mapOf(
                    "stage", "planning",
                    "label", "已完成问题理解，正在调用工具",
                    "plan", plans.stream().map(plan -> mapOf("name", plan.name(), "reason", plan.reason())).toList()
            ));

            for (ToolPlan plan : plans) {
                Dtos.ToolTrace running = new Dtos.ToolTrace(
                        UUID.randomUUID().toString(), plan.name(), toolLabel(plan.name()), "running",
                        toolInput(plan.name(), question), "", 0, Map.of()
                );
                traces.add(running);
                sink.emit("tool", running);
                long toolStarted = System.currentTimeMillis();
                Dtos.ToolTrace finished;
                try {
                    if ("knowledge_search".equals(plan.name())) {
                        retrieval = retrievalService.search(question, history);
                        citations = new ArrayList<>(retrieval.citations());
                        Map<String, Object> meta = new LinkedHashMap<>(retrieval.diagnostic());
                        meta.put("candidates", retrieval.candidates());
                        meta.put("confidence", retrieval.confidence());
                        meta.put("query", retrieval.query());
                        finished = new Dtos.ToolTrace(
                                running.id(), running.name(), running.label(), "success", running.input(),
                                "召回 " + citations.size() + " 个有效片段 · 候选 " + retrieval.candidates()
                                        + " 个 · 最高相关度 " + String.format(Locale.ROOT, "%.2f", retrieval.confidence()),
                                elapsed(toolStarted), meta
                        );
                        replaceTrace(traces, finished);
                        sink.emit("tool", finished);
                        if (!citations.isEmpty()) sink.emit("citations", mapOf("citations", citations));
                    } else if ("database_query".equals(plan.name())) {
                        databaseResult = databaseTool.query(question);
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("query", databaseResult.queryDescription());
                        meta.put("row_count", databaseResult.rowCount());
                        meta.put("rows", databaseResult.rows());
                        finished = new Dtos.ToolTrace(
                                running.id(), running.name(), running.label(), "success", running.input(),
                                databaseResult.summary().replace("**", ""), elapsed(toolStarted), meta
                        );
                        replaceTrace(traces, finished);
                        sink.emit("tool", finished);
                    } else if ("web_search".equals(plan.name())) {
                        if (!webSearchTool.enabled()) {
                            finished = new Dtos.ToolTrace(
                                    running.id(), running.name(), running.label(), "skipped", running.input(),
                                    "联网搜索未启用，已仅使用内部知识库与经营数据库。", elapsed(toolStarted), Map.of()
                            );
                        } else {
                            webResponse = webSearchTool.search(question);
                            Map<String, Object> meta = new LinkedHashMap<>();
                            meta.put("query", webResponse.query());
                            meta.put("results", webResponse.results());
                            meta.put("error", webResponse.error());
                            finished = new Dtos.ToolTrace(
                                    running.id(), running.name(), running.label(),
                                    webResponse.available() ? "success" : "error", running.input(),
                                    webResponse.summary(), elapsed(toolStarted), meta
                            );
                        }
                        replaceTrace(traces, finished);
                        sink.emit("tool", finished);
                    } else {
                        finished = new Dtos.ToolTrace(
                                running.id(), running.name(), running.label(), "skipped", running.input(),
                                "未知工具，已跳过", elapsed(toolStarted), Map.of()
                        );
                        replaceTrace(traces, finished);
                        sink.emit("tool", finished);
                    }
                } catch (Exception exc) {
                    log.error("Tool {} failed: {}", plan.name(), exc.getMessage());
                    finished = new Dtos.ToolTrace(
                            running.id(), running.name(), running.label(), "error", running.input(),
                            "工具执行失败：" + truncate(exc.getMessage(), 240), elapsed(toolStarted), Map.of()
                    );
                    replaceTrace(traces, finished);
                    sink.emit("tool", finished);
                }
            }

            sink.emit("status", mapOf("stage", "verifying", "label", "正在核验证据与引用"));
            sleep(80);
            sink.emit("status", mapOf("stage", "generating", "label", "正在组织答案"));

            boolean generatedByLlm = false;
            if (llm.configured()) {
                AtomicBoolean anyToken = new AtomicBoolean(false);
                try {
                    List<Map<String, String>> messages = llm.buildMessages(
                            question,
                            retrieval == null ? "（无）" : retrieval.context(),
                            toolContext(databaseResult, webResponse),
                            history
                    );
                    boolean generated = llm.stream(messages, token -> {
                        anyToken.set(true);
                        answerParts.add(token);
                        sink.emit("token", mapOf("text", token));
                    });
                    generatedByLlm = generated && anyToken.get();
                } catch (Exception exc) {
                    log.error("LLM generation failed: {}", exc.getMessage());
                    if (!anyToken.get()) {
                        sink.emit("status", mapOf("stage", "fallback", "label", "模型服务不可用，已切换到本地证据回答"));
                    } else {
                        generatedByLlm = true;
                    }
                }
            }

            if (!generatedByLlm) {
                String fallback = composeExtractive(question, retrieval, databaseResult, webResponse);
                streamDemoText(fallback, streaming, answerParts, sink);
            }

            String answer = String.join("", answerParts).strip();
            if (answer.isEmpty()) {
                answer = "抱歉，本次生成没有返回有效内容，请稍后重试。";
                answerParts.add(answer);
                sink.emit("token", mapOf("text", answer));
            }
            int elapsed = elapsed(started);
            mode = generatedByLlm ? "llm" : "extractive";
            Dtos.MessageDto assistant = sessions.addMessage(
                    session.getId(), "assistant", answer, citations, traces, elapsed
            );
            saved = true;
            emitDone(sink, assistant.id(), session.getId(), elapsed, llm.modelLabel(), mode,
                    suggestions(retrieval, databaseResult));
            return new Dtos.ChatResult(
                    session.getId(), assistant.id(), answer, citations, traces, elapsed, llm.modelLabel(), mode
            );
        } finally {
            if (!saved) {
                String partial = String.join("", answerParts).strip();
                if (!partial.isBlank()) {
                    try {
                        sessions.addMessage(session.getId(), "assistant", partial, citations, traces, elapsed(started));
                    } catch (Exception exc) {
                        log.warn("Failed to save partial answer: {}", exc.getMessage());
                    }
                }
            }
        }
    }

    private List<ToolPlan> plan(String question, boolean useWeb) {
        List<ToolPlan> plans = new ArrayList<>();
        plans.add(new ToolPlan("knowledge_search", "优先在企业知识库中查找制度、产品与经营资料"));
        if (textTools.containsAny(question, DB_INTENT_WORDS)) {
            plans.add(new ToolPlan("database_query", "问题涉及结构化经营指标，需要查询数据库"));
        }
        if (useWeb || textTools.containsAny(question, WEB_INTENT_WORDS)) {
            plans.add(new ToolPlan("web_search", "问题需要或用户要求补充外部实时信息"));
        }
        return plans;
    }

    private static String toolLabel(String name) {
        return switch (name) {
            case "knowledge_search" -> "知识库混合检索";
            case "database_query" -> "经营数据库查询";
            case "web_search" -> "联网搜索";
            default -> name;
        };
    }

    private static String toolInput(String name, String question) {
        return switch (name) {
            case "knowledge_search" -> "向量召回 + BM25 召回 → RRF 融合 → Rerank Top-5";
            case "database_query" -> "自然语言指标解析 · " + truncate(question, 80);
            case "web_search" -> truncate(question, 120);
            default -> truncate(question, 120);
        };
    }

    private void replaceTrace(List<Dtos.ToolTrace> traces, Dtos.ToolTrace finished) {
        for (int i = 0; i < traces.size(); i++) {
            if (traces.get(i).id().equals(finished.id())) {
                traces.set(i, finished);
                return;
            }
        }
    }

    private static String toolContext(DatabaseTool.DatabaseResult databaseResult,
                                      WebSearchTool.WebSearchResponse webResponse) {
        List<String> parts = new ArrayList<>();
        if (databaseResult != null) {
            parts.add("【经营数据库 sales_orders】\n" + databaseResult.summary() + "\n\n"
                    + databaseResult.table() + "\n查询条件：" + databaseResult.queryDescription());
        }
        if (webResponse != null) {
            if (webResponse.available()) {
                StringBuilder links = new StringBuilder();
                for (WebSearchTool.SearchResult result : webResponse.results()) {
                    links.append("- ").append(result.title()).append(" ").append(result.url()).append("\n");
                }
                parts.add("【联网搜索结果】\n" + webResponse.summary() + "\n" + links);
            } else {
                parts.add("【联网搜索】\n" + webResponse.summary());
            }
        }
        return parts.isEmpty() ? "（本次未调用其他工具）" : String.join("\n\n", parts);
    }

    private String composeExtractive(
            String question,
            RetrievalService.RetrievalResult retrieval,
            DatabaseTool.DatabaseResult databaseResult,
            WebSearchTool.WebSearchResponse webResponse
    ) {
        List<Dtos.Citation> citations = retrieval == null ? List.of() : retrieval.citations();
        boolean hasToolData = databaseResult != null || (webResponse != null && webResponse.available());
        if (citations.isEmpty() && !hasToolData) {
            Dtos.Stats stats = knowledge.stats();
            String hint = webSearchTool.enabled() ? "\n- 如果问题需要外部信息，可以要求我“联网搜索”。" : "";
            return "我暂时没有在当前知识库中找到与「" + question + "」直接相关的依据。"
                    + "为了避免给出未经证实的结论，我先不做推测。\n\n"
                    + "你可以：\n"
                    + "1. 在「知识库」中上传相关制度、产品文档或经营报告；\n"
                    + "2. 换一种更具体的问法，例如补充部门、时间或流程名称；\n"
                    + "3. 当前知识库已有 **" + stats.readyDocumentCount() + "** 篇文档、**"
                    + stats.chunkCount() + "** 个可检索片段。" + hint;
        }

        List<String> lines = new ArrayList<>();
        String questionLabel = textTools.stripQuestionPrefix(question).replaceAll("^[？?。.\\s]+|[？?。.\\s]+$", "");
        if (databaseResult != null) {
            lines.add("### 经营数据\n");
            lines.add(databaseResult.summary());
            lines.add("");
            lines.add(databaseResult.table());
            lines.add("");
            lines.add("> 数据来自经营数据库 `sales_orders`，查询条件：`" + databaseResult.queryDescription() + "`。");
            lines.add("");
        }

        if (!citations.isEmpty()) {
            if (databaseResult != null) {
                lines.add("### 知识库中的相关说明\n");
            } else {
                lines.add("根据企业知识库中与「**" + questionLabel + "**」相关的内容，可以确认：\n");
            }
            Set<String> seen = new HashSet<>();
            List<CitationGroup> groups = new ArrayList<>();
            for (Dtos.Citation citation : citations) {
                String section = citation.section() == null || citation.section().isBlank()
                        ? "《" + citation.title() + "》" : citation.section();
                if (section.startsWith("第 ") && section.contains("页")) section = "《" + citation.title() + "》" + section;
                int sentencesToTake = groups.isEmpty() ? 3 : 2;
                List<String> sentences = textTools.extractRelevantSentences(
                        citation.text(), retrieval == null ? question : retrieval.query(), tokenizer, sentencesToTake
                );
                if (sentences.isEmpty()) sentences = List.of(citation.snippet());
                List<String> unique = new ArrayList<>();
                for (String sentence : sentences) {
                    String normalized = sentence.replaceAll("\\s+", "");
                    if (normalized.length() < 4 || !seen.add(normalized)) continue;
                    unique.add(sentence.matches(".*[。！？!?；;：:]$") ? sentence : sentence + "。");
                }
                if (unique.isEmpty()) continue;
                String key = citation.documentId() + ":" + section;
                CitationGroup group = groups.stream().filter(item -> item.key.equals(key)).findFirst().orElse(null);
                if (group == null) {
                    group = new CitationGroup(key, section);
                    groups.add(group);
                }
                group.entries.add(new CitationEntry(citation.index(), unique));
            }
            for (CitationGroup group : groups.stream().limit(4).toList()) {
                lines.add("**" + group.label + "**");
                for (CitationEntry entry : group.entries) {
                    for (String sentence : entry.sentences) {
                        lines.add("- " + sentence + " [" + entry.index + "]");
                    }
                }
                lines.add("");
            }
            if (groups.isEmpty()) {
                lines.add(citations.getFirst().snippet() + " [" + citations.getFirst().index() + "]");
                lines.add("");
            }
        }

        if (webResponse != null && webResponse.available()) {
            lines.add("### 联网补充信息\n");
            lines.add(webResponse.summary());
            int index = 1;
            for (WebSearchTool.SearchResult result : webResponse.results().stream().limit(3).toList()) {
                lines.add(index++ + ". [" + result.title() + "](" + result.url() + ")");
            }
            lines.add("");
        }

        if (!citations.isEmpty()) {
            double confidence = retrieval == null ? 0 : retrieval.confidence();
            if (confidence < 0.2) {
                lines.add("> 提示：本次检索的最高相关度为 " + String.format(Locale.ROOT, "%.2f", confidence)
                        + "，证据匹配度偏低。建议核对原文，或补充更具体的文档后再提问。");
                lines.add("");
            }
            lines.add("以上内容基于 **" + citations.size() + "** 个知识库片段整理，可在右侧「证据」面板核对原文。");
        } else {
            lines.add("以上为经营数据库实时聚合结果；当前问题未命中可直接引用的知识库片段。");
        }
        return String.join("\n", lines).strip();
    }

    private void streamDemoText(String text, boolean streaming, List<String> answerParts, EventSink sink) throws IOException {
        int step = 7;
        for (int i = 0; i < text.length(); i += step) {
            String chunk = text.substring(i, Math.min(text.length(), i + step));
            answerParts.add(chunk);
            sink.emit("token", mapOf("text", chunk));
            if (streaming && properties.demoStreamDelayMs() > 0) sleep(properties.demoStreamDelayMs());
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    private static void emitDone(EventSink sink, String messageId, String sessionId, int latency,
                                 String model, String mode, List<String> suggestions) throws IOException {
        sink.emit("done", mapOf(
                "message_id", messageId,
                "session_id", sessionId,
                "latency_ms", latency,
                "model", model,
                "mode", mode,
                "suggestions", suggestions
        ));
    }

    private List<String> suggestions(RetrievalService.RetrievalResult retrieval,
                                     DatabaseTool.DatabaseResult databaseResult) {
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        if (databaseResult != null) {
            suggestions.add("按渠道拆分 2026Q3 销售额");
            suggestions.add("华东区相比上一季度增长了多少？");
        }
        if (retrieval != null && !retrieval.citations().isEmpty()) {
            String section = retrieval.citations().getFirst().section();
            if (section != null && !section.isBlank() && !"正文".equals(section) && !"第 1 页".equals(section)) {
                suggestions.add("把「" + section + "」整理成执行清单");
            }
            suggestions.add("这些依据分别来自哪些文档？");
        }
        suggestions.add("如果制度文件更新了，知识库怎么同步？");
        return suggestions.stream().limit(3).toList();
    }

    private static String smalltalkAnswer() {
        return """
                你好，我是**灵犀**，你的企业知识库智能助手。

                我可以帮你：
                - 查询公司制度、产品手册和经营报告，并在回答中标注来源；
                - 调用经营数据库，查询区域销售、订单和环比数据；
                - 结合多轮上下文持续追问，也可以读取你上传的 PDF、Word 和 Markdown 文档。

                试试问我：**“员工年假怎么休？”** 或 **“2026Q3 各区域销售额怎么样？”**
                """;
    }

    private static int elapsed(long started) {
        return (int) (System.currentTimeMillis() - started);
    }

    public static Map<String, Object> mapOf(Object... keysAndValues) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }

    private record ToolPlan(String name, String reason) {
    }

    private static class CitationGroup {
        private final String key;
        private final String label;
        private final List<CitationEntry> entries = new ArrayList<>();

        private CitationGroup(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private record CitationEntry(int index, List<String> sentences) {
    }

    @FunctionalInterface
    public interface EventSink {
        void emit(String name, Object data) throws IOException;
    }
}
