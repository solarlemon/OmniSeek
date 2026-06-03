package com.example.omniseek.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.omniseek.client.EmbeddingClient;
import com.example.omniseek.entity.EsDocument;
import com.example.omniseek.entity.SearchResult;
import com.example.omniseek.model.User;
import com.example.omniseek.exception.CustomException;
import com.example.omniseek.repository.UserRepository;
import com.example.omniseek.repository.FileUploadRepository;
import com.example.omniseek.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private ChromaService chromaService;

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);

        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // TODO: 实现查询改写以及意图识别（或者HyDE (Hypothetical Document Embeddings)）
            // 多轮对话中，根据用户之前的查询历史，对当前查询进行改写，提高搜索效率
            // 判断用户是在“查知识”、“闲聊”还是“要求执行操作”。如果是闲聊，可以直接跳过 RAG 检索，节省资源。
            // HyDE：先让大模型生成一个伪答案，再用伪答案去搜知识库（这在处理“问题与答案表达差异大”时非常有效）

            // 根据查询的文本字符生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }
            logger.info("向量生成成功，开始执行混合搜索（Chroma + ES + 手动 RRF 融合）");

            // 手动实现 RRF：分别执行 Chroma 向量搜索和 ES BM25 搜索，然后在应用层融合结果
            int recallK = topK * 30; // 召回窗口
            final int rrfK = 60; // RRF 平滑参数

            // 1. 执行 Chroma 向量搜索
            logger.debug("执行 Chroma 向量搜索...");
            float[] queryEmbedding = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                queryEmbedding[i] = queryVector.get(i);
            }
            List<ChromaService.ChromaSearchResponse> chromaResponse = chromaService.search(
                    queryEmbedding, recallK, userDbId, userEffectiveTags);

            // 2. 执行 BM25 文本搜索
            logger.debug("执行 BM25 文本搜索...");
            SearchResponse<EsDocument> bm25Response = esClient.search(s -> {
                s.index("knowledge_base");
                s.query(q -> q.bool(b -> b
                        .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                        .filter(f -> f.bool(bf -> bf
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                })))));
                s.size(recallK);
                return s;
            }, EsDocument.class);

            // 3. 手动实现 RRF 融合
            logger.debug("开始 RRF 融合 Chroma 和 ES 结果...");
            java.util.Map<String, Double> rrfScores = new java.util.HashMap<>();
            java.util.Map<String, SearchResult> documentMap = new java.util.HashMap<>();

            // 处理 Chroma 结果
            int rank = 1;
            for (ChromaService.ChromaSearchResponse hit : chromaResponse) {
                String docId = hit.getFileMd5() + "_" + hit.getChunkId();
                double rrfScore = 1.0 / (rrfK + rank);
                rrfScores.merge(docId, rrfScore, Double::sum);

                if (!documentMap.containsKey(docId)) {
                    documentMap.put(docId, new SearchResult(
                            hit.getFileMd5(),
                            hit.getChunkId(),
                            hit.getTextContent(),
                            Double.valueOf(hit.getScore()),
                            userId, // userId 会从其他来源补充
                            null, // orgTag 会从其他来源补充
                            false)); // isPublic 会从其他来源补充
                }
                rank++;
            }

            // 处理 BM25 结果
            rank = 1;
            for (co.elastic.clients.elasticsearch.core.search.Hit<EsDocument> hit : bm25Response.hits().hits()) {
                if (hit.source() != null) {
                    String docId = hit.source().getFileMd5() + "_" + hit.source().getChunkId();
                    double rrfScore = 1.0 / (rrfK + rank);
                    rrfScores.merge(docId, rrfScore, Double::sum);

                    if (!documentMap.containsKey(docId)) {
                        documentMap.put(docId, new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic()));
                    } else {
                        // 更新已有的文档信息（从 ES 获取完整的权限信息）
                        SearchResult existing = documentMap.get(docId);
                        if (existing.getUserId() == null) {
                            existing.setUserId(hit.source().getUserId());
                            existing.setOrgTag(hit.source().getOrgTag());
                            existing.setIsPublic(hit.source().isPublic());
                        }
                    }
                    rank++;
                }
            }

            // 4. 按 RRF 分数排序，取 topK
            List<SearchResult> results = rrfScores.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .limit(topK)
                    .map(entry -> {
                        SearchResult result = documentMap.get(entry.getKey());
                        // 更新分数为 RRF 分数
                        result.setScore(entry.getValue());
                        logger.info("RRF 结果 - 文件: {}, 块: {}, RRF 分数: {}, 内容: {}",
                                result.getFileMd5(), result.getChunkId(), result.getScore(),
                                result.getTextContent().substring(0,
                                        Math.min(50, result.getTextContent().length())));
                        return result;
                    })
                    .toList();

            logger.debug("返回搜索结果数量: {}", results.size());
            attachFileNames(results); // 为搜索结果补充文件名
            return results;
        } catch (Exception e) {
            logger.error("带权限的搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索方法
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    // 匹配内容相关性
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)))
                                    // 权限过滤
                                    .filter(f -> f
                                            .bool(bf -> bf
                                                    // 条件1: 用户可以访问自己的文档
                                                    .should(s1 -> s1
                                                            .term(t -> t
                                                                    .field("userId")
                                                                    .value(userDbId)))
                                                    // 条件2: 用户可以访问公开的文档
                                                    .should(s2 -> s2
                                                            .term(t -> t
                                                                    .field("public")
                                                                    .value(true)))
                                                    // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                                                    .should(s3 -> {
                                                        if (userEffectiveTags.isEmpty()) {
                                                            return s3.matchNone(mn -> mn);
                                                        } else if (userEffectiveTags.size() == 1) {
                                                            // 单个标签使用 term 查询
                                                            return s3.term(t -> t
                                                                    .field("orgTag")
                                                                    .value(userEffectiveTags.get(0)));
                                                        } else {
                                                            // 多个标签使用 bool should 组合多个 term 查询
                                                            return s3.bool(innerBool -> {
                                                                userEffectiveTags.forEach(
                                                                        tag -> innerBool.should(sh -> sh.term(t -> t
                                                                                .field("orgTag")
                                                                                .value(tag))));
                                                                return innerBool;
                                                            });
                                                        }
                                                    })))))
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class);

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}",
                    response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        logger.debug("纯文本搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}",
                                hit.source().getFileMd5(), hit.source().getChunkId(), hit.score(),
                                hit.source().getTextContent().substring(0,
                                        Math.min(50, hit.source().getTextContent().length())));
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score(),
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic());
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，只能搜索公开文档，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索，查询: {}, topK: {}", query, topK);
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }
            logger.info("向量生成成功，开始执行混合搜索（Chroma + ES + 手动 RRF 融合）");

            // 手动实现 RRF：分别执行 Chroma 向量搜索和 ES BM25 搜索，然后在应用层融合结果
            int recallK = topK * 30; // 召回窗口
            final int rrfK = 60; // RRF 平滑参数

            // 1. 执行 Chroma 向量搜索
            logger.debug("执行 Chroma 向量搜索...");
            float[] queryEmbedding = new float[queryVector.size()];
            for (int i = 0; i < queryVector.size(); i++) {
                queryEmbedding[i] = queryVector.get(i);
            }
            List<ChromaService.ChromaSearchResponse> chromaResults = chromaService.search(
                    queryEmbedding, recallK, null, null); // 空 userId 和 orgTags 表示只搜公开文档

            // 2. 执行 BM25 文本搜索
            logger.debug("执行 BM25 文本搜索...");
            SearchResponse<EsDocument> bm25Response = esClient.search(s -> {
                s.index("knowledge_base");
                s.query(q -> q.bool(b -> b
                        .must(mst -> mst.match(m -> m.field("textContent").query(query)))
                        .filter(f -> f.term(t -> t.field("public").value(true))))); // 只搜公开文档
                s.size(recallK);
                return s;
            }, EsDocument.class);

            // 3. 手动实现 RRF 融合
            logger.debug("开始 RRF 融合 Chroma 和 ES 结果...");
            java.util.Map<String, Double> rrfScores = new java.util.HashMap<>();
            java.util.Map<String, SearchResult> documentMap = new java.util.HashMap<>();

            // 处理 Chroma 结果
            int rank = 1;
            for (ChromaService.ChromaSearchResponse hit : chromaResults) {
                String docId = hit.getFileMd5() + "_" + hit.getChunkId();
                double rrfScore = 1.0 / (rrfK + rank);
                rrfScores.merge(docId, rrfScore, Double::sum);

                if (!documentMap.containsKey(docId)) {
                    documentMap.put(docId, new SearchResult(
                            hit.getFileMd5(),
                            hit.getChunkId(),
                            hit.getTextContent(),
                            Double.valueOf(hit.getScore())));
                }
                rank++;
            }

            // 处理 BM25 结果
            rank = 1;
            for (co.elastic.clients.elasticsearch.core.search.Hit<EsDocument> hit : bm25Response.hits().hits()) {
                if (hit.source() != null) {
                    String docId = hit.source().getFileMd5() + "_" + hit.source().getChunkId();
                    double rrfScore = 1.0 / (rrfK + rank);
                    rrfScores.merge(docId, rrfScore, Double::sum);

                    if (!documentMap.containsKey(docId)) {
                        documentMap.put(docId, new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score()));
                    }
                    rank++;
                }
            }

            // 4. 按 RRF 分数排序，取 topK
            List<SearchResult> results = rrfScores.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .limit(topK)
                    .map(entry -> {
                        SearchResult result = documentMap.get(entry.getKey());
                        // 更新分数为 RRF 分数
                        result.setScore(entry.getValue());
                        return result;
                    })
                    .toList();

            logger.debug("返回搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("搜索失败", e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 仅使用文本匹配的搜索方法
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q
                        .match(m -> m
                                .field("textContent")
                                .query(query)))
                .size(topK),
                EsDocument.class);

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score());
                })
                .toList();
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(
                                () -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}", user.getUsername());
            }

            // 通过orgTagCacheService获取用户的有效标签集合
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList(); // 返回空列表作为默认值
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                logger.debug("解析用户ID为Long: {}", userIdLong);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(
                                () -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过ID找到用户: {}", user.getUsername());
                return userIdLong.toString(); // 如果输入已经是数字ID，直接返回
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                logger.debug("用户ID不是数字格式，作为用户名查找: {}", userId);
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                logger.debug("通过用户名找到用户: {}, ID: {}", user.getUsername(), user.getId());
                return user.getId().toString(); // 返回用户的数据库ID
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        /**
         * 为搜索结果补充文件名，因为搜索结果中只包含文件的MD5值，而没有文件名，所以需要从数据库中查询文件名
         */
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            // 从数据库查询所有相关的文件上传记录
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            // 构建一个映射，将 fileMd5 映射到文件名
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
