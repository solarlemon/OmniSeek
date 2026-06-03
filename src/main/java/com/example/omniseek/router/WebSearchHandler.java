package com.example.omniseek.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class WebSearchHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchHandler.class);

    @Override
    public RouteType getRouteType() {
        return RouteType.WEB_SEARCH;
    }

    @Override
    public void handle(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {
        try {
            logger.info("联网搜索路由处理: userId={}, message={}", userId, userMessage);

            // TODO: 这里需要集成真实的联网搜索 API（如 Bing Search API、Google Search API 等）
            String response = "抱歉，联网搜索功能正在开发中，暂时无法使用。\n" +
                    "您的搜索请求：" + userMessage;
            onChunk.accept(response);

        } catch (Exception e) {
            logger.error("联网搜索路由处理失败", e);
            onError.accept(e);
        }
    }
}
