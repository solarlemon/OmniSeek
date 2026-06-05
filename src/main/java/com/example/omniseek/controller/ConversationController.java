package com.example.omniseek.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.omniseek.exception.CustomException;
import com.example.omniseek.model.ChatSession;
import com.example.omniseek.model.User;
import com.example.omniseek.repository.UserRepository;
import com.example.omniseek.service.ChatSessionService;
import com.example.omniseek.utils.JwtUtils;
import com.example.omniseek.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    /**
     * 获取指定会话的对话历史
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

            return getConversationsFromRedis(sessionId, username, null, null, monitor);

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
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private ChatSessionService chatSessionService;

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
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "获取会话列表异常: %s", e, e.getMessage());
            monitor.end("获取会话列表异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
        }
    }

    /**
     * 从Redis获取对话历史
     */
    private ResponseEntity<?> getConversationsFromRedis(String conversationId, String username, String start_date, String end_date, LogUtils.PerformanceMonitor monitor) {
        // 从Redis获取对话历史（新格式）
        String key = "conversation:" + conversationId + ":window";
        String json = redisTemplate.opsForValue().get(key);
        
        // 如果新格式没有，尝试旧格式（兼容）
        if (json == null) {
            String oldKey = "conversation:" + conversationId;
            json = redisTemplate.opsForValue().get(oldKey);
            if (json != null) {
                LogUtils.logBusiness("GET_CONVERSATIONS", username, "使用旧格式Redis键: {}", oldKey);
            }
        }

        List<Map<String, Object>> formattedConversations = new ArrayList<>();
        if (json != null) {
            try {
                LogUtils.logBusiness("GET_CONVERSATIONS", username, "从Redis获取到数据，长度: {}, 内容预览: {}", 
                        json.length(), json.substring(0, Math.min(100, json.length())));
                
                // 将原始Redis数据转换为前端可用的格式
                List<Map<String, String>> history = objectMapper.readValue(json, 
                        new TypeReference<List<Map<String, String>>>() {});

                // 解析时间范围
                LocalDateTime startDateTime = null;
                LocalDateTime endDateTime = null;

                if (start_date != null && !start_date.trim().isEmpty()) {
                    try {
                        startDateTime = parseDateTime(start_date);
                        LogUtils.logBusiness("GET_CONVERSATIONS", username, "解析起始时间: %s -> %s", start_date,
                                startDateTime);
                    } catch (Exception e) {
                        LogUtils.logBusinessError("GET_CONVERSATIONS", username, "起始时间解析失败: %s", e, start_date);
                        throw new CustomException("起始时间格式错误: " + start_date, HttpStatus.BAD_REQUEST);
                    }
                }

                if (end_date != null && !end_date.trim().isEmpty()) {
                    try {
                        endDateTime = parseDateTime(end_date);
                        LogUtils.logBusiness("GET_CONVERSATIONS", username, "解析结束时间: %s -> %s", end_date, endDateTime);
                    } catch (Exception e) {
                        LogUtils.logBusinessError("GET_CONVERSATIONS", username, "结束时间解析失败: %s", e, end_date);
                        throw new CustomException("结束时间格式错误: " + end_date, HttpStatus.BAD_REQUEST);
                    }
                }

                // 将对话转换为前端需要的格式，使用存储的时间戳并进行时间过滤
                for (Map<String, String> message : history) {
                    String messageTimestamp = message.getOrDefault("timestamp", "未知时间");

                    // 时间过滤
                    if (startDateTime != null || endDateTime != null) {
                        if (!"未知时间".equals(messageTimestamp)) {
                            try {
                                LocalDateTime messageDateTime = LocalDateTime.parse(messageTimestamp,
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                                // 检查是否在时间范围内
                                if (startDateTime != null && messageDateTime.isBefore(startDateTime)) {
                                    continue; // 跳过早于起始时间的消息
                                }
                                if (endDateTime != null && messageDateTime.isAfter(endDateTime)) {
                                    continue; // 跳过晚于结束时间的消息
                                }
                            } catch (Exception e) {
                                // 时间戳格式不正确，跳过过滤（包含所有消息）
                                LogUtils.logBusinessError("GET_CONVERSATIONS", username, "消息时间戳格式错误: %s", e,
                                        messageTimestamp);
                            }
                        }
                        // 如果是"未知时间"且设置了时间过滤，跳过该消息
                        else if (startDateTime != null || endDateTime != null) {
                            continue;
                        }
                    }

                    Map<String, Object> messageWithTimestamp = new HashMap<>();
                    messageWithTimestamp.put("role", message.get("role"));
                    messageWithTimestamp.put("content", message.get("content"));
                    messageWithTimestamp.put("timestamp", messageTimestamp);
                    formattedConversations.add(messageWithTimestamp);
                }

                LogUtils.logBusiness("GET_CONVERSATIONS", username, "从Redis中获取到 %d 条对话记录，过滤后剩余 %d 条，会话ID: %s",
                        history.size(), formattedConversations.size(), conversationId);
                LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS");
                monitor.end("获取对话历史成功");
            } catch (JsonProcessingException e) {
                LogUtils.logBusinessError("GET_CONVERSATIONS", username, "解析对话历史出错", e);
                monitor.end("解析对话历史失败");
                throw new CustomException("解析对话历史失败", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            LogUtils.logBusiness("GET_CONVERSATIONS", username, "会话ID %s 在Redis中找不到对应的历史记录", conversationId);
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS_EMPTY");
            monitor.end("获取对话历史成功（空结果）");
        }

        // 构建统一响应格式
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取对话历史成功");
        response.put("data", formattedConversations);
        return ResponseEntity.ok().body(response);
    }

    /**
     * 解析日期时间字符串，支持多种格式
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            // 尝试标准格式解析 (2023-01-01T12:00:00)
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                // 尝试解析不带秒的格式 (2023-01-01T12:00)
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }

                // 尝试解析不带分钟和秒的格式 (2023-01-01T12)
                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }

                // 尝试解析日期格式 (2023-01-01)
                if (dateTimeStr.length() == 10) {
                    return LocalDateTime.parse(dateTimeStr + "T00:00:00");
                }

                // 如果以上都失败，尝试使用自定义格式解析
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
                return LocalDateTime.parse(dateTimeStr, formatter);
            } catch (Exception e2) {
                LogUtils.logBusinessError("PARSE_DATETIME", "system", "无法解析日期时间: %s", e2, dateTimeStr);
                throw new CustomException("无效的日期格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }
    }

}