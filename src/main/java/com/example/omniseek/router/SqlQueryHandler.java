package com.example.omniseek.router;

import com.example.omniseek.entity.ChatSession;
import com.example.omniseek.entity.FileUpload;
import com.example.omniseek.entity.User;
import com.example.omniseek.repository.ChatMessageRepository;
import com.example.omniseek.repository.ChatSessionRepository;
import com.example.omniseek.repository.FileUploadRepository;
import com.example.omniseek.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class SqlQueryHandler implements RouteHandler {

    private static final Logger logger = LoggerFactory.getLogger(SqlQueryHandler.class);

    private final UserRepository userRepository;
    private final FileUploadRepository fileUploadRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 查询模板：keywords → description + executor
    private final List<QueryTemplate> templates;

    public SqlQueryHandler(UserRepository userRepository,
            FileUploadRepository fileUploadRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository) {
        this.userRepository = userRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;

        this.templates = List.of(
                new QueryTemplate("用户统计",
                        new String[] { "用户", "数量", "多少", "几个", "注册" },
                        "查询系统用户总数和最近注册用户",
                        this::queryUserStats),

                new QueryTemplate("文件统计",
                        new String[] { "文件", "文档", "上传", "数量", "多少" },
                        "查询上传文件总数和最近上传",
                        this::queryFileStats),

                new QueryTemplate("会话统计",
                        new String[] { "会话", "对话", "最多", "排名" },
                        "查询会话统计（消息数最多的Top会话）",
                        this::querySessionStats),

                new QueryTemplate("消息统计",
                        new String[] { "消息", "聊天", "记录", "最近" },
                        "查询最近的消息记录",
                        this::queryRecentMessages));
    }

    @Override
    public RouteType getRouteType() {
        return RouteType.SQL_QUERY;
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
            logger.info("SQL查询路由处理: userId={}, message={}", userId, userMessage);

            // 匹配查询模板
            QueryTemplate matched = null;
            int bestScore = 0;
            for (QueryTemplate tpl : templates) {
                int score = tpl.matchScore(userMessage);
                if (score > bestScore) {
                    bestScore = score;
                    matched = tpl;
                }
            }

            if (matched == null || bestScore == 0) {
                // 没有匹配，返回可用查询列表
                String help = buildHelpText();
                onChunk.accept(help);
                onComplete.run();
                return;
            }

            // 执行查询
            String result = matched.executor.execute();
            onChunk.accept(result);
            onComplete.run();

        } catch (Exception e) {
            logger.error("SQL查询处理失败", e);
            onError.accept(e);
        }
    }

    // ========== 查询实现 ==========

    private String queryUserStats() {
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

    private String queryFileStats() {
        long totalFiles = fileUploadRepository.count();
        List<FileUpload> recentFiles = fileUploadRepository.findTop10ByOrderByCreatedAtDesc();

        // 统计各类型文件数量
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

    @SuppressWarnings("unchecked")
    private String querySessionStats() {
        List<ChatSession> allSessions = chatSessionRepository.findAll();

        // 按消息数排序，取前10
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

    @SuppressWarnings("unchecked")
    private String queryRecentMessages() {
        long totalMessages = chatMessageRepository.count();

        StringBuilder sb = new StringBuilder();
        sb.append("**消息统计**\n\n");
        sb.append("数据库总消息数: ").append(totalMessages).append(" 条 (含归档历史)\n");
        sb.append("> 活跃会话的消息存储在 Redis 中，已归档的消息存储在 MySQL 中\n\n");

        // 查询所有活跃会话的消息数汇总
        List<ChatSession> allSessions = chatSessionRepository.findAll();
        long totalSessionMessages = allSessions.stream()
                .filter(s -> s.getActive())
                .mapToInt(ChatSession::getMessageCount)
                .sum();
        sb.append("**活跃会话消息数**: ").append(totalSessionMessages).append(" 条\n");
        sb.append("**活跃会话数**: ").append(allSessions.stream().filter(ChatSession::getActive).count()).append(" 个\n");

        return sb.toString();
    }

    // ========== 辅助方法 ==========
    private String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("**SQL 数据查询**\n\n");
        sb.append("我可以帮你查询系统数据，支持以下查询：\n\n");
        for (int i = 0; i < templates.size(); i++) {
            QueryTemplate tpl = templates.get(i);
            sb.append(String.format("%d. **%s** — %s\n", i + 1, tpl.name, tpl.description));
        }
        sb.append("\n例如：\n");
        sb.append("- 「系统有多少用户？」\n");
        sb.append("- 「最近上传了哪些文件？」\n");
        sb.append("- 「哪个会话的消息最多？」\n");
        sb.append("- 「最近有哪些聊天记录？」\n");
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

    // ========== 内部类 ==========
    private static class QueryTemplate {
        final String name;
        final String[] keywords;
        final String description;
        final QueryExecutor executor;

        QueryTemplate(String name, String[] keywords, String description, QueryExecutor executor) {
            this.name = name;
            this.keywords = keywords;
            this.description = description;
            this.executor = executor;
        }

        /** 计算匹配分数：命中关键词个数 */
        int matchScore(String message) {
            int score = 0;
            String lower = message.toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw)) {
                    score++;
                }
            }
            return score;
        }
    }

    @FunctionalInterface
    private interface QueryExecutor {
        String execute();
    }
}
