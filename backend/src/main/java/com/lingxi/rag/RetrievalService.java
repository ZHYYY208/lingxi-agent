package com.lingxi.rag;

import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ChunkEntity;
import com.lingxi.model.DocumentEntity;
import com.lingxi.repository.ChunkRepository;
import com.lingxi.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class RetrievalService {
    private static final Set<String> ANCHOR_STOPWORDS = Set.of(
            "员工", "公司", "企业", "我们", "你们", "他们", "什么", "怎么", "如何", "哪些",
            "哪个", "哪里", "可以", "需要", "是否", "请问", "一下", "这个", "那个", "以及",
            "还有", "相关", "具体", "进行", "使用", "提前", "多久", "多少", "为什么", "告诉",
            "帮我", "看看", "介绍", "一个", "目前", "现在", "时候", "问题", "情况", "申请",
            "流程", "步骤", "办理", "提交", "填写", "说明", "要求", "规定", "表现", "限制",
            "内容", "功能", "方式", "方法", "部分", "信息", "工作"
    );
    private static final Pattern ANCHOR_SPLIT = Pattern.compile(
            "[，。！？!?；;、,\\s]|" + String.join("|", ANCHOR_STOPWORDS.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .toList())
    );

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ChineseTokenizer tokenizer;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final TextTools textTools;
    private final LingxiProperties properties;

    public RetrievalService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            ChineseTokenizer tokenizer,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            TextTools textTools,
            LingxiProperties properties
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.tokenizer = tokenizer;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.textTools = textTools;
        this.properties = properties;
    }

    public RetrievalResult search(String question, List<Dtos.MessageDto> history) {
        long started = System.currentTimeMillis();
        String query = buildQuery(question, history);
        List<ChunkData> chunks = loadChunks();
        if (chunks.isEmpty()) {
            return new RetrievalResult(List.of(), query, 0, 0, 0, Map.of("reason", "no_chunks"));
        }

        Map<String, ChunkData> byId = new LinkedHashMap<>();
        for (ChunkData chunk : chunks) byId.put(chunk.id, chunk);
        BM25 bm25 = new BM25(chunks.stream()
                .map(chunk -> new Bm25Document(chunk.id, chunk.title + " " + chunk.section + " " + chunk.text))
                .toList(), tokenizer);
        List<Score> bm25Hits = bm25.search(tokenizer.tokenize(query), properties.retrievalCandidates());
        double[] queryVector = embeddingService.embed(query);
        List<VectorStore.VectorHit> vectorHits = vectorStore.search(queryVector, properties.retrievalCandidates()).stream()
                .filter(hit -> byId.containsKey(hit.chunkId()))
                .toList();

        Map<String, Integer> bm25Rank = new LinkedHashMap<>();
        Map<String, Integer> vectorRank = new LinkedHashMap<>();
        Map<String, Double> bm25Score = new LinkedHashMap<>();
        Map<String, Double> vectorScore = new LinkedHashMap<>();
        for (int i = 0; i < bm25Hits.size(); i++) {
            bm25Rank.put(bm25Hits.get(i).id, i + 1);
            bm25Score.put(bm25Hits.get(i).id, bm25Hits.get(i).score);
        }
        for (int i = 0; i < vectorHits.size(); i++) {
            vectorRank.put(vectorHits.get(i).chunkId(), i + 1);
            vectorScore.put(vectorHits.get(i).chunkId(), vectorHits.get(i).score());
        }
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>(bm25Rank.keySet());
        candidateIds.addAll(vectorRank.keySet());
        if (candidateIds.isEmpty()) {
            return new RetrievalResult(List.of(), query, 0, elapsed(started), 0, Map.of("reason", "no_candidates"));
        }

        double maxBm25 = bm25Score.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        Set<String> queryTerms = tokenizer.tokenSet(query);
        String normalizedQuery = textTools.normalize(query);
        List<FinalScore> fused = new ArrayList<>();
        for (String chunkId : candidateIds) {
            ChunkData chunk = byId.get(chunkId);
            double rrf = 0;
            if (bm25Rank.containsKey(chunkId)) rrf += 1.0 / (60 + bm25Rank.get(chunkId));
            if (vectorRank.containsKey(chunkId)) rrf += 1.0 / (60 + vectorRank.get(chunkId));
            Set<String> chunkTerms = tokenizer.tokenSet(chunk.section + " " + chunk.text);
            Set<String> overlapTerms = new HashSet<>(queryTerms);
            overlapTerms.retainAll(chunkTerms);
            double overlap = overlapTerms.size() / (double) Math.max(1, queryTerms.size());
            Set<String> headingTerms = tokenizer.tokenSet(chunk.title + " " + chunk.section);
            Set<String> headingOverlapTerms = new HashSet<>(queryTerms);
            headingOverlapTerms.retainAll(headingTerms);
            double headingOverlap = headingOverlapTerms.size() / (double) Math.max(1, queryTerms.size());
            double phraseBonus = normalizedQuery.length() >= 3 && textTools.normalize(chunk.text).contains(normalizedQuery) ? 0.08 : 0;
            double score = 0.40 * Math.max(0, vectorScore.getOrDefault(chunkId, 0.0))
                    + 0.30 * (bm25Score.getOrDefault(chunkId, 0.0) / maxBm25)
                    + 0.18 * overlap
                    + 0.12 * headingOverlap
                    + phraseBonus;
            fused.add(new FinalScore(chunkId, score));
        }
        fused.sort(Comparator.comparingDouble(FinalScore::score).reversed());

        List<String> anchors = anchorTerms(query, bm25);
        if (anchors.isEmpty()) {
            return new RetrievalResult(List.of(), query, candidateIds.size(), elapsed(started), 0,
                    Map.of("reason", "no_anchor_terms", "corpus_chunks", chunks.size()));
        }
        String topAnchor = anchors.getFirst();
        Set<String> anchorSet = new HashSet<>(anchors);
        List<FinalScore> anchorFiltered = new ArrayList<>();
        List<FinalScore> headingFiltered = new ArrayList<>();
        for (FinalScore candidate : fused) {
            ChunkData chunk = byId.get(candidate.chunkId);
            Set<String> chunkTerms = tokenizer.tokenSet(chunk.title + " " + chunk.section + " " + chunk.text);
            if (!chunkTerms.contains(topAnchor)) continue;
            anchorFiltered.add(candidate);
            Set<String> headingTerms = tokenizer.tokenSet(chunk.title + " " + chunk.section);
            if (headingTerms.stream().anyMatch(anchorSet::contains)) headingFiltered.add(candidate);
        }
        fused = headingFiltered.isEmpty() ? anchorFiltered : headingFiltered;

        if (!fused.isEmpty()) {
            double relativeCutoff = Math.max(properties.minRetrievalScore(), fused.getFirst().score * 0.38);
            fused = fused.stream().filter(item -> item.score >= relativeCutoff).toList();
        }

        List<FinalScore> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        for (FinalScore candidate : fused) {
            ChunkData chunk = byId.get(candidate.chunkId);
            if (perDocument.getOrDefault(chunk.documentId, 0) >= 2) continue;
            if (candidate.score < properties.minRetrievalScore() && !selected.isEmpty()) continue;
            selected.add(candidate);
            perDocument.merge(chunk.documentId, 1, Integer::sum);
            if (selected.size() >= properties.retrievalTopK()) break;
        }

        List<Dtos.Citation> citations = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            FinalScore selectedScore = selected.get(i);
            ChunkData chunk = byId.get(selectedScore.chunkId);
            citations.add(new Dtos.Citation(
                    i + 1, chunk.id, chunk.documentId, chunk.title, chunk.filename, chunk.section, chunk.page,
                    chunk.text, textTools.snippet(chunk.text, query, 260), round(selectedScore.score),
                    round(vectorScore.getOrDefault(chunk.id, 0.0)), round(bm25Score.getOrDefault(chunk.id, 0.0)),
                    chunk.ordinal
            ));
        }
        double confidence = citations.isEmpty() ? 0 : citations.getFirst().score();
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("bm25_candidates", bm25Hits.size());
        diagnostic.put("vector_candidates", vectorHits.size());
        diagnostic.put("corpus_chunks", chunks.size());
        diagnostic.put("anchors", anchors);
        return new RetrievalResult(citations, query, candidateIds.size(), elapsed(started), confidence, diagnostic);
    }

    public String buildQuery(String question, List<Dtos.MessageDto> history) {
        String query = question == null ? "" : question.strip();
        if (query.length() > 42 || history == null || history.isEmpty()) return query;
        String previousUser = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                previousUser = history.get(i).content();
                break;
            }
        }
        if (previousUser == null || previousUser.equals(question)) return query;
        List<String> markers = List.of("这个", "那个", "它", "上述", "刚才", "然后", "那", "还", "再", "呢", "具体");
        boolean contextual = query.length() < 18 || markers.stream().anyMatch(query::contains);
        return contextual ? previousUser + " " + query : query;
    }

    private List<String> anchorTerms(String query, BM25 bm25) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        // Domain terms are treated as anchors even when Jieba would split a
        // phrase such as "年假有几天" into single characters.
        for (String domainWord : tokenizer.domainWords()) {
            if (query.contains(domainWord) && bm25.idf.getOrDefault(domainWord, 0.0) > 0) {
                candidates.add(domainWord);
            }
        }
        for (String segment : ANCHOR_SPLIT.split(query)) {
            if (segment.isBlank()) continue;
            for (String token : tokenizer.tokenizeWords(segment)) {
                if (token.length() < 2 || token.length() > 4) continue;
                if (ANCHOR_STOPWORDS.contains(token) || token.matches("\\d+")) continue;
                if (bm25.idf.getOrDefault(token, 0.0) <= 0) continue;
                candidates.add(token);
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble((String token) ->
                        bm25.idf.getOrDefault(token, 0.0) * (1.0 + 0.12 * (token.length() - 2))).reversed())
                .limit(2)
                .toList();
    }

    private List<ChunkData> loadChunks() {
        List<ChunkData> result = new ArrayList<>();
        for (DocumentEntity document : documentRepository.findByStatus("ready")) {
            for (ChunkEntity chunk : chunkRepository.findByDocumentIdOrderByOrdinal(document.getId())) {
                result.add(new ChunkData(
                        chunk.getId(), document.getId(), document.getTitle(), document.getOriginalName(),
                        chunk.getOrdinal(), chunk.getText(), chunk.getSection(), chunk.getPage()
                ));
            }
        }
        return result;
    }

    private static long elapsed(long started) {
        return System.currentTimeMillis() - started;
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    public record RetrievalResult(
            List<Dtos.Citation> citations,
            String query,
            int candidates,
            long latencyMs,
            double confidence,
            Map<String, Object> diagnostic
    ) {
        public String context() {
            if (citations.isEmpty()) return "（知识库中没有检索到直接相关的片段）";
            StringBuilder builder = new StringBuilder();
            for (Dtos.Citation citation : citations) {
                String location = citation.section() == null || citation.section().isBlank()
                        ? (citation.page() == null ? "" : "第 " + citation.page() + " 页")
                        : citation.section();
                builder.append("[").append(citation.index()).append("] 文档《").append(citation.title()).append("》")
                        .append(location.isBlank() ? "" : " · " + location).append("\n")
                        .append(citation.text()).append("\n\n");
            }
            return builder.toString().strip();
        }
    }

    private record ChunkData(
            String id, String documentId, String title, String filename, int ordinal,
            String text, String section, Integer page
    ) {
    }

    private record FinalScore(String chunkId, double score) {
    }

    private record Score(String id, double score) {
    }

    private record Bm25Document(String id, String text) {
    }

    private static class BM25 {
        private final List<String> ids = new ArrayList<>();
        private final List<Map<String, Integer>> frequencies = new ArrayList<>();
        private final List<Integer> lengths = new ArrayList<>();
        private final Map<String, Double> idf = new HashMap<>();
        private final double averageLength;

        BM25(List<Bm25Document> documents, ChineseTokenizer tokenizer) {
            Map<String, Integer> documentFrequency = new HashMap<>();
            for (Bm25Document document : documents) {
                List<String> tokens = tokenizer.tokenize(document.text);
                Map<String, Integer> counts = new HashMap<>();
                for (String token : tokens) counts.merge(token, 1, Integer::sum);
                ids.add(document.id);
                frequencies.add(counts);
                lengths.add(tokens.size());
                for (String token : counts.keySet()) documentFrequency.merge(token, 1, Integer::sum);
            }
            averageLength = lengths.stream().mapToInt(Integer::intValue).average().orElse(0);
            int total = documents.size();
            for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
                idf.put(entry.getKey(), Math.log(1 + (total - entry.getValue() + 0.5) / (entry.getValue() + 0.5)));
            }
        }

        List<Score> search(List<String> queryTerms, int limit) {
            List<Score> scores = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                double score = 0;
                int length = Math.max(1, lengths.get(i));
                for (String term : queryTerms) {
                    int frequency = frequencies.get(i).getOrDefault(term, 0);
                    if (frequency == 0) continue;
                    double termIdf = idf.getOrDefault(term, 0.0);
                    score += termIdf * (frequency * 2.5) / (frequency + 1.5 * (1 - 0.75 + 0.75 * length / Math.max(1, averageLength)));
                }
                if (score > 0) scores.add(new Score(ids.get(i), score));
            }
            scores.sort(Comparator.comparingDouble(Score::score).reversed());
            return scores.subList(0, Math.min(limit, scores.size()));
        }
    }
}
