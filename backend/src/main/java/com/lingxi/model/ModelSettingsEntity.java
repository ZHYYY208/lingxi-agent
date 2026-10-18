package com.lingxi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "model_settings")
public class ModelSettingsEntity {
    @Id
    private Long id = 1L;

    @Column(nullable = false, length = 20)
    private String provider = "demo";

    @Column(name = "base_url", length = 500)
    private String baseUrl = "";

    @Column(length = 160)
    private String model = "";

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "api_key_encrypted")
    private String apiKeyEncrypted;

    @Column(nullable = false)
    private double temperature = 0.2;

    @Column(name = "max_tokens", nullable = false)
    private int maxTokens = 1400;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
