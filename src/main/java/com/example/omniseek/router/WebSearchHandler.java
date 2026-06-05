package com.example.omniseek.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import com.example.omniseek.client.DeepSeekClient;
import com.example.omniseek.utils.SearchUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class WebSearchHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSearchHandler.class);

    @Autowired
    private SearchUtils searchUtils;

    @Override
    public RouteType getRouteType() {
        return RouteType.WEB_SEARCH;
    }

    private final DeepSeekClient deepSeekClient;

    public WebSearchHandler(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
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
            logger.info("联网搜索路由处理: userId={}, message={}", userId, userMessage);

            List<Map<String, String>> searchResults = searchUtils.tavilySearch(userMessage);
            if (searchResults == null || searchResults.isEmpty()) {
                String fallbackMsg = "抱歉，未找到与您问题相关的网络信息。\n\n您的问题是：" + userMessage;
                onChunk.accept(fallbackMsg);
                return;
            }
            // 3. 构建搜索结果上下文（限制总长度，避免超出 token 限制）
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("以下是来自互联网的搜索结果，请基于这些结果回答用户的问题。\n\n");
            int maxLength = 4000; // 可根据实际模型限制调整
            int currentLength = 0;
            for (int i = 0; i < searchResults.size(); i++) {
                Map<String, String> item = searchResults.get(i);
                String title = cleanText(item.getOrDefault("title", "无标题"));
                String url = cleanText(item.getOrDefault("url", ""));
                String content = cleanText(item.getOrDefault("content", ""));

                String resultBlock = String.format("【结果 %d】\n标题: %s\n链接: %s\n内容: %s\n\n",
                        i + 1, title, url, content);

                if (currentLength + resultBlock.length() > maxLength) {
                    contextBuilder.append("...(更多结果已省略)");
                    break;
                }
                contextBuilder.append(resultBlock);
                currentLength += resultBlock.length();
            }
            String context = contextBuilder.toString();
            // 4. 调用 DeepSeekClient 流式生成最终答案
            // 这里将搜索结果作为上下文，不额外传递历史消息（或根据需求传递）
            deepSeekClient.streamResponse(
                    userMessage,
                    context,
                    null, // 如果需要多轮对话历史，可以传；否则传 null 或空 List
                    onChunk,
                    onError,
                    onComplete);

        } catch (Exception e) {
            logger.error("联网搜索路由处理失败", e);
            onError.accept(e);
        }
    }

    /**
     * 清理文本：去除首尾空格、JSON 引号等
     */
    private String cleanText(String text) {
        if (text == null)
            return "";
        // 去除外层引号（如果是由 toString() 产生的）
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }
}
