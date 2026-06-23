package com.example.omniseek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class QueryRewriterService {

        private static final Logger logger = LoggerFactory.getLogger(QueryRewriterService.class);

        private final WebClient webClient;

        private final String apiKey;
        private final String model;
        private final ObjectMapper objectMapper;

        @Value("${ai.hyde.prompt-template}")
        private String hydePromptTemplate;

        @Value("${ai.expand-query.prompt-template}")
        private String expandPromptTemplate;

        public QueryRewriterService(@Value("${deepseek.api.url}") String apiUrl,
                        @Value("${deepseek.api.key}") String apiKey,
                        @Value("${deepseek.api.model}") String model) {
                this.apiKey = apiKey;
                this.model = model;
                this.objectMapper = new ObjectMapper();

                // 构建 WebClient
                WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                        builder.defaultHeader("Authorization", "Bearer " + apiKey);
                }
                this.webClient = builder.build();
        }

        /**
         * 生成假设文档（异步，返回 Mono）
         */
        public Mono<String> generateHypotheticalAnswer(String originalQuery) {
                String prompt = String.format(hydePromptTemplate, originalQuery);

                Map<String, Object> requestBody = Map.of(
                                "model", model,
                                "messages", List.of(
                                                Map.of("role", "system", "content", "你是一位专业的文档撰写专家。"),
                                                Map.of("role", "user", "content", prompt)),
                                "temperature", 0.2,
                                "max_tokens", 500,
                                "stream", false);

                return webClient.post()
                                .uri("/chat/completions")
                                .bodyValue(requestBody)
                                .retrieve()
                                .bodyToMono(String.class)
                                .map(this::extractContent)
                                .doOnSuccess(content -> logger.debug("假设文档生成成功，长度：{}", content.length()))
                                .onErrorResume(e -> {
                                        logger.error("生成假设文档失败，降级使用原始查询。错误：{}", e.getMessage());
                                        return Mono.just(originalQuery); // 降级：返回原始问题
                                });
        }

        /**
         * 同步阻塞版本（适用于传统 MVC 环境）
         */
        public String generateHypotheticalAnswerBlocking(String originalQuery) {
                return generateHypotheticalAnswer(originalQuery).block();
        }

        /**
         * 扩展查询：生成多个子问题（同步阻塞，带降级）
         * 
         * @param originalQuery 原始问题
         * @return 子问题列表，若失败则仅包含原始问题
         */
        public List<String> rewriteQuery(String originalQuery) {
                String prompt = String.format(expandPromptTemplate, originalQuery);

                Map<String, Object> requestBody = Map.of(
                                "model", model,
                                "messages", List.of(
                                                Map.of("role", "system", "content", "你是一个专业的查询改写助手。"),
                                                Map.of("role", "user", "content", prompt)),
                                "temperature", 0.3,
                                "max_tokens", 150,
                                "stream", false);

                try {
                        String response = webClient.post()
                                        .uri("/chat/completions")
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .block();

                        if (response != null) {
                                String content = extractContent(response);
                                if (content != null && !content.isEmpty()) {
                                        // 按行分割，过滤空行
                                        List<String> queries = content.lines()
                                                        .map(String::trim)
                                                        .filter(line -> !line.isEmpty() && line.endsWith("?"))
                                                        .toList();

                                        if (!queries.isEmpty()) {
                                                logger.info("查询扩展成功：{} -> {}", originalQuery, queries);
                                                return queries;
                                        }
                                }
                        }
                } catch (Exception e) {
                        logger.error("查询扩展失败，降级为原始查询", e);
                }

                // 降级：返回包含原始问题的单元素列表
                return List.of(originalQuery);
        }

        /**
         * 从响应 JSON 中提取 content
         */
        private String extractContent(String responseBody) {
                try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                                String content = choices.get(0)
                                                .path("message")
                                                .path("content")
                                                .asText();
                                if (content != null && !content.isEmpty()) {
                                        return content;
                                }
                        }
                        throw new RuntimeException("DeepSeek API 返回内容为空");
                } catch (Exception e) {
                        logger.error("解析响应失败：{}", responseBody, e);
                        throw new RuntimeException("解析 DeepSeek 响应失败", e);
                }
        }
}
