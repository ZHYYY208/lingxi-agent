package com.lingxi.web;

import com.lingxi.config.LingxiProperties;
import com.lingxi.dto.Dtos;
import com.lingxi.rag.EmbeddingService;
import com.lingxi.rag.VectorStore;
import com.lingxi.repository.SalesOrderRepository;
import com.lingxi.service.KnowledgeService;
import com.lingxi.service.LlmClient;
import com.lingxi.service.SessionService;
import com.lingxi.service.WebSearchTool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SystemController {
    private final LingxiProperties properties;
    private final KnowledgeService knowledgeService;
    private final SessionService sessionService;
    private final LlmClient llmClient;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final WebSearchTool webSearchTool;
    private final SalesOrderRepository salesOrderRepository;
    private final DataSource dataSource;

    public SystemController(
            LingxiProperties properties,
            KnowledgeService knowledgeService,
            SessionService sessionService,
            LlmClient llmClient,
            EmbeddingService embeddingService,
            VectorStore vectorStore,
            WebSearchTool webSearchTool,
            SalesOrderRepository salesOrderRepository,
            DataSource dataSource
    ) {
        this.properties = properties;
        this.knowledgeService = knowledgeService;
        this.sessionService = sessionService;
        this.llmClient = llmClient;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.webSearchTool = webSearchTool;
        this.salesOrderRepository = salesOrderRepository;
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Dtos.Health health() {
        boolean databaseOk = databaseOk();
        String redis = !properties.redisEnabled() ? "disabled"
                : (sessionService.redisHealthy() ? "ok" : "fallback");
        boolean vectorOk = vectorStore.healthy();
        return new Dtos.Health(
                databaseOk && vectorOk ? "ok" : "degraded",
                properties.appVersion(),
                databaseOk ? "ok" : "error",
                redis,
                vectorOk ? "ok" : "error",
                llmClient.configured() ? "configured" : "demo",
                Instant.now()
        );
    }

    @GetMapping("/info")
    public Dtos.SystemInfo info() {
        Dtos.Stats stats = knowledgeService.stats();
        boolean salesAvailable = true;
        List<Dtos.ToolInfo> tools = List.of(
                new Dtos.ToolInfo("knowledge_search", "知识库混合检索",
                        "向量召回 + BM25 + RRF 融合 + Rerank", true,
                        Map.of("top_k", properties.retrievalTopK(), "chunk_size", properties.chunkSize(),
                                "overlap", properties.chunkOverlap())),
                new Dtos.ToolInfo("database_query", "经营数据库查询",
                        "只读语义查询，已载入 " + salesOrderRepository.count() + " 条销售订单",
                        salesAvailable,
                        Map.of("table", "sales_orders", "row_count", salesOrderRepository.count())),
                new Dtos.ToolInfo("web_search", "联网搜索",
                        "Tavily / DuckDuckGo，补充实时外部信息", webSearchTool.enabled(),
                        Map.of("provider", properties.tavilyApiKey() == null || properties.tavilyApiKey().isBlank()
                                ? "DuckDuckGo" : "Tavily"))
        );
        return new Dtos.SystemInfo(
                properties.appName(),
                properties.appVersion(),
                llmClient.mode(),
                llmClient.modelLabel(),
                embeddingService.name(),
                vectorStore.name(),
                sessionService.storeName(),
                databaseLabel(),
                webSearchTool.enabled(),
                properties.authEnabled(),
                tools,
                new Dtos.Limits(properties.maxUploadMb(), properties.chatRateLimitPerMinute(),
                        properties.retrievalTopK(), properties.chunkSize(), properties.chunkOverlap(),
                        stats.messageCount())
        );
    }

    @GetMapping("/stats")
    public Dtos.Stats stats() {
        return knowledgeService.stats();
    }

    @GetMapping("/config/limits")
    public Map<String, Object> limits() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("max_upload_mb", properties.maxUploadMb());
        result.put("supported_extensions", List.of(".pdf", ".docx", ".md", ".markdown", ".txt"));
        return result;
    }

    private boolean databaseOk() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception exc) {
            return false;
        }
    }

    private String databaseLabel() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase().contains("mysql")) return "MySQL";
            if (product != null && product.toLowerCase().contains("h2")) return "H2（本地模式）";
            return product == null ? "未知数据库" : product;
        } catch (Exception exc) {
            return "数据库";
        }
    }
}
