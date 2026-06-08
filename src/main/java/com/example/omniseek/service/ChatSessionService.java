package com.example.omniseek.service;

import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.entity.User;
import com.example.omniseek.exception.CustomException;
import com.example.omniseek.repository.ChatSessionRepository;
import com.example.omniseek.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);

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

        //TODO:对话先存在Redis，一段时间后再从Redis迁移到数据库持久化存储
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

    /**
     * 归档会话时更新元数据（由 ConversationArchiveService 调用）
     * 更新消息计数，如果标题为默认值则尝试设置标题
     */
    @Transactional
    public void archiveSession(String sessionId, int messageCount, String summary) {
        ChatSession session = getSession(sessionId);
        session.setMessageCount(session.getMessageCount() + messageCount);

        // 如果标题还是默认的"新对话"，尝试从摘要中提取标题
        if ("新对话".equals(session.getTitle()) && summary != null && !summary.isBlank()) {
            String title = summary.length() > 30 ? summary.substring(0, 30) + "..." : summary;
            session.setTitle(title);
        }

        chatSessionRepository.save(session);
        logger.info("会话归档元数据更新完成: sessionId={}, messageCount={}", sessionId, session.getMessageCount());
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
