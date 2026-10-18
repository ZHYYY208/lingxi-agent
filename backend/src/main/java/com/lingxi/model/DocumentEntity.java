package com.lingxi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_status", columnList = "status"),
        @Index(name = "idx_documents_hash", columnList = "content_hash")
})
public class DocumentEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(nullable = false, length = 16)
    private String extension;

    @Column(name = "mime_type", nullable = false, length = 128)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 20)
    private String status = "processing";

    @Column(length = 2000)
    private String error;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "char_count", nullable = false)
    private long charCount;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "is_demo", nullable = false)
    private boolean demo;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
