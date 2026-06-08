package com.example.omniseek.service;

import com.example.omniseek.entity.ChatMessage;
import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.repository.ChatMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ConversationArchiveService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationArchiveService.class);

    private static final String ACTIVITY_KEY = "conversation:activity";

    @Value("${conversation.archive.inactive-days:3}")
    private int inactiveDays;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionService chatSessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 记录会话活动时间（发消息或收回复时调用）
     * 更新 Redis ZSET 中该会话的 score 为当前时间戳
     */
    public void recordActivity(String sessionId) {
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, sessionId, now);
        logger.debug("记录会话活动时间: sessionId={}", sessionId);
    }

    /**
     * 定时任务：每 1 小时扫描一次，将 3 天无活动的会话归档到 MySQL
     */
    @Scheduled(fixedRateString = "${conversation.archive.scan-interval:3600000}")
    @Transactional
    public void archiveInactiveSessions() {
        long deadline = System.currentTimeMillis() - Duration.ofDays(inactiveDays).toMillis();

        logger.info("开始扫描无活动会话，截止时间戳: {}, 日期: {}", deadline, LocalDateTime.now().minusDays(inactiveDays));

        // 找出所有超过 deadline 的会话（score < deadline）
        Set<String> inactiveSessions = redisTemplate.opsForZSet()
                .rangeByScore(ACTIVITY_KEY, 0, deadline);

        if (inactiveSessions == null || inactiveSessions.isEmpty()) {
            logger.info("没有需要归档的无活动会话");
            return;
        }

        logger.info("发现 {} 个可能需要归档的会话", inactiveSessions.size());

        for (String sessionId : inactiveSessions) {
            try {
                // 再次检查，防止在迭代过程中该会话又被激活
                Double currentScore = redisTemplate.opsForZSet().score(ACTIVITY_KEY, sessionId);
                if (currentScore == null || currentScore > deadline) {
                    logger.debug("会话 {} 已被激活，跳过归档", sessionId);
                    continue;
                }

                archiveSingleSession(sessionId);

            } catch (Exception e) {
                logger.error("归档会话失败: sessionId={}", sessionId, e);
            }
        }

        logger.info("会话扫描归档完成");
    }

    /**
     * 归档单个会话：Redis → MySQL
     */
    @Transactional
    public void archiveSingleSession(String sessionId) {
        logger.info("开始归档会话: sessionId={}", sessionId);

        // 1. 从 Redis 读取窗口消息
        List<Map<String, String>> windowMessages = getWindowMessagesFromRedis(sessionId);

        // 2. 从 Redis 读取摘要
        String summary = getSummaryFromRedis(sessionId);

        if (windowMessages.isEmpty() && summary == null) {
            logger.warn("会话 {} 在 Redis 中无数据，仅清理活动记录", sessionId);
            cleanupRedis(sessionId);
            return;
        }

        // 3. 填充缺失的 userId 信息（从 chat_sessions 获取）
        String userId = null;
        try {
            ChatSession session = chatSessionService.getSession(sessionId);
            userId = String.valueOf(session.getUser().getId());

            // 4. 将窗口消息写入 MySQL
            List<ChatMessage> messagesToSave = new ArrayList<>();
            for (Map<String, String> msg : windowMessages) {
                String role = msg.get("role");
                String content = msg.get("content");
                String timestampStr = msg.get("timestamp");

                LocalDateTime ts = LocalDateTime.now();
                if (timestampStr != null) {
                    try {
                        ts = LocalDateTime.parse(timestampStr);
                    } catch (Exception e) {
                        logger.warn("消息时间戳解析失败: {}, 使用当前时间", timestampStr);
                    }
                }

                messagesToSave.add(new ChatMessage(sessionId, userId, role, content, ts));
            }

            // 如果有摘要，将摘要也作为一条 system 消息保存
            if (summary != null && !summary.isBlank()) {
                messagesToSave
                        .add(new ChatMessage(sessionId, userId, "system", "【历史摘要】" + summary, LocalDateTime.now()));
            }

            chatMessageRepository.saveAll(messagesToSave);

            // 5. 更新 chat_sessions 表中的消息数
            int totalMessages = windowMessages.size();
            chatSessionService.archiveSession(sessionId, totalMessages, summary);

            logger.info("会话归档完成: sessionId={}, 归档消息数={}", sessionId, messagesToSave.size());

        } catch (Exception e) {
            logger.error("归档会话时获取用户信息失败: sessionId={}", sessionId, e);
        }

        // 6. 清理 Redis 中该会话的所有相关数据
        cleanupRedis(sessionId);
    }

    /**
     * 清理 Redis 中该会话的所有数据
     */
    private void cleanupRedis(String sessionId) {
        redisTemplate.delete("conversation:" + sessionId + ":window");
        redisTemplate.delete("conversation:" + sessionId + ":summary");
        redisTemplate.opsForZSet().remove(ACTIVITY_KEY, sessionId);
        logger.debug("已清理 Redis 中会话 {} 的数据", sessionId);
    }

    /**
     * 从 Redis 读取窗口消息
     */
    private List<Map<String, String>> getWindowMessagesFromRedis(String sessionId) {
        String key = "conversation:" + sessionId + ":window";
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException e) {
            logger.error("解析 Redis 窗口消息失败: sessionId={}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 从 Redis 读取摘要
     */
    private String getSummaryFromRedis(String sessionId) {
        String key = "conversation:" + sessionId + ":summary";
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 将消息完整保存到 MySQL（供压缩前调用，确保早期消息不丢失）
     */
    @Transactional
    public void saveMessagesBeforeCompaction(String sessionId, List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 获取 userId
        String userId = null;
        try {
            ChatSession session = chatSessionService.getSession(sessionId);
            userId = String.valueOf(session.getUser().getId());
        } catch (Exception e) {
            logger.warn("保存压缩前消息时获取用户信息失败: sessionId={}", sessionId, e);
            return;
        }

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            String timestampStr = msg.get("timestamp");

            LocalDateTime ts = LocalDateTime.now();
            if (timestampStr != null) {
                try {
                    ts = LocalDateTime.parse(timestampStr);
                } catch (Exception e) {
                    // 忽略解析失败
                }
            }

            chatMessages.add(new ChatMessage(sessionId, userId, role, content, ts));
        }

        chatMessageRepository.saveAll(chatMessages);
        logger.info("压缩前已保存 {} 条消息到 MySQL，sessionId={}", chatMessages.size(), sessionId);
    }

    /**
     * 获取会话的历史消息（优先 Redis，Redis 没有则从 MySQL 读取）
     */
    public List<ChatMessage> getHistoryMessages(String sessionId) {
        // 1. 检查 Redis 中是否有数据（活跃会话）
        String redisKey = "conversation:" + sessionId + ":window";
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            logger.debug("从 Redis 读取会话历史: sessionId={}", sessionId);
            List<Map<String, String>> redisMessages = getWindowMessagesFromRedis(sessionId);

            try {
                // 获取 userId
                ChatSession session = chatSessionService.getSession(sessionId);
                String userId = String.valueOf(session.getUser().getId());
                // 转换为 ChatMessage 列表
                return redisMessages.stream()
                        .map(msg -> {
                            String role = msg.get("role");
                            String content = msg.get("content");
                            String timestampStr = msg.get("timestamp");
                            LocalDateTime ts = LocalDateTime.now();
                            if (timestampStr != null) {
                                try {
                                    ts = LocalDateTime.parse(timestampStr);
                                } catch (Exception ignored) {
                                }
                            }
                            return new ChatMessage(null, sessionId, userId, role, content, ts);
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.warn("获取会话用户信息失败: sessionId={}", sessionId, e);
                return new ArrayList<>();
            }
        }

        // 2. Redis 没有则从 MySQL 读取（已归档会话）
        logger.debug("从 MySQL 读取会话历史: sessionId={}", sessionId);
        return chatMessageRepository.findBySessionIdOrderByTimestamp(sessionId);
    }
}
