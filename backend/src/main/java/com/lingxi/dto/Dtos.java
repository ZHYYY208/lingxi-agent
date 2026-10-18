package com.lingxi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Dtos {
    private Dtos() {
    }

    public record Citation(
            int index,
            String chunkId,
            String documentId,
            String title,
            String filename,
            String section,
            Integer page,
            String text,
            String snippet,
            double score,
            double vectorScore,
            double bm25Score,
            int chunkIndex
    ) {
    }

    public record ToolTrace(
            String id,
            String name,
            String label,
            String status,
            String input,
            String output,
            int durationMs,
            Map<String, Object> meta
    ) {
    }

    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 64) String sessionId,
            Boolean webSearch
    ) {
        public boolean useWeb() {
            return Boolean.TRUE.equals(webSearch);
        }
    }

    public record ChatResult(
            String sessionId,
            String messageId,
            String answer,
            List<Citation> citations,
            List<ToolTrace> tools,
            long latencyMs,
            String model,
            String mode
    ) {
    }

    public record MessageDto(
            String id,
            String role,
            String content,
            List<Citation> citations,
            List<ToolTrace> tools,
            int latencyMs,
            Instant createdAt
    ) {
    }

    public record SessionSummary(
            String id,
            String title,
            long messageCount,
            String preview,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record SessionDetail(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<MessageDto> messages
    ) {
    }

    public record DocumentDto(
            String id,
            String title,
            String originalName,
            String extension,
            String mimeType,
            long sizeBytes,
            String status,
            String error,
            int chunkCount,
            long charCount,
            int pageCount,
            @JsonProperty("is_demo") boolean demo,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RejectedFile(String filename, String reason) {
    }

    public record UploadResponse(List<DocumentDto> documents, List<RejectedFile> rejected) {
    }

    public record SeedResponse(int seeded, String message, List<DocumentDto> documents) {
    }

    public record Stats(
            long documentCount,
            long readyDocumentCount,
            long chunkCount,
            long charCount,
            long sessionCount,
            long messageCount,
            Instant lastIndexedAt,
            double indexCoverage
    ) {
    }

    public record Limits(
            int maxUploadMb,
            int chatRateLimitPerMinute,
            int retrievalTopK,
            int chunkSize,
            int chunkOverlap,
            long messagesIndexed
    ) {
    }

    public record ToolInfo(String name, String label, String description, boolean available, Map<String, Object> meta) {
    }

    public record SystemInfo(
            String name,
            String version,
            String mode,
            String model,
            String embedding,
            String vectorStore,
            String sessionStore,
            String database,
            boolean webSearchEnabled,
            boolean authEnabled,
            List<ToolInfo> tools,
            Limits limits
    ) {
    }

    public record Health(
            String status,
            String version,
            String database,
            String redis,
            String vectorStore,
            String llm,
            Instant timestamp
    ) {
    }

    public record RenameSessionRequest(@NotBlank @Size(max = 120) String title) {
    }

    public record FeedbackRequest(
            @NotBlank String messageId,
            @NotNull @Min(-1) @Max(1) Integer rating,
            @Size(max = 2000) String comment
    ) {
    }

    public record FeedbackResponse(boolean saved, String feedbackId) {
    }

    public record SimpleResponse(boolean deleted, String id) {
    }

    public record ChunkDto(String id, int ordinal, String text, String section, Integer page, int charCount) {
    }

    public record ChunksResponse(DocumentDto document, List<ChunkDto> chunks, int count) {
    }

    public record ErrorResponse(String detail, String code, String requestId) {
    }

    public record AgentEvent(String name, Object data) {
    }

    public record AdminLoginRequest(@NotBlank String password) {
    }

    public record AdminLoginResponse(String token, Instant expiresAt) {
    }

    public record AdminSessionResponse(boolean authenticated, Instant expiresAt) {
    }

    public record ModelConfigView(
            String provider,
            String baseUrl,
            String model,
            boolean apiKeySet,
            String apiKeyMasked,
            double temperature,
            int maxTokens,
            Instant updatedAt,
            String mode,
            String modelLabel
    ) {
    }

    public record ModelConfigUpdateRequest(
            @NotBlank String provider,
            String baseUrl,
            String model,
            String apiKey,
            Boolean clearApiKey,
            Double temperature,
            Integer maxTokens
    ) {
    }

    public record ModelTestRequest(
            String provider,
            String baseUrl,
            String model,
            String apiKey
    ) {
    }

    public record ModelTestResponse(
            boolean available,
            long latencyMs,
            String message,
            List<String> models
    ) {
    }

    public record AdminOverview(
            Stats stats,
            ModelConfigView model,
            Health health,
            List<DocumentDto> recentDocuments,
            long salesOrders
    ) {
    }
}
