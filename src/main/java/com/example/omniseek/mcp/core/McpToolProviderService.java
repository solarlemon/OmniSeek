package com.example.omniseek.mcp.core;

// import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * MCP 工具提供者服务
 * 负责创建 MCP Client 并返回统一的 ToolProvider
 */
@Slf4j
@Service
public class McpToolProviderService {

    // private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据配置创建 MCP Client 并返回 ToolProvider
     *
     * @param configs 工具配置列表
     *                LOCAL:
     *                {"type":"LOCAL","command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","."]}
     *                REMOTE:
     *                {"type":"REMOTE","baseUrl":"http://localhost:8080/mcp"}
     * @return ToolProvider
     */
    public ToolProvider createToolProvider(List<Map<String, Object>> configs) {
        List<McpClientAdapter> clients = new ArrayList<>();

        for (Map<String, Object> config : configs) {
            try {
                String type = (String) config.get("type");
                McpClientAdapter client = switch (type) {
                    case "LOCAL" -> createStdioClient(config);
                    case "REMOTE" -> createRemoteClient(config);
                    default -> throw new IllegalArgumentException("未知类型: " + type);
                };
                clients.add(client);
            } catch (Exception e) {
                log.error("创建 MCP Client 失败: {}", e.getMessage());
            }
        }

        return McpToolProvider.builder()
                .mcpClients(clients.stream()
                        .map(adapter -> (McpClient) adapter.client)
                        .toList())
                .build();
    }

    /**
     * 创建本地 STDIO MCP 客户端
     * 适用于 npx 启动的 MCP Server（文件系统、数据库、搜索等）
     */
    private McpClientAdapter createStdioClient(Map<String, Object> config) {
        String command = (String) config.get("command");
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) config.getOrDefault("args", List.of());

        // Windows 兼容：npx → npx.cmd
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (List.of("npx", "npm", "node", "pnpm", "yarn", "uvx").contains(command)) {
                command = command + ".cmd";
            }
        }

        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        fullCommand.addAll(args);

        log.info("创建 STDIO MCP Client: {}", String.join(" ", fullCommand));

        McpTransport transport = StdioMcpTransport.builder()
                .command(fullCommand)
                .logEvents(true)
                .build();

        DefaultMcpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();

        return new McpClientAdapter(client, command);
    }

    /**
     * 创建远程 HTTP/SSE MCP 客户端
     * 适用于远程部署的 MCP Server
     */
    private McpClientAdapter createRemoteClient(Map<String, Object> config) {
        String baseUrl = (String) config.get("baseUrl");
        log.info("创建 HTTP/SSE MCP Client: {}", baseUrl);

        McpTransport transport = StreamableHttpMcpTransport.builder()
                .url(baseUrl)
                .logRequests(true)
                .build();

        DefaultMcpClient client = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();

        return new McpClientAdapter(client, baseUrl);
    }

    /** 内部包装类 */
    private record McpClientAdapter(DefaultMcpClient client, String identifier) {
    }
}
