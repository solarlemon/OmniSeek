package com.example.omniseek.router;

import com.example.omniseek.mcp.core.ToolCallingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MCP 工具调用路由处理器
 * <p>
 * 处理知识库统计、系统信息查询等需要调用 MCP 工具的问题。
 * 直接走 ToolCallingService 两阶段流程，不经过 RAG 检索。
 * </p>
 */
@Component
public class ToolCallingHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(ToolCallingHandler.class);

    private final ToolCallingService toolCallingService;

    public ToolCallingHandler(ToolCallingService toolCallingService) {
        this.toolCallingService = toolCallingService;
    }

    @Override
    public RouteType getRouteType() {
        return RouteType.TOOL_CALLING;
    }

    @Override
    public void handle(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        try {
            logger.info("工具调用路由处理: userId={}, message={}", userId, userMessage);
            toolCallingService.streamWithTools(userMessage, "", history, onChunk, onError, onComplete);
        } catch (Exception e) {
            logger.error("工具调用路由处理失败", e);
            onError.accept(e);
        }
    }
}
