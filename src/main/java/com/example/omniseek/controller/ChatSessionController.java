package com.example.omniseek.controller;

import com.example.omniseek.model.ChatSession;
import com.example.omniseek.service.ChatSessionService;
import com.example.omniseek.utils.JwtUtils;
import com.example.omniseek.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/chat-sessions")
public class ChatSessionController {

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping
    public ResponseEntity<?> createSession(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("CREATE_SESSION");
        String username = null;
        try {
            username = extractUsername(token);
            LogUtils.logBusiness("CREATE_SESSION", username, "用户请求创建新会话");

            ChatSession session = chatSessionService.createSession(username);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "创建会话成功");
            response.put("data", convertToSessionDTO(session));

            LogUtils.logUserOperation(username, "CREATE_SESSION", "session_creation", "SUCCESS");
            monitor.end("创建会话成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("CREATE_SESSION", username, "创建会话失败: %s", e, e.getMessage());
            monitor.end("创建会话失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "创建会话失败: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getSessions(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_SESSIONS");
        String username = null;
        try {
            username = extractUsername(token);
            LogUtils.logBusiness("GET_SESSIONS", username, "用户请求获取会话列表");

            List<ChatSession> sessions = chatSessionService.getUserSessions(username);
            LogUtils.logBusiness("GET_SESSIONS", username, "查询到 %d 个会话", sessions.size());

            // 打印每个会话的详情
            for (ChatSession session : sessions) {
                LogUtils.logBusiness("GET_SESSIONS", username, "会话: sessionId=%s, title=%s, messageCount=%d",
                        session.getSessionId(), session.getTitle(), session.getMessageCount());
            }

            List<Map<String, Object>> sessionDTOs = sessions.stream()
                    .map(this::convertToSessionDTO)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取会话列表成功");
            response.put("data", sessionDTOs);

            // 打印完整的响应内容
            LogUtils.logBusiness("GET_SESSIONS", username, "返回响应: %s", response.toString());

            LogUtils.logUserOperation(username, "GET_SESSIONS", "session_list", "SUCCESS");
            monitor.end("获取会话列表成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SESSIONS", username, "获取会话列表失败: %s", e, e.getMessage());
            monitor.end("获取会话列表失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "获取会话列表失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@RequestHeader("Authorization") String token,
            @PathVariable String sessionId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_SESSION");
        String username = null;
        try {
            username = extractUsername(token);
            LogUtils.logBusiness("GET_SESSION", username, "用户请求获取会话详情: %s", sessionId);

            ChatSession session = chatSessionService.getSession(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取会话详情成功");
            response.put("data", convertToSessionDTO(session));

            LogUtils.logUserOperation(username, "GET_SESSION", "session_detail", "SUCCESS");
            monitor.end("获取会话详情成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_SESSION", username, "获取会话详情失败: %s", e, e.getMessage());
            monitor.end("获取会话详情失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "获取会话详情失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{sessionId}/title")
    public ResponseEntity<?> updateSessionTitle(@RequestHeader("Authorization") String token,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("UPDATE_SESSION_TITLE");
        String username = null;
        try {
            username = extractUsername(token);
            String title = request.get("title");
            LogUtils.logBusiness("UPDATE_SESSION_TITLE", username, "用户请求更新会话标题: %s -> %s", sessionId, title);

            ChatSession session = chatSessionService.updateSessionTitle(sessionId, title);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "更新会话标题成功");
            response.put("data", convertToSessionDTO(session));

            LogUtils.logUserOperation(username, "UPDATE_SESSION_TITLE", "title_update", "SUCCESS");
            monitor.end("更新会话标题成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_SESSION_TITLE", username, "更新会话标题失败: %s", e, e.getMessage());
            monitor.end("更新会话标题失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "更新会话标题失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(@RequestHeader("Authorization") String token,
            @PathVariable String sessionId) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_SESSION");
        String username = null;
        try {
            username = extractUsername(token);
            LogUtils.logBusiness("DELETE_SESSION", username, "用户请求删除会话: %s", sessionId);

            chatSessionService.deleteSession(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "删除会话成功");

            LogUtils.logUserOperation(username, "DELETE_SESSION", "session_deletion", "SUCCESS");
            monitor.end("删除会话成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_SESSION", username, "删除会话失败: %s", e, e.getMessage());
            monitor.end("删除会话失败: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "删除会话失败: " + e.getMessage()));
        }
    }

    private String extractUsername(String token) {
        return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
    }

    private Map<String, Object> convertToSessionDTO(ChatSession session) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("sessionId", session.getSessionId());
        dto.put("title", session.getTitle());
        dto.put("createdAt", session.getCreatedAt());
        dto.put("updatedAt", session.getUpdatedAt());
        dto.put("messageCount", session.getMessageCount());
        return dto;
    }
}
