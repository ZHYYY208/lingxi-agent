package com.lingxi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lingxi.demo-stream-delay-ms=0",
                "lingxi.admin-password=test-admin",
                "lingxi.secret-key=test-secret-key",
                "spring.datasource.url=jdbc:h2:mem:lingxi-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
class LingxiBackendApplicationTests {
    private static final Path TEST_DATA = createTempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("lingxi.data-dir", () -> TEST_DATA.toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("lingxi-java-test-");
        } catch (IOException exc) {
            throw new IllegalStateException(exc);
        }
    }

    private String adminToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/admin/login",
                new HttpEntity<>(Map.of("password", "test-admin"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = mapper.readValue(response.getBody(), new TypeReference<>() {
        });
        return body.get("token").toString();
    }

    private HttpHeaders adminHeaders() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", adminToken());
        return headers;
    }

    @Test
    void healthAndInfo() throws Exception {
        ResponseEntity<String> health = rest.getForEntity("/api/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> healthJson = mapper.readValue(health.getBody(), new TypeReference<>() {
        });
        assertThat(healthJson.get("status")).isEqualTo("ok");

        ResponseEntity<String> info = rest.getForEntity("/api/info", String.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> infoJson = mapper.readValue(info.getBody(), new TypeReference<>() {
        });
        assertThat(infoJson.get("mode")).isEqualTo("demo");
        assertThat(infoJson.get("database").toString()).contains("H2");
    }

    @Test
    void adminAuthenticationProtectsDocumentApis() throws Exception {
        assertThat(rest.getForEntity("/api/documents", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Token", adminToken());
        assertThat(rest.exchange("/api/documents", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> model = rest.exchange("/api/admin/model", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(model.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(model.getBody()).contains("\"provider\":\"demo\"");
    }

    @Test
    void adminModelConfigCanBeSavedWithoutReturningSecret() throws Exception {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> update = Map.of(
                "provider", "openai",
                "base_url", "http://127.0.0.1:9/v1",
                "model", "test-model",
                "api_key", "sk-test-12345678",
                "temperature", 0.1,
                "max_tokens", 300
        );
        ResponseEntity<String> saved = rest.exchange("/api/admin/model", HttpMethod.PUT,
                new HttpEntity<>(update, headers), String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> model = mapper.readValue(saved.getBody(), new TypeReference<>() {
        });
        assertThat(model.get("mode")).isEqualTo("llm");
        assertThat(model.get("api_key_set")).isEqualTo(true);
        assertThat(model.get("api_key_masked").toString()).doesNotContain("12345678");
        assertThat(saved.getBody()).doesNotContain("sk-test-12345678");

        HttpHeaders resetHeaders = adminHeaders();
        resetHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> reset = Map.of(
                "provider", "demo", "base_url", "", "model", "",
                "clear_api_key", true, "temperature", 0.2, "max_tokens", 1400
        );
        ResponseEntity<String> resetResponse = rest.exchange("/api/admin/model", HttpMethod.PUT,
                new HttpEntity<>(reset, resetHeaders), String.class);
        assertThat(resetResponse.getBody()).contains("\"mode\":\"demo\"");
    }

    @Test
    void knowledgeBaseSeededAndChatAnswersWithCitations() throws Exception {
        HttpHeaders adminHeaders = adminHeaders();
        ResponseEntity<String> docs = rest.exchange("/api/documents", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), String.class);
        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> documents = mapper.readValue(docs.getBody(), new TypeReference<>() {
        });
        assertThat(documents).hasSizeGreaterThanOrEqualTo(4);
        assertThat(documents.getFirst().get("is_demo")).isEqualTo(true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> request = Map.of("message", "员工年假怎么休？需要提前多久申请？");
        ResponseEntity<String> response = rest.postForEntity("/api/chat", new HttpEntity<>(request, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(response.getBody(), new TypeReference<>() {
        });
        assertThat(json.get("mode")).isEqualTo("extractive");
        assertThat(json.get("answer").toString()).contains("5 天");
        assertThat((List<?>) json.get("citations")).isNotEmpty();
    }

    @Test
    void databaseToolRunsWithChat() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> request = Map.of("message", "2026 年第三季度各区域销售额表现如何？");
        ResponseEntity<String> response = rest.postForEntity("/api/chat", new HttpEntity<>(request, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = mapper.readValue(response.getBody(), new TypeReference<>() {
        });
        assertThat(json.get("answer").toString()).contains("621.0 万元");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) json.get("tools");
        assertThat(tools).anyMatch(tool -> "database_query".equals(tool.get("name")) && "success".equals(tool.get("status")));
    }

    @Test
    void sseStreamContainsExpectedEvents() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        Map<String, Object> request = Map.of("message", "报销流程和发票要求是什么？");
        ResponseEntity<String> response = rest.postForEntity("/api/chat/stream", new HttpEntity<>(request, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("event:meta");
        assertThat(response.getBody()).contains("event:tool");
        assertThat(response.getBody()).contains("event:citations");
        assertThat(response.getBody()).contains("event:done");
    }

    @Test
    void uploadIndexAndDeleteDocument() throws Exception {
        String unique = java.util.UUID.randomUUID().toString();
        String content = "# 测试制度 " + unique + "\n\n## 办公用品\n\n员工可以申请人体工学键盘，由部门负责人审批。\n";
        ByteArrayResource file = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "policy-" + unique + ".md";
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", file);
        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        uploadHeaders.set("X-Admin-Token", adminToken());
        ResponseEntity<String> upload = rest.postForEntity("/api/documents/upload",
                new HttpEntity<>(form, uploadHeaders), String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> json = mapper.readValue(upload.getBody(), new TypeReference<>() {
        });
        List<Map<String, Object>> accepted = (List<Map<String, Object>>) json.get("documents");
        assertThat(accepted).hasSize(1);
        String documentId = accepted.getFirst().get("id").toString();

        Map<String, Object> document = null;
        for (int i = 0; i < 30; i++) {
            HttpHeaders pollHeaders = new HttpHeaders();
            pollHeaders.set("X-Admin-Token", adminToken());
            ResponseEntity<String> docs = rest.exchange("/api/documents", HttpMethod.GET,
                    new HttpEntity<>(pollHeaders), String.class);
            List<Map<String, Object>> all = mapper.readValue(docs.getBody(), new TypeReference<>() {
            });
            document = all.stream().filter(item -> documentId.equals(item.get("id"))).findFirst().orElse(null);
            if (document != null && "ready".equals(document.get("status"))) break;
            Thread.sleep(100);
        }
        assertThat(document).isNotNull();
        assertThat(document.get("status")).isEqualTo("ready");

        HttpHeaders chunkHeaders = new HttpHeaders();
        chunkHeaders.set("X-Admin-Token", adminToken());
        ResponseEntity<String> chunks = rest.exchange("/api/documents/" + documentId + "/chunks",
                HttpMethod.GET, new HttpEntity<>(chunkHeaders), String.class);
        assertThat(chunks.getBody()).contains("人体工学键盘");

        HttpHeaders deleteHeaders = new HttpHeaders();
        deleteHeaders.set("X-Admin-Token", adminToken());
        rest.exchange("/api/documents/" + documentId, HttpMethod.DELETE, new HttpEntity<>(deleteHeaders), String.class);
        ResponseEntity<String> after = rest.exchange("/api/documents/" + documentId + "/chunks",
                HttpMethod.GET, new HttpEntity<>(deleteHeaders), String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
