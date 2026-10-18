package com.lingxi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.dto.Dtos;
import com.lingxi.service.AgentService;
import com.lingxi.service.SessionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final AgentService agentService;
    private final SessionService sessionService;
    private final ObjectMapper mapper;
    private final TaskExecutor agentExecutor;

    public ChatController(
            AgentService agentService,
            SessionService sessionService,
            ObjectMapper mapper,
            @Qualifier("agentExecutor") TaskExecutor agentExecutor
    ) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.mapper = mapper;
        this.agentExecutor = agentExecutor;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@Valid @RequestBody Dtos.ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");
        headers.set("Connection", "keep-alive");

        agentExecutor.execute(() -> {
            try {
                agentService.run(request.message(), request.sessionId(), request.useWeb(), true, (name, data) -> {
                    String json = mapper.writeValueAsString(data);
                    emitter.send(SseEmitter.event().name(name).data(json, MediaType.TEXT_PLAIN));
                });
                emitter.complete();
            } catch (Exception exc) {
                if (isClientDisconnected(exc)) {
                    emitter.completeWithError(exc);
                    return;
                }
                log.error("Chat stream failed: {}", exc.getMessage());
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("message", "回答生成失败，请稍后重试");
                error.put("detail", truncate(exc.getMessage(), 300));
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            mapper.writeValueAsString(error), MediaType.TEXT_PLAIN));
                    emitter.complete();
                } catch (Exception sendError) {
                    emitter.completeWithError(exc);
                }
            }
        });
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @PostMapping("/chat")
    public Dtos.ChatResult chat(@Valid @RequestBody Dtos.ChatRequest request) {
        try {
            return agentService.run(request.message(), request.sessionId(), request.useWeb(), false, (name, data) -> {
            });
        } catch (IOException exc) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "回答生成失败", exc);
        }
    }

    @GetMapping("/sessions")
    public List<Dtos.SessionSummary> sessions() {
        return sessionService.listSessions();
    }

    @PostMapping("/sessions")
    public Dtos.SessionDetail createSession() {
        return sessionService.create("新对话");
    }

    @GetMapping("/sessions/{sessionId}")
    public Dtos.SessionDetail session(@PathVariable String sessionId) {
        return sessionService.detail(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    @PatchMapping("/sessions/{sessionId}")
    public Dtos.SessionDetail rename(@PathVariable String sessionId,
                                     @Valid @RequestBody Dtos.RenameSessionRequest request) {
        if (!sessionService.rename(sessionId, request.title().strip())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return sessionService.detail(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Dtos.SimpleResponse deleteSession(@PathVariable String sessionId) {
        if (!sessionService.delete(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return new Dtos.SimpleResponse(true, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<Dtos.MessageDto> messages(@PathVariable String sessionId) {
        if (sessionService.detail(sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return sessionService.messages(sessionId);
    }

    @PostMapping("/feedback")
    public Dtos.FeedbackResponse feedback(@Valid @RequestBody Dtos.FeedbackRequest request) {
        String id = sessionService.feedback(request.messageId(), request.rating(),
                request.comment() == null ? "" : request.comment());
        return new Dtos.FeedbackResponse(true, id);
    }

    private static boolean isClientDisconnected(Exception exc) {
        String message = exc.getMessage();
        return exc instanceof IllegalStateException
                || (message != null && (message.contains("Broken pipe") || message.contains("Connection reset")
                || message.contains("disconnected")));
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
