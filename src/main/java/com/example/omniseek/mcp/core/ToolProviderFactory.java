package com.example.omniseek.mcp.core;

import dev.langchain4j.service.tool.ToolProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统一工具入口
 * 整合内置工具 + MCP 外部工具，为 Agent 提供完整的 ToolProvider
 */
@Service
@RequiredArgsConstructor
public class ToolProviderFactory {

    private final BuiltinToolRegistry builtinToolRegistry;
    private final McpToolProviderService mcpToolProviderService;

    /**
     * 获取完整的工具配置：
     * 1. 所有内置工具（@Tool 注解方法）
     * 2. 所有已启用的 MCP 外部工具
     */
    public ToolProvider getCompleteToolProvider() {
        return mcpToolProviderService.createToolProvider(loadMcpConfigs());
    }

    /** 获取内置工具对象（用于 Agent.tools()） */
    public List<Object> getBuiltinTools() {
        return builtinToolRegistry.getAllToolObjects();
    }

    /** 从数据库/配置文件加载 MCP 工具配置 */
    private List<Map<String, Object>> loadMcpConfigs() {
        // 示例：从 application.yml 读取
        // 实际项目可以从数据库 mcp_tool 表读取
        return List.of(
                Map.of("type", "LOCAL", "command", "npx",
                        "args", List.of("-y", "@modelcontextprotocol/server-filesystem", ".")));
    }
}
