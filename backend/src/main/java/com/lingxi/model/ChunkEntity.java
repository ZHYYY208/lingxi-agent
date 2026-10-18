package com.lingxi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chunks", indexes = {
        @Index(name = "idx_chunks_document", columnList = "document_id"),
        @Index(name = "idx_chunks_document_ordinal", columnList = "document_id, ordinal")
})
public class ChunkEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(nullable = false)
    private int ordinal;

    @Lob
    @Column(nullable = false)
    private String text;

    @Lob
    @Column(nullable = false)
    private String embedding;

    private Integer page;

    @Column(length = 500)
    private String section;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
