package com.example.omniseek.router;

import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface RouteHandler {
    RouteType getRouteType();

    void handle(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError);
}
