package com.example.omniseek;

import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MinerUFileParser {

    private static final String API_BASE_URL = "https://mineru.net/api/v4";
    private static final String TOKEN = "eyJ0eXBlIjoiSldUIiwiYWxnIjoiSFM1MTIifQ.eyJqdGkiOiI2NjMwMDU1MiIsInJvbCI6IlJPTEVfUkVHSVNURVIiLCJpc3MiOiJPcGVuWExhYiIsImlhdCI6MTc3ODMyOTUwOSwiY2xpZW50SWQiOiJsa3pkeDU3bnZ5MjJqa3BxOXgydyIsInBob25lIjoiIiwib3BlbklkIjpudWxsLCJ1dWlkIjoiNjY2NjljOGYtYzFmNi00OWNkLWE1YzAtN2E5MDEwYWMwYTBjIiwiZW1haWwiOiJzb2xhcl9sZW1vbkAxNjMuY29tIiwiZXhwIjoxNzg2MTA1NTA5fQ.VRU6io4_-2RUu97X4Fh6jmrgkhvnrMxbKLzfmQwhPt6hn-R6eU4f5Ub59_qH3rgiL1OMzaBD5YSfGjm6kU4eag"; // 替换为你的Token
    private static final Gson gson = new Gson();
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) {
        // 要解析的本地文件路径
        String localFilePath = "D:\\快速访问\\下载\\1-2.pdf";
        parseLocalFile(localFilePath);
    }

    /**
     * 解析本地文件的主流程
     * 
     * @param filePath 本地文件路径
     */
    public static void parseLocalFile(String filePath) {
        try {
            // 1. 申请文件上传URL
            List<String> uploadUrls = requestUploadUrls(filePath);
            if (uploadUrls == null || uploadUrls.isEmpty()) {
                System.err.println("申请上传URL失败");
                return;
            }

            // 2. 上传文件到获取到的URL
            String uploadUrl = uploadUrls.get(0);
            long uploadStartTime = System.currentTimeMillis();
            boolean uploadSuccess = uploadFileToUrl(filePath, uploadUrl);
            if (!uploadSuccess) {
                System.err.println("文件上传失败");
                return;
            }

            System.out.println("文件上传成功，解析任务已自动提交。");

            // 3. 获取任务 ID 并轮询
            String dataId = extractDataIdFromUrl(uploadUrl); 
            if (dataId != null) {
                System.out.println("开始轮询解析结果，Data ID: " + dataId);
                pollExtractionResult(dataId, uploadStartTime);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从上传 URL 中提取 Data ID (根据 MinerU 的 URL 规则)
     */
    private static String extractDataIdFromUrl(String url) {
        // 示例 URL: .../extract/2026-05-09/29b3cffa.../6a8a91c9...pdf
        // 通常在申请接口的响应中会有更直接的 task_id，这里简化处理
        try {
            String[] parts = url.split("/");
            for (String part : parts) {
                if (part.length() > 30 && part.contains("-")) {
                    return part; // 返回类似 UUID 的部分
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    /**
     * 轮询解析结果
     */
    private static void pollExtractionResult(String dataId, long startTime) throws IOException, InterruptedException {
        String url = API_BASE_URL + "/extract/task/" + dataId; // 假设的查询接口

        while (true) {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer " + TOKEN)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("查询失败: " + response.code());
                    break;
                }

                String body = response.body().string();
                JsonObject json = gson.fromJson(body, JsonObject.class);
                
                int code = json.get("code").getAsInt();
                if (code == 0) {
                    JsonObject data = json.getAsJsonObject("data");
                    String status = data.get("status").getAsString();
                    
                    if ("success".equalsIgnoreCase(status)) {
                        long totalTime = System.currentTimeMillis() - startTime;
                        System.out.println("解析完成！");
                        System.out.println("Markdown 结果 URL: " + data.get("full_md_url").getAsString());
                        System.out.println("从开始上传到解析成功的总耗时: " + (totalTime / 1000.0) + " 秒");
                        break;
                    } else if ("failed".equalsIgnoreCase(status)) {
                        System.err.println("解析任务失败。");
                        break;
                    }
                }
                
                System.out.println("解析中，请稍候...");
                Thread.sleep(10000); // 建议设为 10 秒，平衡实时性与请求频率
            }
        }
    }

    /**
     * 步骤1: 请求服务器获取文件的上传URL
     * 
     * @param filePath 文件完整路径
     * @return 包含上传URL的列表
     * @throws IOException 网络请求异常
     */
    private static List<String> requestUploadUrls(String filePath) throws IOException {
        String url = API_BASE_URL + "/file-urls/batch";

        // 从路径中提取文件名
        String fileName = new File(filePath).getName();

        // 构建请求体JSON
        Map<String, Object> requestBody = new HashMap<>();

        // 构建files列表
        List<Map<String, String>> files = new ArrayList<>();
        Map<String, String> fileInfo = new HashMap<>();
        fileInfo.put("name", fileName);
        fileInfo.put("data_id", UUID.randomUUID().toString()); // 唯一标识符
        files.add(fileInfo);

        requestBody.put("files", files);
        requestBody.put("model_version", "vlm"); // 使用VLM模型

        String jsonBody = gson.toJson(requestBody);

        // 构建HTTP请求
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + TOKEN)
                .addHeader("Content-Type", "application/json")
                .build();

        // 执行请求
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("请求失败，状态码: " + response.code());
                return null;
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.get("code").getAsInt() == 0) {
                // 解析成功，获取上传URLs
                List<String> urls = new ArrayList<>();
                jsonResponse.getAsJsonObject("data")
                        .getAsJsonArray("file_urls")
                        .forEach(urlElement -> urls.add(urlElement.getAsString()));

                System.out.println("成功获取上传URLs: " + urls);
                return urls;
            } else {
                String msg = jsonResponse.get("msg").getAsString();
                System.err.println("申请上传链接失败: " + msg);
                return null;
            }
        }
    }

    /**
     * 步骤2: 将本地文件通过PUT请求上传到指定URL
     * 
     * @param filePath  本地文件路径
     * @param uploadUrl 上传URL
     * @return 是否上传成功
     * @throws IOException 文件读取或网络异常
     */
    private static boolean uploadFileToUrl(String filePath, String uploadUrl) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("本地文件不存在: " + filePath);
            return false;
        }

        // 读取文件字节
        byte[] fileContent = java.nio.file.Files.readAllBytes(file.toPath());

        // 构建PUT请求
        // 注意：MinerU 返回的是阿里云 OSS 的预签名 URL。
        // OSS 校验签名时会检查 Content-Type。如果申请 URL 时没指定，上传时也不能带 Content-Type，或者必须保持一致。
        // 这里去掉 MediaType.parse("application/octet-stream")，使用 null 或尝试不设置 Content-Type
        // 报头
        Request request = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(fileContent, null))
                .build();

        // 执行上传
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                System.err.println("上传文件到云端失败，状态码: " + response.code());
                System.err.println("响应详情: " + response.body().string());
            }
            return response.isSuccessful();
        }
    }
}