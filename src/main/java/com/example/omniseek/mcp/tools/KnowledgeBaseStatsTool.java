package com.example.omniseek.mcp.tools;

import com.example.omniseek.mcp.core.BuiltinToolProvider;
import com.example.omniseek.repository.DocumentVectorRepository;
import com.example.omniseek.repository.FileUploadRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAG 场景示例：查询知识库统计信息
 * 统计文档数量、切片数量、各用户上传分布等
 */
@Slf4j
@Component
public class KnowledgeBaseStatsTool implements BuiltinToolProvider {

    private final DocumentVectorRepository documentVectorRepository;
    private final FileUploadRepository fileUploadRepository;

    public KnowledgeBaseStatsTool(DocumentVectorRepository documentVectorRepository,
            FileUploadRepository fileUploadRepository) {
        this.documentVectorRepository = documentVectorRepository;
        this.fileUploadRepository = fileUploadRepository;
    }

    @Tool("knowledge_base_stats")
    public String getKnowledgeBaseStats(String knowledgeBaseId) {
        try {
            long totalChunks = documentVectorRepository.count();
            long totalFiles = fileUploadRepository.count();

            return String.format(
                    "📊 知识库统计报告\n" +
                            "━━━━━━━━━━━━━━━━━━━━\n" +
                            "• 知识库 ID: %s\n" +
                            "• 文档总数: %d 个\n" +
                            "• 切片总数: %d 个\n" +
                            "• 平均切片/文档: %.1f 个\n" +
                            "━━━━━━━━━━━━━━━━━━━━",
                    knowledgeBaseId,
                    totalFiles,
                    totalChunks,
                    totalFiles > 0 ? (double) totalChunks / totalFiles : 0);
        } catch (Exception e) {
            log.error("获取知识库统计信息失败", e);
            return "查询知识库统计信息时发生错误: " + e.getMessage();
        }
    }

    @Override
    public String getToolName() {
        return "knowledge_base_stats";
    }

    @Override
    public String getDisplayName() {
        return "知识库统计";
    }

    @Override
    public String getDescription() {
        return "获取 RAG 知识库的文档/切片统计信息";
    }
}
