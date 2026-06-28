package com.example.omniseek.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.example.omniseek.service.IntentRouterService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class RouteManager {
    private static final Logger logger = LoggerFactory.getLogger(RouteManager.class);

    private final IntentRouterService intentRouter;
    private final Map<RouteType, RouteHandler> handlers = new HashMap<>();

    public RouteManager(IntentRouterService intentRouter,
            KnowledgeBaseHandler knowledgeBaseHandler,
            ToolCallingHandler toolCallingHandler,
            DirectAnswerHandler directAnswerHandler) {
        this.intentRouter = intentRouter;

        // 注册所有路由处理器
        handlers.put(RouteType.KNOWLEDGE_BASE, knowledgeBaseHandler);
        handlers.put(RouteType.TOOL_CALLING, toolCallingHandler);
        handlers.put(RouteType.DIRECT_ANSWER, directAnswerHandler);
    }

    public void route(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        // 1. 识别意图
        RouteType routeType = intentRouter.route(userMessage);
        logger.info("路由决策: {} -> {}", userMessage, routeType);

        // 2. 获取对应处理器
        RouteHandler handler = handlers.get(routeType);
        if (handler == null) {
            logger.warn("未找到处理器，使用默认直接回答");
            handler = handlers.get(RouteType.DIRECT_ANSWER);
        }

        // 3. 处理请求
        handler.handle(userId, userMessage, history, session, onChunk, onError, onComplete);
    }
}
