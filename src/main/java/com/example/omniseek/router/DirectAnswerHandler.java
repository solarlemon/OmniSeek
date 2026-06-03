package com.example.omniseek.router;

import com.example.omniseek.client.DeepSeekClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class DirectAnswerHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(DirectAnswerHandler.class);

    private final DeepSeekClient deepSeekClient;

    public DirectAnswerHandler(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    @Override
    public RouteType getRouteType() {
        return RouteType.DIRECT_ANSWER;
    }

    @Override
    public void handle(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {
        try {
            logger.info("直接回答路由处理: userId={}, message={}", userId, userMessage);

            // 直接调用 LLM，不传入检索上下文
            deepSeekClient.streamResponse(userMessage, "", history, onChunk, onError);

        } catch (Exception e) {
            logger.error("直接回答路由处理失败", e);
            onError.accept(e);
        }
    }
}
