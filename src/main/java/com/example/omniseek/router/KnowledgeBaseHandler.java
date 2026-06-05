package com.example.omniseek.router;

import com.example.omniseek.client.DeepSeekClient;
import com.example.omniseek.dto.SearchResult;
import com.example.omniseek.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class KnowledgeBaseHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseHandler.class);

    private final HybridSearchService searchService;
    private final DeepSeekClient deepSeekClient;

    public KnowledgeBaseHandler(HybridSearchService searchService, DeepSeekClient deepSeekClient) {
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
    }

    @Override
    public RouteType getRouteType() {
        return RouteType.KNOWLEDGE_BASE;
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
            logger.info("知识库路由处理: userId={}, message={}", userId, userMessage);

            // 执行带权限的混合搜索
            List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);
            logger.debug("搜索结果数量: {}", searchResults.size());

            // 构建上下文
            String context = buildContext(searchResults);

            // 调用 DeepSeek 生成回复
            deepSeekClient.streamResponse(userMessage, context, history, onChunk, onError, onComplete);

        } catch (Exception e) {
            logger.error("知识库路由处理失败", e);
            onError.accept(e);
        }
    }

    private String buildContext(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return "";
        }

        final int MAX_SNIPPET_LEN = 300;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String snippet = result.getTextContent();
            if (snippet.length() > MAX_SNIPPET_LEN) {
                snippet = snippet.substring(0, MAX_SNIPPET_LEN) + "…";
            }
            String fileLabel = result.getFileName() != null ? result.getFileName() : "unknown";
            context.append(String.format("[%d] (%s) %s\n", i + 1, fileLabel, snippet));
        }
        return context.toString();
    }
}
