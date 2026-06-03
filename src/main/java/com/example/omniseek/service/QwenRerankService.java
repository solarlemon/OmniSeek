package com.example.omniseek.service;

import com.example.omniseek.entity.SearchResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class QwenRerankService {
    @Value("${rerank.api.key}")
    private String apiKey;

    @Value("${rerank.api.url}")
    private String RERANK_API_URL;

    @Value("${rerank.api.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 执行重排序
     *
     * @param query      用户查询语句
     * @param candidates 待排序的SearchResult列表
     * @param topN       返回前N条
     * @return 精排后的结果（按相关性从高到低）
     */
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);

        List<String> documents = new ArrayList<>();
        for (SearchResult result : candidates) {
            documents.add(result.getTextContent());
        }
        input.put("documents", documents);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", true);
        parameters.put("top_n", topN);

        request.put("input", input);
        request.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        // 调用
        ResponseEntity<QwenRerankResponse> response = restTemplate.exchange(
                RERANK_API_URL,
                HttpMethod.POST,
                entity,
                QwenRerankResponse.class);

        // 解析响应
        QwenRerankResponse body = response.getBody();
        if (body == null || body.getOutput() == null || body.getOutput().getResults() == null) {
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        List<QwenRerankResult> results = body.getOutput().getResults();

        List<SearchResult> rankedList = new ArrayList<>();
        for (QwenRerankResult res : results) {
            SearchResult sr = candidates.get(res.getIndex());
            sr.setScore(res.getRelevanceScore());
            rankedList.add(sr);
        }

        return rankedList;
    }

    // 响应VO
    @Data
    public static class QwenRerankResponse {
        private Output output;

        @Data
        public static class Output {
            private List<QwenRerankResult> results;
        }
    }

    @Data
    public static class QwenRerankResult {
        private Integer index;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
    }
}
