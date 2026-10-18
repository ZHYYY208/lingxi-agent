package com.lingxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ModelSettingsEntity;
import com.lingxi.repository.ModelSettingsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ModelConfigService {
    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);
    private static final String DEMO = "demo";
    private static final String OPENAI = "openai";
    private static final String OLLAMA = "ollama";

    private final ModelSettingsRepository repository;
    private final SecretCipher cipher;
    private final LingxiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public ModelConfigService(ModelSettingsRepository repository, SecretCipher cipher,
                              LingxiProperties properties, ObjectMapper mapper) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.mapper = mapper;
    }

    @PostConstruct
    void initialize() {
        if (repository.existsById(1L)) return;
        ModelSettingsEntity entity = new ModelSettingsEntity();
        entity.setProvider(properties.llmConfigured() ? OPENAI : DEMO);
        entity.setBaseUrl(properties.llmBaseUrl() == null ? "" : properties.llmBaseUrl());
        entity.setModel(properties.llmModel() == null ? "" : properties.llmModel());
        entity.setApiKeyEncrypted(cipher.encrypt(properties.llmApiKey()));
        entity.setTemperature(properties.llmTemperature());
        entity.setMaxTokens(properties.llmMaxTokens());
        repository.save(entity);
        log.info("Initialized model settings from environment: provider={}", entity.getProvider());
    }

    public RuntimeConfig runtime() {
        ModelSettingsEntity entity = repository.findById(1L).orElseGet(this::defaults);
        String apiKey = cipher.decrypt(entity.getApiKeyEncrypted());
        boolean configured = isConfigured(entity.getProvider(), entity.getBaseUrl(), entity.getModel(), apiKey);
        return new RuntimeConfig(
                normalizeProvider(entity.getProvider()),
                entity.getBaseUrl() == null ? "" : entity.getBaseUrl(),
                entity.getModel() == null ? "" : entity.getModel(),
                apiKey,
                entity.getTemperature(),
                entity.getMaxTokens(),
                configured
        );
    }

    public Dtos.ModelConfigView view() {
        ModelSettingsEntity entity = repository.findById(1L).orElseGet(this::defaults);
        String apiKey = cipher.decrypt(entity.getApiKeyEncrypted());
        boolean configured = isConfigured(entity.getProvider(), entity.getBaseUrl(), entity.getModel(), apiKey);
        return new Dtos.ModelConfigView(
                normalizeProvider(entity.getProvider()),
                entity.getBaseUrl() == null ? "" : entity.getBaseUrl(),
                entity.getModel() == null ? "" : entity.getModel(),
                apiKey != null && !apiKey.isBlank(),
                SecretCipher.mask(apiKey),
                entity.getTemperature(),
                entity.getMaxTokens(),
                entity.getUpdatedAt(),
                configured ? "llm" : "demo",
                configured ? entity.getModel() : "灵犀本地证据引擎"
        );
    }

    public Dtos.ModelConfigView save(Dtos.ModelConfigUpdateRequest request) {
        String provider = normalizeProvider(request.provider());
        if (!List.of(DEMO, OPENAI, OLLAMA).contains(provider)) {
            throw new IllegalArgumentException("不支持的模型接入方式");
        }
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().strip();
        String model = request.model() == null ? "" : request.model().strip();
        if (!DEMO.equals(provider) && (baseUrl.isBlank() || model.isBlank())) {
            throw new IllegalArgumentException("API 地址和模型名称不能为空");
        }
        if (OPENAI.equals(provider) && (request.apiKey() == null || request.apiKey().isBlank())) {
            ModelSettingsEntity existing = repository.findById(1L).orElse(null);
            String existingKey = existing == null ? null : cipher.decrypt(existing.getApiKeyEncrypted());
            if (Boolean.TRUE.equals(request.clearApiKey())
                    || existingKey == null || existingKey.isBlank()) {
                throw new IllegalArgumentException("第三方 API 模式需要配置 API Key");
            }
        }
        ModelSettingsEntity entity = repository.findById(1L).orElseGet(() -> {
            ModelSettingsEntity created = new ModelSettingsEntity();
            created.setId(1L);
            return created;
        });
        entity.setProvider(provider);
        entity.setBaseUrl(stripSlash(baseUrl));
        entity.setModel(model);
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            entity.setApiKeyEncrypted(null);
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKeyEncrypted(cipher.encrypt(request.apiKey().strip()));
        }
        if (request.temperature() != null) {
            entity.setTemperature(Math.max(0, Math.min(1.5, request.temperature())));
        }
        if (request.maxTokens() != null) {
            entity.setMaxTokens(Math.max(64, Math.min(8192, request.maxTokens())));
        }
        ModelSettingsEntity saved = repository.save(entity);
        log.info("Model settings updated: provider={} model={}", saved.getProvider(), saved.getModel());
        return view();
    }

    public Dtos.ModelTestResponse test(Dtos.ModelTestRequest request) {
        RuntimeConfig current = runtime();
        String provider = normalizeProvider(request.provider() == null || request.provider().isBlank()
                ? current.provider() : request.provider());
        if (DEMO.equals(provider)) {
            return new Dtos.ModelTestResponse(false, 0,
                    "当前为本地证据模式，未启用外部模型。", List.of());
        }
        String baseUrl = stripSlash(request.baseUrl() == null || request.baseUrl().isBlank()
                ? current.baseUrl() : request.baseUrl().strip());
        String model = request.model() == null || request.model().isBlank() ? current.model() : request.model().strip();
        String apiKey = request.apiKey() != null && !request.apiKey().isBlank()
                ? request.apiKey().strip() : current.apiKey();
        if (baseUrl.isBlank() || model.isBlank()) {
            return new Dtos.ModelTestResponse(false, 0, "API 地址和模型名称不能为空", List.of());
        }
        long started = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - started;
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return new Dtos.ModelTestResponse(false, latency, "认证失败，请检查 API Key", List.of());
            }
            if (response.statusCode() >= 400) {
                return new Dtos.ModelTestResponse(false, latency,
                        "连接失败，HTTP " + response.statusCode(), List.of());
            }
            List<String> models = new ArrayList<>();
            JsonNode root = mapper.readTree(response.body());
            for (JsonNode item : root.path("data")) {
                if (item.has("id")) models.add(item.path("id").asText());
            }
            for (JsonNode item : root.path("models")) {
                if (item.has("name")) models.add(item.path("name").asText());
            }
            return new Dtos.ModelTestResponse(true, latency,
                    models.isEmpty() ? "连接成功" : "连接成功，发现 " + models.size() + " 个模型",
                    models.stream().limit(30).toList());
        } catch (Exception exc) {
            return new Dtos.ModelTestResponse(false, System.currentTimeMillis() - started,
                    "连接失败：" + exc.getMessage(), List.of());
        }
    }

    private ModelSettingsEntity defaults() {
        ModelSettingsEntity entity = new ModelSettingsEntity();
        entity.setProvider(DEMO);
        entity.setBaseUrl("");
        entity.setModel("");
        entity.setTemperature(0.2);
        entity.setMaxTokens(1400);
        return entity;
    }

    private static boolean isConfigured(String provider, String baseUrl, String model, String apiKey) {
        String normalized = normalizeProvider(provider);
        if (DEMO.equals(normalized)) return false;
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) return false;
        if (OPENAI.equals(normalized)) return apiKey != null && !apiKey.isBlank();
        return true;
    }

    public static String normalizeProvider(String provider) {
        if (provider == null) return DEMO;
        return switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "openai", "third_party", "third-party", "api" -> OPENAI;
            case "ollama", "local", "local_llm", "lmstudio" -> OLLAMA;
            case "demo", "disabled", "none" -> DEMO;
            default -> provider.strip().toLowerCase(Locale.ROOT);
        };
    }

    private static String stripSlash(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        while (stripped.endsWith("/")) stripped = stripped.substring(0, stripped.length() - 1);
        return stripped;
    }

    public record RuntimeConfig(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            double temperature,
            int maxTokens,
            boolean configured
    ) {
    }
}
