package com.lingxi.repository;

import com.lingxi.model.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {
    List<ChatSessionEntity> findTop50ByOrderByUpdatedAtDesc();

    Optional<ChatSessionEntity> findFirstByOrderByUpdatedAtDesc();
}
