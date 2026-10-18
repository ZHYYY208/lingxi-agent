package com.lingxi.repository;

import com.lingxi.model.ChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkRepository extends JpaRepository<ChunkEntity, String> {
    List<ChunkEntity> findByDocumentIdOrderByOrdinal(String documentId);

    @Modifying
    @Query("delete from ChunkEntity c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") String documentId);

    @Query("select coalesce(sum(c.charCount), 0) from ChunkEntity c")
    long sumCharCount();
}
