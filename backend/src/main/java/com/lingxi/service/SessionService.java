package com.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingxi.dto.Dtos;
import com.lingxi.model.ChatMessageEntity;
import com.lingxi.model.ChatSessionEntity;
import com.lingxi.model.FeedbackEntity;
import com.lingxi.repository.ChatMessageRepository;
import com.lingxi.repository.ChatSessionRepository;
import com.lingxi.repository.FeedbackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class SessionService {
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final FeedbackRepository feedbackRepository;
    private final DtoMapper mapper;
    private final SessionCache cache;
    private final ObjectMapper objectMapper;

    public SessionService(
            ChatSessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            FeedbackRepository feedbackRepository,
            DtoMapper mapper,
            SessionCache cache,
            ObjectMapper objectMapper
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.mapper = mapper;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public ChatSessionEntity getOrCreate(String sessionId, String title) {
        if (sessionId != null && !sessionId.isBlank()) {
            Optional<ChatSessionEntity> existing = sessionRepository.findById(sessionId);
            if (existing.isPresent()) return existing.get();
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTitle(truncate(title, 120));
        return sessionRepository.save(session);
    }

    public Dtos.SessionDetail create(String title) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTitle(title == null || title.isBlank() ? "新对话" : truncate(title, 120));
        sessionRepository.save(session);
        return detail(session.getId()).orElseThrow();
    }

    public List<Dtos.SessionSummary> listSessions() {
        List<Dtos.SessionSummary> result = new ArrayList<>();
        for (ChatSessionEntity session : sessionRepository.findTop50ByOrderByUpdatedAtDesc()) {
            long count = messageRepository.countBySessionId(session.getId());
            ChatMessageEntity last = messageRepository.findTopBySessionIdOrderByCreatedAtDesc(session.getId()).orElse(null);
            result.add(new Dtos.SessionSummary(
                    session.getId(), session.getTitle(), count,
                    last == null ? "" : truncate(last.getContent(), 80),
                    session.getCreatedAt(), session.getUpdatedAt()
            ));
        }
        return result;
    }

    public Optional<Dtos.SessionDetail> detail(String sessionId) {
        Optional<ChatSessionEntity> session = sessionRepository.findById(sessionId);
        if (session.isEmpty()) return Optional.empty();
        return Optional.of(new Dtos.SessionDetail(
                session.get().getId(), session.get().getTitle(), session.get().getCreatedAt(),
                session.get().getUpdatedAt(), messages(sessionId)
        ));
    }

    public List<Dtos.MessageDto> messages(String sessionId) {
        Optional<List<Dtos.MessageDto>> cached = cache.get(sessionId);
        if (cached.isPresent()) return cached.get();
        List<Dtos.MessageDto> messages = new ArrayList<>();
        for (ChatMessageEntity message : messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            messages.add(mapper.message(message));
        }
        cache.put(sessionId, messages);
        return messages;
    }

    public List<Dtos.MessageDto> history(String sessionId, int limit) {
        List<Dtos.MessageDto> messages = messages(sessionId);
        return messages.size() <= limit ? messages : messages.subList(messages.size() - limit, messages.size());
    }

    @Transactional
    public Dtos.MessageDto addMessage(String sessionId, String role, String content,
                                      List<Dtos.Citation> citations, List<Dtos.ToolTrace> tools, int latencyMs) {
        ChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("会话不存在"));
        ChatMessageEntity message = new ChatMessageEntity();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        try {
            message.setCitationsJson(objectMapper.writeValueAsString(citations == null ? List.of() : citations));
            message.setToolsJson(objectMapper.writeValueAsString(tools == null ? List.of() : tools));
        } catch (Exception exc) {
            message.setCitationsJson("[]");
            message.setToolsJson("[]");
        }
        message.setLatencyMs(latencyMs);
        messageRepository.save(message);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        cache.invalidate(sessionId);
        return mapper.message(message);
    }

    public boolean rename(String sessionId, String title) {
        Optional<ChatSessionEntity> session = sessionRepository.findById(sessionId);
        if (session.isEmpty()) return false;
        session.get().setTitle(truncate(title, 120));
        sessionRepository.save(session.get());
        return true;
    }

    @Transactional
    public boolean delete(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) return false;
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        cache.invalidate(sessionId);
        return true;
    }

    public String feedback(String messageId, int rating, String comment) {
        FeedbackEntity feedback = new FeedbackEntity();
        feedback.setMessageId(messageId);
        feedback.setRating(rating);
        feedback.setComment(comment == null ? "" : truncate(comment, 2000));
        feedbackRepository.save(feedback);
        return feedback.getId();
    }

    public String storeName() {
        if (!cache.enabled()) return "H2/MySQL";
        return cache.healthy() ? "Redis + H2/MySQL" : "H2/MySQL（Redis 不可用）";
    }

    public boolean redisHealthy() {
        return cache.healthy();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
