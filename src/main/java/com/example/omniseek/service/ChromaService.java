package com.example.omniseek.service;

import com.example.omniseek.entity.ChromaDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class ChromaService {

    private static final Logger logger = LoggerFactory.getLogger(ChromaService.class);

    @Autowired
    private WebClient chromaWebClient; // 注入配置好的 WebClient

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Value("${chroma.collection:knowledge_base}")
    private String collectionName;

    @Value("${embedding.api.dimension:2048}")
    private int embeddingDimension;

    private String collectionId; // Chroma数据库集合的 ID

    /**
     * 应用启动时初始化 Chroma 连接检查
     */
    @PostConstruct
    public void init() {
        logger.info("========================================");
        logger.info("正在检查 Chroma 连接...");
        logger.info("Chroma 地址: {}", chromaUrl);
        logger.info("集合名称: {}", collectionName);
        logger.info("使用配置好的 WebClient（含超时设置）");
        logger.info("========================================");

        try {
            // 测试心跳 - 使用注入的 chromaWebClient
            String heartbeat = chromaWebClient
                    .get()
                    .uri("/api/v1/heartbeat")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30)); // 也给足够的时间
            logger.info("✅ Chroma 连接成功！Heartbeat: {}", heartbeat);
            // 初始化集合
            initializeCollection();

        } catch (Exception e) {
            logger.error("❌ Chroma 连接失败！");
            logger.error("请确认:");
            logger.error("  1. Chroma 服务是否已启动在: {}", chromaUrl);
            logger.error("  2. 网络是否可访问");
            logger.error("错误信息: {}", e.getMessage());
            // 不抛出异常，让应用继续启动（但功能会受限）
        }
    }

    /**
     * 初始化 Chroma 集合
     */
    public void initializeCollection() {
        try {
            logger.info("初始化 Chroma 集合: {}", collectionName);
            // 1. 获取集合（用名称获取，返回的是完整信息，包含 id）
            Map<String, Object> collection = getCollectionByName();
            if (collection != null) {
                // 2. 取出真正的 UUID（不是名称！）
                this.collectionId = (String) collection.get("id");
                logger.info("集合 {} 已存在，ID: {}", collectionName, collectionId);
                return;
            }

            // 3. 不存在则创建，并保存 UUID
            createCollection();
        } catch (Exception e) {
            logger.error("初始化 Chroma 集合失败", e);
            throw new RuntimeException("初始化 Chroma 集合失败", e);
        }
    }

    /**
     * 创建集合
     */
    private void createCollection() {
        try {
            ClassPathResource resource = new ClassPathResource("chroma-mappings/knowledge_base.json");
            String configJson = FileCopyUtils
                    .copyToString(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
            JsonNode config = objectMapper.readTree(configJson);
            // 类型安全写法，无警告
            ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<Map<String, Object>>() {
            };
            Map<String, Object> result = WebClient.create(chromaUrl)
                    .post()
                    .uri("/api/v1/collections")
                    .header("Content-Type", "application/json")
                    .bodyValue(config)
                    .retrieve()
                    .bodyToMono(typeRef)
                    .block(Duration.ofSeconds(10));
            this.collectionId = (String) result.get("id");
            logger.info("Chroma 集合 {} 创建成功，UUID: {}", collectionName, collectionId);
        } catch (Exception e) {
            logger.error("创建集合失败", e);
            throw new RuntimeException("创建集合失败", e);
        }
    }

    private Map<String, Object> getCollectionByName() {
        try {
            // 重点：接口返回的是 数组 List<Map>，不是 Map！
            List<Map<String, Object>> collections = chromaWebClient
                    .get()
                    .uri("/api/v1/collections")
                    .retrieve()
                    // 关键修复：用泛型引用安全接收 List<Map<String,Object>>
                    .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .collectList()
                    .block();

            // 打印看看返回了什么
            System.out.println("=== 查到的所有集合：" + collections);

            // 直接过滤匹配
            return collections.stream()
                    .filter(c -> c.get("name").toString().trim()
                            .equals(collectionName.trim()))
                    .findFirst()
                    .orElse(null);

        } catch (Exception e) {
            // 打印错误，方便排查
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 批量添加文档到 Chroma
     */
    public void bulkAddDocuments(List<ChromaDocument> documents) {
        try {
            logger.info("开始批量添加文档到 Chroma, 数量: {}", documents.size());
            if (documents.isEmpty()) {
                logger.warn("文档列表为空，跳过添加");
                return;
            }
            // 1. 构建请求体（严格 v1 结构）
            ObjectNode requestBody = objectMapper.createObjectNode();

            ArrayNode idsNode = requestBody.putArray("ids");
            documents.forEach(doc -> idsNode.add(doc.getId()));

            ArrayNode embeddingsNode = requestBody.putArray("embeddings");
            documents.forEach(doc -> {
                ArrayNode vectorNode = embeddingsNode.addArray();
                for (float v : doc.getEmbedding()) {
                    vectorNode.add(v);
                }
            });

            ArrayNode metadatasNode = requestBody.putArray("metadatas");
            documents.forEach(doc -> {
                ObjectNode metadataNode = metadatasNode.addObject();
                metadataNode.put("fileMd5", doc.getFileMd5() == null ? "" : doc.getFileMd5());
                metadataNode.put("chunkId", doc.getChunkId() == null ? null : doc.getChunkId());
                metadataNode.put("userId", doc.getUserId() == null ? null : doc.getUserId());
                metadataNode.put("orgTag", doc.getOrgTag() == null ? null : doc.getOrgTag());
                metadataNode.put("isPublic", doc.isPublic());
                metadataNode.put("textContent", doc.getTextContent() == null ? null : doc.getTextContent());
            });

            // 2. 【关键】打印真实 JSON，复制到 Postman 测试
            // String json =
            // objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            // logger.info("发送到 Chroma 的请求体:\n{}", json);

            // 3. 发送请求并捕获 400 响应体
            String response = WebClient.create(chromaUrl)
                    .post()
                    .uri("/api/v1/collections/{collectionId}/add", collectionId)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), res -> res.bodyToMono(String.class)
                            .flatMap(msg -> {
                                logger.error("Chroma 400 响应体: {}", msg);
                                return Mono.error(new RuntimeException("Chroma 400: " + msg));
                            }))
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            logger.info("成功添加 {} 个文档到 Chroma，响应: {}", documents.size(), response);
        } catch (Exception e) {
            logger.error("批量添加文档到 Chroma 失败", e);
            throw new RuntimeException("批量添加文档到 Chroma 失败", e);
        }
    }

    /**
     * 搜索向量
     */
    public List<ChromaSearchResponse> search(float[] queryEmbedding, int topK,
            String userId, List<String> userEffectiveTags) {
        try {
            logger.debug("开始 Chroma 向量搜索, topK: {}", topK);

            ObjectNode requestBody = objectMapper.createObjectNode();

            // query_embeddings
            ArrayNode queryEmbeddingsNode = requestBody.putArray("query_embeddings");
            ArrayNode queryVectorNode = queryEmbeddingsNode.addArray();
            for (float v : queryEmbedding) {
                queryVectorNode.add(v);
            }

            requestBody.put("n_results", topK);

            ObjectNode whereNode = requestBody.putObject("where");
            ArrayNode orArray = whereNode.putArray("$or");

            // 条件1: 公开文档
            orArray.addObject().put("isPublic", true);

            // 条件2: 用户自己的文档
            if (userId != null && !userId.isEmpty()) {
                orArray.addObject().put("userId", userId);
            }

            // 条件3: 组织标签 $in
            if (userEffectiveTags != null && !userEffectiveTags.isEmpty()) {
                ObjectNode tagCond = orArray.addObject();
                ObjectNode inNode = tagCond.putObject("orgTag");
                ArrayNode inArray = inNode.putArray("$in");
                userEffectiveTags.forEach(inArray::add);
            }

            // 发送请求
            String response = WebClient.create(chromaUrl)
                    .post()
                    .uri("/api/v1/collections/{collectionId}/query", collectionId)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            return parseSearchResponse(response);
        } catch (Exception e) {
            logger.error("Chroma 向量搜索失败", e);
            throw new RuntimeException("Chroma 向量搜索失败", e);
        }
    }

    /**
     * 解析搜索响应
     */
    private List<ChromaSearchResponse> parseSearchResponse(String response) {
        List<ChromaSearchResponse> results = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode idsArray = root.path("ids").get(0); // 第一层结果集
            JsonNode distancesArray = root.path("distances").get(0);
            JsonNode metadatasArray = root.path("metadatas").get(0);

            // 遍历每个匹配项
            for (int i = 0; i < idsArray.size(); i++) {
                ChromaSearchResponse result = new ChromaSearchResponse();

                // ID & 距离
                result.setId(idsArray.get(i).asText());
                double distance = distancesArray.get(i).asDouble();
                result.setDistance((float) distance);

                JsonNode metadata = metadatasArray.get(i);
                result.setTextContent(metadata.path("textContent").asText());

                // 其他字段
                result.setFileMd5(metadata.path("fileMd5").asText());
                result.setChunkId(metadata.path("chunkId").asInt());

                // 距离 → 相似度（0 ≤ distance ≤ 2）
                result.setScore((float) (1 - distance / 2));

                results.add(result);
            }

        } catch (Exception e) {
            logger.error("解析 Chroma 搜索响应失败", e);
        }

        return results;
    }

    /**
     * 根据 fileMd5 删除文档
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            logger.info("从 Chroma 删除文档, fileMd5: {}", fileMd5);

            ObjectNode requestBody = objectMapper.createObjectNode();
            ObjectNode whereNode = requestBody.putObject("where");
            whereNode.put("fileMd5", fileMd5);

            WebClient.create(chromaUrl)
                    .post()
                    .uri("/api/v1/collections/{collectionId}/delete", collectionId)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            logger.info("成功从 Chroma 删除文档, fileMd5: {}", fileMd5);
        } catch (Exception e) {
            logger.error("从 Chroma 删除文档失败", e);
            throw new RuntimeException("从 Chroma 删除文档失败", e);
        }
    }

    /**
     * Chroma 搜索结果类
     */
    public static class ChromaSearchResponse {
        private String id;
        private String fileMd5;
        private Integer chunkId;
        private String textContent;
        private float distance;
        private float score;

        public ChromaSearchResponse() {
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getFileMd5() {
            return fileMd5;
        }

        public void setFileMd5(String fileMd5) {
            this.fileMd5 = fileMd5;
        }

        public Integer getChunkId() {
            return chunkId;
        }

        public void setChunkId(Integer chunkId) {
            this.chunkId = chunkId;
        }

        public String getTextContent() {
            return textContent;
        }

        public void setTextContent(String textContent) {
            this.textContent = textContent;
        }

        public float getDistance() {
            return distance;
        }

        public void setDistance(float distance) {
            this.distance = distance;
        }

        public float getScore() {
            return score;
        }

        public void setScore(float score) {
            this.score = score;
        }
    }
}
