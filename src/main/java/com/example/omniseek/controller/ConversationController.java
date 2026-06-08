package com.example.omniseek.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.omniseek.entity.ChatMessage;
import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.exception.CustomException;
import com.example.omniseek.repository.UserRepository;
import com.example.omniseek.service.ChatSessionService;
import com.example.omniseek.service.ConversationArchiveService;
import com.example.omniseek.utils.JwtUtils;
import com.example.omniseek.utils.LogUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    /**
     * 获取指定会话的对话历史（优先从 Redis 读取，Redis 没有则从 MySQL 读取）
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getConversationBySessionId(
            @RequestHeader("Authorization") String token,
            @PathVariable String sessionId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATION_BY_ID");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_CONVERSATION_BY_ID", "token_validation",
                        "FAILED_INVALID_TOKEN");
                monitor.end("获取对话历史失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            LogUtils.logBusiness("GET_CONVERSATION_BY_ID", username, "获取会话历史，会话ID: {}", sessionId);

            // 使用 ArchiveService 获取历史（优先 Redis，无则 MySQL）
            List<ChatMessage> messages = archiveService.getHistoryMessages(sessionId);

            List<Map<String, Object>> formattedMessages = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, Object> msgMap = new HashMap<>();
                msgMap.put("role", msg.getRole());
                msgMap.put("content", msg.getContent());
                msgMap.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toString() : null);
                formattedMessages.add(msgMap);
            }

            LogUtils.logBusiness("GET_CONVERSATION_BY_ID", username,
                    "获取到 {} 条历史消息（来源: {})", formattedMessages.size(),
                    messages.isEmpty() ? "无数据" : "Redis/MySQL");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取对话历史成功");
            response.put("data", formattedMessages);

            monitor.end("获取对话历史成功");
            return ResponseEntity.ok(response);

        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_CONVERSATION_BY_ID", username, "获取对话历史失败: %s", e, e.getMessage());
            monitor.end("获取对话历史失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATION_BY_ID", username, "获取对话历史异常: %s", e, e.getMessage());
            monitor.end("获取对话历史异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ConversationArchiveService archiveService;

    /**
     * 查询用户所有会话列表
     */
    @GetMapping
    public ResponseEntity<?> getConversations(
            @RequestHeader("Authorization") String token) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_CONVERSATIONS");
        String username = null;
        try {
            // 从token中提取用户名
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_CONVERSATIONS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取对话历史失败：无效token");
                throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "开始查询用户会话列表");

            // 获取用户的所有会话
            List<ChatSession> sessions = chatSessionService.getUserSessions(username);
            List<Map<String, Object>> sessionList = new ArrayList<>();

            for (ChatSession session : sessions) {
                Map<String, Object> sessionData = new HashMap<>();
                sessionData.put("sessionId", session.getSessionId());
                sessionData.put("title", session.getTitle());
                sessionData.put("createdAt", session.getCreatedAt());
                sessionData.put("updatedAt", session.getUpdatedAt());
                sessionData.put("messageCount", session.getMessageCount());
                sessionList.add(sessionData);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "找到 %d 个会话", sessionList.size());
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_list", "SUCCESS");
            monitor.end("获取会话列表成功");

            // 构建统一响应格式
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取会话列表成功");
            response.put("data", sessionList);
            return ResponseEntity.ok().body(response);

        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取会话列表失败: %s", e, e.getMessage());
            monitor.end("获取会话列表失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取会话列表异常: %s", e, e.getMessage());
            monitor.end("获取会话列表异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

}