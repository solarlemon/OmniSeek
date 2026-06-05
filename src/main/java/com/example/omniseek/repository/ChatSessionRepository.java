package com.example.omniseek.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.omniseek.entity.ChatSession;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findBySessionId(String sessionId);

    List<ChatSession> findByUserIdAndActiveOrderByUpdatedAtDesc(Long userId, Boolean active);

    Optional<ChatSession> findByUserIdAndActiveTrue(Long userId);

    void deleteBySessionId(String sessionId);
}
