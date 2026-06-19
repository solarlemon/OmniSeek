package com.example.omniseek.mcp.tools;

import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.entity.FileUpload;
import com.example.omniseek.entity.User;
import com.example.omniseek.mcp.core.BuiltinToolProvider;
import com.example.omniseek.repository.ChatMessageRepository;
import com.example.omniseek.repository.ChatSessionRepository;
import com.example.omniseek.repository.FileUploadRepository;
import com.example.omniseek.repository.UserRepository;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQL 数据查询工具
 * 查询系统用户、文件、会话、消息等统计数据
 */
@Component
public class SqlQueryTool implements BuiltinToolProvider {

    private final UserRepository userRepository;
    private final FileUploadRepository fileUploadRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public SqlQueryTool(UserRepository userRepository,
            FileUploadRepository fileUploadRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository) {
        this.userRepository = userRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Tool("sql_query_user_stats")
    public String queryUserStats() {
        long totalUsers = userRepository.count();
        List<User> recentUsers = userRepository.findTop10ByOrderByCreatedAtDesc();

        StringBuilder sb = new StringBuilder();
        sb.append("**用户统计**\n\n");
        sb.append("系统用户总数: ").append(totalUsers).append(" 人\n\n");
        sb.append("**最近注册用户** (前10):\n\n");

        if (recentUsers.isEmpty()) {
            sb.append("暂无用户数据\n");
        } else {
            for (int i = 0; i < recentUsers.size(); i++) {
                User u = recentUsers.get(i);
                String time = u.getCreatedAt() != null ? u.getCreatedAt().format(DT_FMT) : "未知";
                sb.append(String.format("%d. **%s** — 注册时间: %s\n", i + 1, u.getUsername(), time));
            }
        }
        sb.append("\n> 提示: 你可以问「谁上传的文件最多」「谁的会话最多」查看更详细的数据\n");
        return sb.toString();
    }

    @Tool("sql_query_file_stats")
    public String queryFileStats() {
        long totalFiles = fileUploadRepository.count();
        List<FileUpload> recentFiles = fileUploadRepository.findTop10ByOrderByCreatedAtDesc();

        long pdfCount = fileUploadRepository.findAll().stream()
                .filter(f -> f.getFileName() != null && f.getFileName().toLowerCase().endsWith(".pdf"))
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("**文件统计**\n\n");
        sb.append("上传文件总数: ").append(totalFiles).append(" 个\n");
        sb.append("其中 PDF 文件: ").append(pdfCount).append(" 个\n\n");
        sb.append("**最近上传文件** (前10):\n\n");

        if (recentFiles.isEmpty()) {
            sb.append("暂无文件数据\n");
        } else {
            for (int i = 0; i < recentFiles.size(); i++) {
                FileUpload f = recentFiles.get(i);
                String time = f.getCreatedAt() != null ? f.getCreatedAt().format(DT_FMT) : "未知";
                String size = formatFileSize(f.getTotalSize());
                String status = f.getStatus() == 1 ? "已完成" : "上传中";
                sb.append(String.format("%d. **%s** — %s, %s, 状态: %s\n",
                        i + 1, f.getFileName(), size, time, status));
            }
        }
        return sb.toString();
    }

    @Tool("sql_query_session_stats")
    public String querySessionStats() {
        List<ChatSession> allSessions = chatSessionRepository.findAll();

        List<ChatSession> topSessions = allSessions.stream()
                .filter(s -> s.getActive() && s.getMessageCount() > 0)
                .sorted((a, b) -> Integer.compare(b.getMessageCount(), a.getMessageCount()))
                .limit(10)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("**会话统计**\n\n");
        sb.append("总会话数: ").append(allSessions.size()).append("\n\n");
        sb.append("**消息数最多的会话** (Top 10):\n\n");

        if (topSessions.isEmpty()) {
            sb.append("暂无会话数据\n");
        } else {
            for (int i = 0; i < topSessions.size(); i++) {
                ChatSession s = topSessions.get(i);
                String title = s.getTitle() != null ? s.getTitle() : "未命名";
                String time = s.getUpdatedAt() != null ? s.getUpdatedAt().format(DT_FMT) : "未知";
                sb.append(String.format("%d. **%s** — %d 条消息, 最后活跃: %s\n",
                        i + 1, title, s.getMessageCount(), time));
            }
        }
        return sb.toString();
    }

    @Tool("sql_query_message_stats")
    public String queryMessageStats() {
        long totalMessages = chatMessageRepository.count();

        StringBuilder sb = new StringBuilder();
        sb.append("**消息统计**\n\n");
        sb.append("数据库总消息数: ").append(totalMessages).append(" 条 (含归档历史)\n");
        sb.append("> 活跃会话的消息存储在 Redis 中，已归档的消息存储在 MySQL 中\n\n");

        List<ChatSession> allSessions = chatSessionRepository.findAll();
        long totalSessionMessages = allSessions.stream()
                .filter(s -> s.getActive())
                .mapToInt(ChatSession::getMessageCount)
                .sum();
        sb.append("**活跃会话消息数**: ").append(totalSessionMessages).append(" 条\n");
        sb.append("**活跃会话数**: ").append(allSessions.stream().filter(ChatSession::getActive).count()).append(" 个\n");

        return sb.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String getToolName() {
        return "sql_query";
    }

    @Override
    public String getDisplayName() {
        return "SQL数据查询";
    }

    @Override
    public String getDescription() {
        return "查询系统数据，包括用户统计、文件统计、会话统计和消息统计";
    }
}
