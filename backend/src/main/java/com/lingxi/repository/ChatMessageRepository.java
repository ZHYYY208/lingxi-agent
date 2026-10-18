package com.lingxi.repository;

import com.lingxi.model.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {
    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    Optional<ChatMessageEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    long countBySessionId(String sessionId);

    @Modifying
    @Query("delete from ChatMessageEntity m where m.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
