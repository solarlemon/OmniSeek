package com.example.omniseek.mcp.tools;

import com.example.omniseek.mcp.core.BuiltinToolProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 网络搜索工具
 * 基于 Tavily API 实现，替代原有的 WebSearchHandler
 */
@Slf4j
@Component
public class WebSearchTool implements BuiltinToolProvider {

    @Value("${web-search.api.url}")
    private String baseUrl;

    @Value("${web-search.api.key}")
    private String apiKey;

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public WebSearchTool() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Tool("web_search")
    public String webSearch(String query) {
        log.info("MCP 工具: 执行网络搜索，查询={}", query);
        List<Map<String, String>> results;
        try {
            results = tavilySearch(query);
        } catch (Exception e) {
            log.error("网络搜索失败", e);
            return "网络搜索失败: " + e.getMessage();
        }

        if (results == null || results.isEmpty()) {
            return "未找到与 '" + query + "' 相关的网络信息";
        }

        final int MAX_RESULTS = 5;
        final int MAX_TOTAL_CHARS = 4000;
        StringBuilder resultBuilder = new StringBuilder();
        int charCount = 0;

        for (int i = 0; i < Math.min(results.size(), MAX_RESULTS); i++) {
            Map<String, String> item = results.get(i);
            String title = clean(item.getOrDefault("title", "无标题"));
            String url = clean(item.getOrDefault("url", ""));
            String content = clean(item.getOrDefault("content", ""));

            String block = String.format("[%d] %s\n链接: %s\n摘要: %s\n\n",
                    i + 1, title, url, content);

            if (charCount + block.length() > MAX_TOTAL_CHARS) {
                resultBuilder.append("...(更多结果已省略)");
                break;
            }
            resultBuilder.append(block);
            charCount += block.length();
        }

        return resultBuilder.toString();
    }

    @Override
    public String getToolName() {
        return "web_search";
    }

    @Override
    public String getDisplayName() {
        return "网络搜索";
    }

    @Override
    public String getDescription() {
        return "通过网络搜索获取最新信息";
    }

    private List<Map<String, String>> tavilySearch(String query) throws Exception {
        List<Map<String, String>> results = new ArrayList<>();

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("query", query);

        Request request = new Request.Builder()
                .url(baseUrl)
                .post(RequestBody.create(MediaType.parse("application/json"),
                        objectMapper.writeValueAsString(requestBody)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Tavily 搜索请求失败: " + response.code());
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode jsonNode = root.get("results");

            if (jsonNode != null && !jsonNode.isEmpty()) {
                jsonNode.forEach(data -> {
                    Map<String, String> processedResult = new HashMap<>();
                    processedResult.put("title", data.has("title") ? data.get("title").asText() : "无标题");
                    processedResult.put("url", data.has("url") ? data.get("url").asText() : "");
                    processedResult.put("content", data.has("content") ? data.get("content").asText() : "");
                    results.add(processedResult);
                });
            }
        }
        return results;
    }

    private String clean(String text) {
        if (text == null) return "";
        if (text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }
}
