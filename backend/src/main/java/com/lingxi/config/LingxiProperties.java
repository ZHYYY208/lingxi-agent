package com.lingxi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "lingxi")
public record LingxiProperties(
        String appName,
        String appVersion,
        String appEnv,
        String dataDir,
        String apiKey,
        String adminPassword,
        long adminTokenTtlSeconds,
        String secretKey,
        int maxUploadMb,
        int chunkSize,
        int chunkOverlap,
        int retrievalTopK,
        int retrievalCandidates,
        double minRetrievalScore,
        int rateLimitPerMinute,
        int chatRateLimitPerMinute,
        boolean demoSeed,
        long demoStreamDelayMs,
        int embeddingDim,
        String embeddingProvider,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel,
        double llmTemperature,
        int llmMaxTokens,
        int llmTimeoutSeconds,
        boolean webSearchEnabled,
        String tavilyApiKey,
        int webSearchTimeoutSeconds,
        boolean redisEnabled,
        long sessionCacheTtlSeconds,
        String milvusUri,
        String milvusToken,
        String milvusCollection,
        List<String> corsOrigins
) {
    public Path dataPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    public Path uploadsPath() {
        return dataPath().resolve("uploads");
    }

    public long maxUploadBytes() {
        return (long) maxUploadMb * 1024 * 1024;
    }

    public boolean authEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean llmConfigured() {
        return llmApiKey != null && !llmApiKey.isBlank();
    }

    public List<String> safeCorsOrigins() {
        return corsOrigins == null ? List.of() : corsOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }
}
