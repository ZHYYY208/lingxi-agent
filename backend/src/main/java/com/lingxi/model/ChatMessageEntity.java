package com.lingxi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_messages_session", columnList = "session_id"),
        @Index(name = "idx_messages_created", columnList = "created_at")
})
public class ChatMessageEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 16)
    private String role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Lob
    @Column(name = "citations_json", nullable = false)
    private String citationsJson = "[]";

    @Lob
    @Column(name = "tools_json", nullable = false)
    private String toolsJson = "[]";

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
