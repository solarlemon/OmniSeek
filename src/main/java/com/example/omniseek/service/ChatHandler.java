package com.example.omniseek.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.omniseek.client.DeepSeekClient;
import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.router.RouteManager;
import com.example.omniseek.service.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RouteManager routeManager;
    private final DeepSeekClient deepSeekClient;
    private final ChatSessionService chatSessionService;
    private final ConversationArchiveService archiveService;
    private final ObjectMapper objectMapper;

    // 滑动窗口配置
    @Value("${ai.context.window.size:8}")
    private int windowSize;
    @Value("${ai.context.compaction.trigger:12}")
    private int compactionTriggerCount;
    @Value("${ai.context.compaction.max-input-chars:4000}")
    private int maxCompactionInputChars;
    @Value("${ai.context.summary-prompt}")
    private String summaryPrompt;

    @Qualifier("compactionExecutor")
    private final Executor compactionExecutor; // 为了异步处理压缩上下文的任务

    // 流式响应相关（保持原有逻辑）
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    // 用于跟踪每个会话的响应完成状态
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();
    // 停止标志 - 简单方案
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
            RouteManager routeManager,
            DeepSeekClient deepSeekClient,
            ChatSessionService chatSessionService,
            ConversationArchiveService archiveService,
            Executor compactionExecutor) {
        this.redisTemplate = redisTemplate;
        this.routeManager = routeManager;
        this.deepSeekClient = deepSeekClient;
        this.chatSessionService = chatSessionService;
        this.archiveService = archiveService;
        this.objectMapper = new ObjectMapper();
        this.compactionExecutor = compactionExecutor;
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        processMessage(userId, userMessage, session, null);
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session, String conversationId) {
        /*
         * 处理用户消息，通过路由系统选择合适的处理方式
         */
        logger.debug("开始处理消息，用户ID: {}, session ID: {}, conversationId: {}", userId, session.getId(), conversationId);
        try {
            // 1. 获取会话 ID（优先使用传入的，否则复用已存会话，再否则创建新的）
            String finalConversationId;
            if (conversationId != null && !conversationId.isBlank()) {
                finalConversationId = conversationId;
                logger.info("使用传入的会话ID: {}, 用户ID: {}", finalConversationId, userId);
            } else {
                // 尝试从 Redis 获取用户当前活动的会话 ID
                String currentSessionKey = "user:" + userId + ":current_session";
                String existingSessionId = redisTemplate.opsForValue().get(currentSessionKey);

                if (existingSessionId != null) {
                    finalConversationId = existingSessionId;
                    logger.info("复用用户当前会话ID: {}, 用户ID: {}", finalConversationId, userId);
                } else {
                    // 创建新会话并获取其 ID
                    ChatSession newSession = chatSessionService.createSession(userId);
                    finalConversationId = newSession.getSessionId();
                    // 存入 Redis，下次复用
                    redisTemplate.opsForValue().set(currentSessionKey, finalConversationId, Duration.ofDays(7));
                    logger.info("创建新会话，会话ID: {}, 用户ID: {}", finalConversationId, userId);
                }
            }

            // 为当前会话创建响应构建器
            responseBuilders.put(session.getId(), new StringBuilder());
            // 创建一个CompletableFuture来跟踪响应完成状态
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(session.getId(), responseFuture);

            // 获取压缩后的历史（摘要 + 滑动窗口消息）
            List<Map<String, String>> history = getCompressedHistory(finalConversationId);
            logger.debug("获取到 {} 条历史（含摘要）", history.size());

            // 路由处理（流式生成）
            responseFutures.put(session.getId(), responseFuture);

            String convId = finalConversationId; // 用于 lambda 中
            routeManager.route(userId, userMessage, history, session,
                    chunk -> {
                        StringBuilder builder = responseBuilders.get(session.getId());
                        if (builder != null)
                            builder.append(chunk);
                        sendResponseChunk(session, chunk);
                    },
                    error -> {
                        handleError(session, error);
                        responseFuture.completeExceptionally(error);
                        cleanupSession(session.getId());
                    },
                    () -> { // onComplete 回调
                        String fullResponse = responseBuilders.get(session.getId()).toString();
                        responseFuture.complete(fullResponse);
                        saveMessagesAndCompact(convId, userMessage, fullResponse);
                        // 每次响应完成后，使用当前时间更新会话活动时间以实现定时归档
                        archiveService.recordActivity(convId);
                        sendCompletionNotification(session, convId);
                        cleanupSession(session.getId());
                    });

        } catch (Exception e) {
            logger.error("处理消息错误: {}", e.getMessage(), e);
            handleError(session, e);
            cleanupSession(session.getId());
        }
    }

    public void stopResponse(String userId, WebSocketSession session) {
        /* 处理用户停止响应请求，接收到停止请求后，设置对应用户id的停止标志位为 true */
        String sessionId = session.getId();
        logger.info("收到停止请求，用户ID: {}, 会话ID: {}", userId, sessionId);
        stopFlags.put(sessionId, true);
        try {
            Map<String, Object> response = Map.of(
                    "type", "stop",
                    "message", "响应已停止",
                    "timestamp", System.currentTimeMillis());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            logger.error("发送停止确认失败", e);
        }
        // 等待 2 秒，确保前端有足够的时间处理停止确认
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            stopFlags.remove(sessionId);
        }).start();
    }

    /**
     * 获取压缩后的历史：摘要(作为system消息) + 滑动窗口消息
     */
    private List<Map<String, String>> getCompressedHistory(String conversationId) {
        List<Map<String, String>> result = new ArrayList<>();

        String summary = getSummary(conversationId);
        if (summary != null && !summary.isBlank()) {
            Map<String, String> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "【历史摘要】" + summary);
            result.add(summaryMsg);
        }

        List<Map<String, String>> windowMessages = getWindowMessages(conversationId);
        result.addAll(windowMessages);
        return result;
    }

    /**
     * 存储新消息（用户+助手）并尝试压缩
     */
    private void saveMessagesAndCompact(String conversationId, String userMessage, String assistantResponse) {
        /**
         * 当滑动窗口消息数超过 compactionTriggerCount（如 12 条）时，
         * 保留末尾 windowSize 条（如 8 条），前面的消息与旧摘要一起生成新摘要。
         */
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", timestamp);

        Map<String, String> assistantMsg = new HashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", assistantResponse);
        assistantMsg.put("timestamp", timestamp);

        List<Map<String, String>> currentWindow = getWindowMessages(conversationId);
        currentWindow.add(userMsg);
        currentWindow.add(assistantMsg);

        if (currentWindow.size() > compactionTriggerCount) {
            // 异步执行压缩，避免阻塞 Reactor 线程
            compactionExecutor.execute(() -> {
                try {
                    compactConversation(conversationId, currentWindow);
                } catch (Exception e) {
                    logger.error("异步压缩失败", e);
                }
            });
        } else {
            saveWindowMessages(conversationId, currentWindow);
        }
    }

    /**
     * 执行压缩：保留最后 windowSize 条消息，前面的消息+旧摘要生成新摘要
     * 压缩前会将早期消息完整保存到 MySQL，确保数据不丢失
     */
    private void compactConversation(String conversationId, List<Map<String, String>> currentWindow) {
        int keepCount = Math.min(windowSize, currentWindow.size());
        List<Map<String, String>> messagesToKeep = currentWindow.subList(currentWindow.size() - keepCount,
                currentWindow.size());
        List<Map<String, String>> messagesToCompact = currentWindow.subList(0, currentWindow.size() - keepCount);

        // 先完整保存到 MySQL，再压缩（确保早期消息不丢失）
        try {
            archiveService.saveMessagesBeforeCompaction(conversationId, new ArrayList<>(messagesToCompact));
        } catch (Exception e) {
            logger.error("压缩前保存消息到 MySQL 失败", e);
        }

        String oldSummary = getSummary(conversationId);
        String newSummary = generateSummary(oldSummary, messagesToCompact);

        saveSummary(conversationId, newSummary);
        saveWindowMessages(conversationId, messagesToKeep);

        logger.info("压缩完成，conversationId={}, 压缩消息数={}, 保留消息数={}, 新摘要长度={}",
                conversationId, messagesToCompact.size(), messagesToKeep.size(), newSummary.length());
    }

    /**
     * 调用 DeepSeekClient 生成摘要
     */
    private String generateSummary(String oldSummary, List<Map<String, String>> messages) {
        // 将消息序列化为文本（截取尾部避免过长）
        StringBuilder conversationText = new StringBuilder();
        for (Map<String, String> msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            conversationText.append(role).append(": ").append(content).append("\n");
        }
        String text = conversationText.toString();
        if (text.length() > maxCompactionInputChars) {
            text = text.substring(text.length() - maxCompactionInputChars);
        }

        String prompt = summaryPrompt
                .replace("{oldSummary}", oldSummary == null ? "无" : oldSummary)
                .replace("{conversationText}", text);

        return deepSeekClient.generateSummary(prompt);
    }

    private String getSummary(String conversationId) {
        String key = "conversation:" + conversationId + ":summary";
        return redisTemplate.opsForValue().get(key);
    }

    private void saveSummary(String conversationId, String summary) {
        String key = "conversation:" + conversationId + ":summary";
        redisTemplate.opsForValue().set(key, summary, Duration.ofDays(7));
    }

    private List<Map<String, String>> getWindowMessages(String conversationId) {
        String key = "conversation:" + conversationId + ":window";
        String json = redisTemplate.opsForValue().get(key);
        if (json == null)
            return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (JsonProcessingException e) {
            logger.error("解析窗口消息失败", e);
            return new ArrayList<>();
        }
    }

    private void saveWindowMessages(String conversationId, List<Map<String, String>> messages) {
        String key = "conversation:" + conversationId + ":window";
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
        } catch (JsonProcessingException e) {
            logger.error("保存窗口消息失败", e);
        }
    }

    private void cleanupSession(String sessionId) {
        /* 清理会话资源，包括响应构建器、响应未来和停止标志位 ，防止 OOM*/
        responseBuilders.remove(sessionId);
        responseFutures.remove(sessionId);
        stopFlags.remove(sessionId);
    }

    private void sendResponseChunk(WebSocketSession session, String chunk) {
        /*发送消息到前端，实时更新响应内容。每次都会检查是否需要停止 */
        if (Boolean.TRUE.equals(stopFlags.get(session.getId())))
            return;
        try {
            Map<String, String> chunkResponse = Map.of("chunk", chunk);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chunkResponse)));
        } catch (Exception e) {
            logger.error("发送响应块失败", e);
        }
    }

    private void sendCompletionNotification(WebSocketSession session, String sessionId) {
        try {
            Map<String, Object> notification = Map.of(
                    "type", "completion",
                    "status", "finished",
                    "sessionId", sessionId,
                    "timestamp", System.currentTimeMillis());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("发送完成通知失败", e);
        }
    }

    private void handleError(WebSocketSession session, Throwable error) {
        logger.error("处理错误", error);
        try {
            Map<String, String> errorResponse = Map.of("error", "AI服务暂时不可用，请稍后重试");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResponse)));
        } catch (Exception e) {
            logger.error("发送错误消息失败", e);
        }
    }
}