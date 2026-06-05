package com.example.omniseek.service;

import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.entity.User;
import com.example.omniseek.exception.CustomException;
import com.example.omniseek.repository.ChatSessionRepository;
import com.example.omniseek.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionService {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private UserRepository userRepository;

    public ChatSession createSession(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUser(user);
        session.setTitle("新对话");
        session.setActive(true);
        session.setMessageCount(0);

        return chatSessionRepository.save(session);
    }

    public List<ChatSession> getUserSessions(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        return chatSessionRepository.findByUserIdAndActiveOrderByUpdatedAtDesc(user.getId(), true);
    }

    public ChatSession getSession(String sessionId) {
        return chatSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new CustomException("会话不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public ChatSession updateSessionTitle(String sessionId, String title) {
        ChatSession session = getSession(sessionId);
        session.setTitle(title);
        return chatSessionRepository.save(session);
    }

    @Transactional
    public void incrementMessageCount(String sessionId) {
        ChatSession session = getSession(sessionId);
        session.setMessageCount(session.getMessageCount() + 1);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void deleteSession(String sessionId) {
        ChatSession session = getSession(sessionId);
        session.setActive(false);
        chatSessionRepository.save(session);
    }

    @Transactional
    public void permanentDeleteSession(String sessionId) {
        chatSessionRepository.deleteBySessionId(sessionId);
    }
}
