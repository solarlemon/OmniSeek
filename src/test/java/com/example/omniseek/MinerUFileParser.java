package com.example.omniseek;

import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * MinerU Agent 轻量解析 API - 本地文件上传示例
 * 文档参考：https://mineru.net/api/v1/agent/parse/file
 */
public class MinerUFileParser {

    private static final String BASE_URL = "https://mineru.net/api/v1/agent";
    private static final int TIMEOUT_SECONDS = 120;
    private static final int POLL_INTERVAL_SECONDS = 3;
    private static final int MAX_POLL_ATTEMPTS = 100; // 约 5 分钟

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // 1. 指定本地 PDF 文件路径
        String filePath = "D:/快速访问/下载/Localedit.pdf";

        // 2. 执行解析
        String markdownContent = parseLocalPdf(filePath);

        if (markdownContent != null) {
            File pdfFile = new File(filePath);
            File parentDir = pdfFile.getParentFile();
            String baseName = pdfFile.getName().replaceFirst("\\.[^.]+$", "");
            File mdFile = new File(parentDir, baseName + ".md");

            Files.write(mdFile.toPath(), markdownContent.getBytes(StandardCharsets.UTF_8));
            System.out.println("✅ 解析成功！Markdown 已保存至: " + mdFile.getAbsolutePath());
        } else {
            System.out.println("❌ 解析失败");
        }
    }

    /**
     * 解析本地 PDF 文件
     */
    public static String parseLocalPdf(String filePath) throws IOException, InterruptedException {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("文件不存在或不是有效文件: " + filePath);
            return null;
        }

        // 检查文件大小（Agent API 限制 10MB）
        long fileSizeMB = file.length() / (1024 * 1024);
        if (fileSizeMB > 10) {
            System.err.println("⚠️ 文件大小 " + fileSizeMB + "MB 超过 Agent API 限制（10MB），请使用精准解析 API 或拆分文件");
            return null;
        }

        System.out.println("📄 文件: " + file.getName() + " (" + fileSizeMB + "MB)");

        // 第一步：获取签名上传 URL
        String taskId = getUploadUrl(file.getName());
        if (taskId == null) {
            return null;
        }

        // 第二步：PUT 上传文件
        boolean uploaded = uploadFile(file, taskId);
        if (!uploaded) {
            System.err.println("❌ 文件上传失败");
            return null;
        }

        System.out.println("✅ 文件上传成功，等待解析...");

        // 第三步：轮询解析结果
        return pollResult(taskId);
    }

    /**
     * 第一步：获取签名上传 URL
     */
    private static String getUploadUrl(String fileName) throws IOException {
        String url = BASE_URL + "/parse/file";

        // 构建请求体 JSON（与官方文档一致）
        String jsonBody = String.format(
                "{\"file_name\": \"%s\", \"language\": \"ch\", \"enable_table\": true, \"is_ocr\": false, \"enable_formula\": true}",
                fileName);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("获取上传 URL 失败，HTTP 状态码: " + response.code());
                return null;
            }

            String responseBody = response.body().string();
            System.out.println("📨 获取上传 URL 响应: " + responseBody);

            JsonNode jsonNode = mapper.readTree(responseBody);
            int code = jsonNode.path("code").asInt();

            if (code != 0) {
                System.err.println("获取上传 URL 失败: " + jsonNode.path("msg").asText());
                return null;
            }

            String taskId = jsonNode.path("data").path("task_id").asText();
            String fileUrl = jsonNode.path("data").path("file_url").asText();

            if (taskId == null || taskId.isEmpty()) {
                System.err.println("未获取到 task_id");
                return null;
            }

            // 存储 file_url 供后续上传使用
            UploadContext.setFileUrl(taskId, fileUrl);
            System.out.println("📋 Task ID: " + taskId);
            System.out.println("🔗 签名上传 URL: " + fileUrl);

            return taskId;
        }
    }

    /**
     * 第二步：PUT 上传文件到 OSS
     */
    private static boolean uploadFile(File file, String taskId) throws IOException {
        String fileUrl = UploadContext.getFileUrl(taskId);
        if (fileUrl == null || fileUrl.isEmpty()) {
            System.err.println("未找到 file_url，请先调用 getUploadUrl");
            return false;
        }

        // 使用 PUT 方法，将文件作为请求体
        Request request = new Request.Builder()
                .url(fileUrl)
                .put(RequestBody.create(file, null))
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            // OSS PUT 成功返回 200 或 201
            if (code == 200 || code == 201) {
                System.out.println("✅ PUT 上传成功，状态码: " + code);
                return true;
            } else {
                System.err.println("❌ PUT 上传失败，状态码: " + code);
                System.err.println("响应: " + (response.body() != null ? response.body().string() : "无响应体"));
                return false;
            }
        }
    }

    /**
     * 第三步：轮询解析结果
     */
    private static String pollResult(String taskId) throws IOException, InterruptedException {
        String url = BASE_URL + "/parse/" + taskId;

        int attempts = 0;
        while (attempts < MAX_POLL_ATTEMPTS) {
            attempts++;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("查询状态失败，HTTP 状态码: " + response.code());
                    Thread.sleep(POLL_INTERVAL_SECONDS * 1000);
                    continue;
                }

                String responseBody = response.body().string();
                JsonNode jsonNode = mapper.readTree(responseBody);

                int code = jsonNode.path("code").asInt();
                if (code != 0) {
                    System.err.println("查询状态返回错误: " + jsonNode.path("msg").asText());
                    Thread.sleep(POLL_INTERVAL_SECONDS * 1000);
                    continue;
                }

                JsonNode data = jsonNode.path("data");
                String state = data.path("state").asText();

                // 状态映射
                String stateDesc;
                switch (state) {
                    case "waiting-file":
                        stateDesc = "等待文件上传";
                        break;
                    case "uploading":
                        stateDesc = "文件下载中";
                        break;
                    case "pending":
                        stateDesc = "排队中";
                        break;
                    case "running":
                        stateDesc = "解析中";
                        break;
                    case "done":
                        stateDesc = "已完成";
                        break;
                    case "failed":
                        stateDesc = "失败";
                        break;
                    default:
                        stateDesc = state;
                        break;
                }

                System.out.println("📊 [" + attempts + "] 任务状态: " + stateDesc);

                if ("done".equals(state)) {
                    String markdownUrl = data.path("markdown_url").asText();
                    if (markdownUrl == null || markdownUrl.isEmpty()) {
                        System.err.println("⚠️ 任务已完成，但未返回 markdown_url");
                        return null;
                    }

                    System.out.println("📥 下载 Markdown: " + markdownUrl);
                    return downloadMarkdown(markdownUrl);
                }

                if ("failed".equals(state)) {
                    String errMsg = data.path("err_msg").asText();
                    int errCode = data.path("err_code").asInt();
                    System.err.println("❌ 解析失败，错误码: " + errCode + ", 错误信息: " + errMsg);
                    return null;
                }

                // 等待后继续轮询
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000);
            }
        }

        System.err.println("⏰ 轮询超时（" + MAX_POLL_ATTEMPTS + " 次），请稍后手动查询 task_id: " + taskId);
        return null;
    }

    /**
     * 下载 Markdown 内容
     */
    private static String downloadMarkdown(String markdownUrl) throws IOException {
        Request request = new Request.Builder()
                .url(markdownUrl)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("下载 Markdown 失败，HTTP 状态码: " + response.code());
                return null;
            }
            return response.body().string();
        }
    }

    /**
     * 简单上下文类，用于在步骤间传递 file_url
     */
    private static class UploadContext {
        private static String currentTaskId;
        private static String currentFileUrl;

        public static void setFileUrl(String taskId, String fileUrl) {
            currentTaskId = taskId;
            currentFileUrl = fileUrl;
        }

        public static String getFileUrl(String taskId) {
            if (taskId.equals(currentTaskId)) {
                return currentFileUrl;
            }
            return null;
        }
    }
}