package com.lingxi.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.config.LingxiProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EmbeddingService {
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
    private final LingxiProperties properties;
    private final ChineseTokenizer tokenizer;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public EmbeddingService(LingxiProperties properties, ChineseTokenizer tokenizer, ObjectMapper mapper) {
        this.properties = properties;
        this.tokenizer = tokenizer;
        this.mapper = mapper;
    }

    public String name() {
        return "openai".equalsIgnoreCase(properties.embeddingProvider())
                ? "OpenAI-compatible Embedding"
                : "本地哈希 N-gram";
    }

    public List<double[]> embedAll(List<String> texts) {
        if ("openai".equalsIgnoreCase(properties.embeddingProvider())) {
            return embedOpenAi(texts);
        }
        List<double[]> vectors = new ArrayList<>();
        for (String text : texts) vectors.add(embedLocal(text));
        return vectors;
    }

    public double[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    public double[] embedLocal(String text) {
        int dimension = Math.max(64, properties.embeddingDim());
        Map<String, Double> features = new LinkedHashMap<>();
        for (String token : tokenizer.tokenize(text)) {
            features.merge(token, 1.4, Double::sum);
        }
        String compact = TextTools.normalize(text).replaceAll("\\s+", "");
        for (int n = 2; n <= 4; n++) {
            for (int i = 0; i <= compact.length() - n; i++) {
                String gram = compact.substring(i, i + n);
                if (!gram.isBlank()) features.merge("g:" + gram, 0.35, Double::sum);
            }
        }
        double[] vector = new double[dimension];
        for (Map.Entry<String, Double> entry : features.entrySet()) {
            byte[] digest = sha256(entry.getKey());
            long raw = 0;
            for (int i = 0; i < 8; i++) raw = (raw << 8) | (digest[i] & 0xffL);
            int index = (int) Math.floorMod(raw, dimension);
            double sign = ((raw >> 16) & 1) == 1 ? 1.0 : -1.0;
            vector[index] += sign * Math.log1p(entry.getValue());
        }
        double norm = 0;
        for (double value : vector) norm += value * value;
        if (norm > 0) {
            double length = Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= length;
        }
        return vector;
    }

    private List<double[]> embedOpenAi(List<String> texts) {
        if (properties.embeddingApiKey() == null || properties.embeddingApiKey().isBlank()) {
            throw new IllegalStateException("EMBEDDING_PROVIDER=openai 时需要配置 EMBEDDING_API_KEY");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", properties.embeddingModel());
            payload.put("input", texts);
            if (properties.embeddingModel() != null && properties.embeddingModel().contains("text-embedding-3")) {
                payload.put("dimensions", properties.embeddingDim());
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(properties.embeddingBaseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + properties.embeddingApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Embedding 请求失败: " + response.statusCode());
            }
            com.fasterxml.jackson.databind.JsonNode dataNode = mapper.readTree(response.body()).path("data");
            List<com.fasterxml.jackson.databind.JsonNode> items = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : dataNode) items.add(item);
            items.sort(Comparator.comparingInt(item -> item.path("index").asInt(0)));
            List<double[]> result = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : items) {
                com.fasterxml.jackson.databind.JsonNode raw = item.path("embedding");
                double[] vector = new double[raw.size()];
                for (int i = 0; i < raw.size(); i++) vector[i] = raw.get(i).asDouble();
                if (vector.length != properties.embeddingDim()) {
                    throw new IllegalStateException("Embedding 维度不匹配: " + vector.length + " != " + properties.embeddingDim());
                }
                result.add(vector);
            }
            return result;
        } catch (Exception exc) {
            throw new IllegalStateException("Embedding 请求失败: " + exc.getMessage(), exc);
        }
    }

    private static String stripSlash(String value) {
        if (value == null) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exc) {
            throw new IllegalStateException(exc);
        }
    }

    public String serialize(double[] vector) {
        try {
            double[] rounded = new double[vector.length];
            for (int i = 0; i < vector.length; i++) rounded[i] = Math.round(vector[i] * 1_000_000d) / 1_000_000d;
            return mapper.writeValueAsString(rounded);
        } catch (Exception exc) {
            throw new IllegalStateException("无法序列化向量", exc);
        }
    }

    public double[] deserialize(String json) {
        try {
            return mapper.readValue(json, double[].class);
        } catch (Exception exc) {
            return new double[properties.embeddingDim()];
        }
    }

    public double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) return 0;
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            a += left[i] * left[i];
            b += right[i] * right[i];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }
}
