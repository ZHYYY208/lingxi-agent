package com.lingxi.repository;

import com.lingxi.model.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {
    List<DocumentEntity> findAllByOrderByCreatedAtDesc();

    Optional<DocumentEntity> findFirstByContentHashAndStatusIn(String contentHash, Collection<String> statuses);

    long countByStatus(String status);

    List<DocumentEntity> findByStatus(String status);

    @Query("select max(d.updatedAt) from DocumentEntity d where d.status = 'ready'")
    Instant findLastReadyUpdatedAt();
}
