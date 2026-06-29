package com.example.omniseek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class LLMIntentClassifier {

    private static final Logger logger = LoggerFactory.getLogger(LLMIntentClassifier.class);
    private final WebClient webClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final long timeoutSeconds;

    @Value("${ai.intent.prompt-template}")
    private String intentPromptTemplate;

    public LLMIntentClassifier(@Value("${sub_model.api.url}") String apiUrl,
            @Value("${sub_model.api.key}") String apiKey,
            @Value("${sub_model.api.model}") String model,
            @Value("${ai.intent.llm.timeout-seconds:5}") long timeoutSeconds) {
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.timeoutSeconds = timeoutSeconds;

        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    /**
     * 调用 LLM 进行意图分类（同步阻塞，带超时）
     * 
     * @param userMessage 用户问题
     * @return 意图类别字符串，失败时返回 null
     */
    public String classifyIntent(String userMessage) {
        String prompt = String.format(intentPromptTemplate, userMessage);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个专业的意图分类助手，只输出分类结果。"),
                        Map.of("role", "user", "content", prompt)),
                "temperature", 0.1, // 极低温度确保稳定性
                "max_tokens", 20,
                "stream", false);

        try {
            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block(); // 此处阻塞，因为是同步调用

            if (response != null) {
                String intent = extractIntent(response);
                if (isValidIntent(intent)) {
                    logger.info("LLM 意图识别结果：{} -> {}", userMessage, intent);
                    return intent;
                }
            }
        } catch (Exception e) {
            logger.error("LLM 意图识别失败，将返回 null", e);
        }
        return null;
    }

    private String extractIntent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText("")
                    .trim()
                    .toUpperCase();
            // 移除可能的标点或换行
            return content.replaceAll("[^A-Z_]", "");
        } catch (Exception e) {
            logger.warn("解析 LLM 响应失败", e);
            return null;
        }
    }

    private boolean isValidIntent(String intent) {
        return intent != null && (intent.equals("KNOWLEDGE_BASE") ||
                intent.equals("WEB_SEARCH") ||
                intent.equals("DIRECT_ANSWER"));
    }
}