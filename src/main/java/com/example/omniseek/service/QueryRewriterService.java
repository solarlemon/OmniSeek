package com.example.omniseek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        private final Duration readTimeout;
        private final ObjectMapper objectMapper;

        @Value("${ai.hyde.prompt-template}")
        private String hydePromptTemplate;

        @Value("${ai.hyde.decision-prompt-template}")
        private String hydeDecisionPromptTemplate;

        @Value("${ai.expand-query.prompt-template}")
        private String expandPromptTemplate;

        public QueryRewriterService(@Value("${sub_model.api.url}") String apiUrl,
                        @Value("${sub_model.api.key}") String apiKey,
                        @Value("${sub_model.api.model}") String model,
                        @Value("${sub_model.timeout.read:60}") long readTimeoutSeconds) {
                this.apiKey = apiKey;
                this.model = model;
                this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
                this.objectMapper = new ObjectMapper();

                // 构建 WebClient，设置读取超时
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
                                .timeout(readTimeout)
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
                                "max_tokens", 300,
                                "stream", false);

                try {
                        String response = webClient.post()
                                        .uri("/chat/completions")
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .timeout(readTimeout)
                                        .block();

                        if (response != null) {
                                String content = extractContent(response);
                                if (content != null && !content.isEmpty()) {
                                        // 按行分割，过滤空行
                                        // 同时支持中英文问号和陈述句格式，提高容错性
                                        List<String> queries = content.lines()
                                                        .map(String::trim)
                                                        .filter(line -> !line.isEmpty())
                                                        // 支持问句形式（中英文问号）或陈述句形式
                                                        .filter(line -> line.endsWith("?")
                                                                        || line.endsWith("？")
                                                                        || line.length() > 2)
                                                        .map(line -> {
                                                                // 去除开头序号（1. 2. 3. 等）
                                                                return line.replaceFirst("^[\\d]+[.、]\\s*", "")
                                                                                .replaceFirst("^[\\d]+[)]", "");
                                                        })
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
         * 优先取 content，为空时依次回退 reasoning_content（硅基流动 Qwen）和 reasoning（sensenova）
         */
        private String extractContent(String responseBody) {
                try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                                JsonNode message = choices.get(0).path("message");
                                // 优先取 content
                                String content = message.path("content").asText();
                                if (content != null && !content.isEmpty()) {
                                        return content;
                                }
                                // 回退到 reasoning_content（硅基流动上的 Qwen 等推理模型）
                                String reasoningContent = message.path("reasoning_content").asText();
                                if (reasoningContent != null && !reasoningContent.isEmpty()) {
                                        return reasoningContent;
                                }
                                // 回退到 reasoning（sensenova 等模型）
                                String reasoning = message.path("reasoning").asText();
                                if (reasoning != null && !reasoning.isEmpty()) {
                                        return reasoning;
                                }
                        }
                        throw new RuntimeException("API 返回内容为空");
                } catch (Exception e) {
                        logger.error("解析响应失败：{}", responseBody, e);
                        throw new RuntimeException("解析响应失败", e);
                }
        }

        /**
         * 缓存：query -> HyDE 结果
         */
        private final ConcurrentHashMap<String, String> hydeCache = new ConcurrentHashMap<>();

        /**
         * 使用 sub_model（轻量模型）智能判断是否应该使用 HyDE
         * 通过 LLM 分析查询语义，判断是否需要语义扩展
         * 替代原来的硬编码字符数判断
         *
         * @param query 原始查询
         * @return true 表示应该使用 HyDE，false 表示直接使用原查询
         */
        public boolean shouldUseHyDE(String query) {
                if (query == null || query.trim().isEmpty()) {
                        return false;
                }

                String trimmed = query.trim();

                // 极端短查询（1-2 字符）直接使用 HyDE，无需调用 LLM
                if (trimmed.length() <= 2) {
                        logger.debug("查询极短（{} 字符），直接使用 HyDE: {}", trimmed.length(), query);
                        return true;
                }

                try {
                        String prompt = String.format(hydeDecisionPromptTemplate, query);

                        Map<String, Object> requestBody = Map.of(
                                        "model", model,
                                        "messages", List.of(
                                                        Map.of("role", "system", "content",
                                                                        "你是一个精确的查询分析专家。"),
                                                        Map.of("role", "user", "content", prompt)),
                                        "temperature", 0.1,
                                        "max_tokens", 200,
                                        "stream", false);

                        String response = webClient.post()
                                        .uri("/chat/completions")
                                        .bodyValue(requestBody)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .timeout(readTimeout)
                                        .block();

                        if (response != null) {
                                String content = extractContent(response);
                                boolean shouldHyde = "YES".equalsIgnoreCase(content.trim());
                                logger.info("HyDE 决策 - query: {}, LLM 判断: {} => {}",
                                                query, content.trim(), shouldHyde ? "使用 HyDE" : "不使用 HyDE");
                                return shouldHyde;
                        }
                } catch (Exception e) {
                        logger.warn("HyDE 决策 LLM 调用失败，降级使用长度判断", e);
                }

                // 降级策略：长度 >= 30 字符的完整问句不需要 HyDE，否则需要
                boolean fallback = trimmed.length() < 30;
                logger.debug("HyDE 决策降级，query 长度 {}，{}", trimmed.length(),
                                fallback ? "使用 HyDE" : "不使用 HyDE");
                return fallback;
        }

        /**
         * 根据 query 长度决定使用 HyDE 还是原查询
         * - 如果 query 过短，使用 HyDE 生成假设性答案
         * - 否则直接返回原查询
         *
         * @param query 原始查询
         * @return 用于搜索的查询文本（HyDE 结果或原查询）
         */
        public String getSearchQuery(String query) {
                if (shouldUseHyDE(query)) {
                        // HyDE 结果使用缓存，避免重复生成
                        return hydeCache.computeIfAbsent(query, q -> generateHypotheticalAnswerBlocking(q));
                }
                logger.debug("查询长度足够，直接使用原查询: {}", query);
                return query;
        }
}
