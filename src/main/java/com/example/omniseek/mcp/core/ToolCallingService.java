package com.example.omniseek.mcp.core;

import com.example.omniseek.client.DeepSeekClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Consumer;

/**
 * 工具调用核心服务
 * <p>
 * 集成内置工具 (@Tool 注解) 到 DeepSeek 聊天流程中。
 * 实现两阶段调用:
 * 阶段1: 非流式调用 LLM + tools，检测是否需要调用工具
 * 阶段2: 执行工具后，流式调用 LLM 生成回复
 * </p>
 */
@Service
public class ToolCallingService {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallingService.class);

    private final List<BuiltinToolProvider> toolProviders;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    /** 缓存的工具定义列表（OpenAI 格式） */
    private List<Map<String, Object>> toolDefinitions;

    /** 工具名称 → { bean, method } 映射 */
    private final Map<String, ToolMethodInfo> toolMethodMap = new LinkedHashMap<>();

    public ToolCallingService(List<BuiltinToolProvider> toolProviders, DeepSeekClient deepSeekClient) {
        this.toolProviders = toolProviders;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
        initTools();
    }

    /** 初始化：扫描所有 BuiltinToolProvider 中的 @Tool 方法 */
    private void initTools() {
        toolDefinitions = new ArrayList<>();

        for (BuiltinToolProvider provider : toolProviders) {
            Class<?> clazz = provider.getClass();
            for (Method method : clazz.getDeclaredMethods()) {
                Tool toolAnnotation = method.getAnnotation(Tool.class);
                if (toolAnnotation == null) {
                    // 也检查父类/接口中的方法
                    continue;
                }

                String toolName = provider.getToolName();
                String description = provider.getDescription();

                // 构建参数定义
                Map<String, Object> properties = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();

                for (Parameter param : method.getParameters()) {
                    String paramName = param.getName();
                    // 注意：需要 -parameters 编译选项才能获取真实参数名
                    // 如果获取不到（如 param.getName() 返回 arg0），回退到简单命名
                    if (paramName.startsWith("arg")) {
                        paramName = paramName.toLowerCase();
                    }
                    properties.put(paramName, Map.of(
                            "type", simpleTypeName(param.getType()),
                            "description", paramName));
                    required.add(paramName);
                }

                // 构建 OpenAI 格式的 tool definition
                Map<String, Object> functionDef = new LinkedHashMap<>();
                functionDef.put("name", toolName);
                functionDef.put("description", description);
                functionDef.put("parameters", Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", required));

                toolDefinitions.add(Map.of(
                        "type", "function",
                        "function", functionDef));

                toolMethodMap.put(toolName, new ToolMethodInfo(provider, method));

                logger.info("注册工具: {} -> {}.{}() 参数={}",
                        toolName, clazz.getSimpleName(), method.getName(), required);
            }
        }

        logger.info("工具调用服务初始化完成，共加载 {} 个工具定义", toolDefinitions.size());
    }

    /**
     * 带工具支持的流式聊天
     * <p>
     * 流程：
     * 1. 构建消息列表（含历史、system 上下文、用户消息）
     * 2. 非流式调用 LLM + tools，检测是否需要工具调用
     * 3. 如果需要工具调用，执行工具并注入结果
     * 4. 流式调用 LLM 生成最终回复
     * </p>
     */
    public void streamWithTools(String userMessage,
            String context,
            List<Map<String, String>> history,
            Consumer<String> onChunk,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        try {
            // 1. 构建消息列表
            List<Map<String, Object>> messages = buildMessages(userMessage, context, history);

            // 如果没有工具定义，直接退化为普通流式调用
            if (toolDefinitions.isEmpty()) {
                logger.debug("无可用工具，退化为普通流式调用");
                deepSeekClient.streamChat(messages, onChunk, onError, onComplete);
                return;
            }

            // 2. 阶段1：非流式调用，检测工具调用
            logger.debug("阶段1: 非流式调用检测工具需求");
            String phase1Response = deepSeekClient.callWithTools(messages, toolDefinitions);

            if (phase1Response == null) {
                logger.error("阶段1调用失败，退化为普通流式调用");
                deepSeekClient.streamChat(messages, onChunk, onError, onComplete);
                return;
            }

            // 3. 解析响应，检查是否有 tool_calls
            JsonNode responseJson = objectMapper.readTree(phase1Response);
            JsonNode choice = responseJson.at("/choices/0");
            JsonNode messageNode = choice.path("message");
            JsonNode toolCallsNode = messageNode.path("tool_calls");

            if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
                // 有工具调用请求
                logger.info("LLM 请求调用 {} 个工具", toolCallsNode.size());

                // 4. 将 assistant 消息（含 tool_calls）加入消息列表
                messages.add(objectMapper.convertValue(messageNode, Map.class));

                // 5. 执行每个工具并添加结果
                for (JsonNode toolCall : toolCallsNode) {
                    String toolCallId = toolCall.path("id").asText();
                    String functionName = toolCall.path("function").path("name").asText();
                    String arguments = toolCall.path("function").path("arguments").asText();

                    logger.info("执行工具: {} (id={})", functionName, toolCallId);

                    // 执行工具
                    String toolResult = executeTool(functionName, arguments);

                    // 添加 tool 结果消息
                    Map<String, Object> toolResultMsg = new LinkedHashMap<>();
                    toolResultMsg.put("role", "tool");
                    toolResultMsg.put("tool_call_id", toolCallId);
                    toolResultMsg.put("content", toolResult);
                    messages.add(toolResultMsg);

                    logger.debug("工具 {} 执行结果长度: {}", functionName, toolResult.length());
                }

                // 6. 阶段2：流式调用（含工具结果）
                logger.debug("阶段2: 流式调用生成回复");
                deepSeekClient.streamChat(messages, onChunk, onError, onComplete);
            } else {
                // 没有工具调用，检查是否有直接文本回复
                String content = messageNode.path("content").asText("");
                if (!content.isEmpty()) {
                    logger.debug("LLM 直接回复，无工具调用，内容长度: {}", content.length());
                    // 将文本内容作为单个块发送
                    onChunk.accept(content);
                    onComplete.run();
                } else {
                    // 既无工具调用也无内容，退化为流式调用
                    logger.warn("非流式调用无工具调用也无内容，退化为普通流式调用");
                    deepSeekClient.streamChat(buildMessages(userMessage, context, history),
                            onChunk, onError, onComplete);
                }
            }

        } catch (Exception e) {
            logger.error("工具调用流程异常", e);
            onError.accept(e);
        }
    }

    /**
     * 构建消息列表
     */
    private List<Map<String, Object>> buildMessages(String userMessage,
            String context,
            List<Map<String, String>> history) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // System 消息
        StringBuilder sysBuilder = new StringBuilder();
        if (context != null && !context.isEmpty()) {
            sysBuilder.append("你是一个智能助手，可以使用工具来辅助回答。\n\n");
            sysBuilder.append("参考信息:\n").append(context);
        } else {
            sysBuilder.append("你是一个智能助手，可以使用工具来辅助回答。");
        }
        messages.add(Map.of("role", "system", "content", sysBuilder.toString()));

        // 历史消息
        if (history != null) {
            for (Map<String, String> h : history) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", h.get("role"));
                msg.put("content", h.get("content"));
                messages.add(msg);
            }
        }

        // 当前用户消息
        messages.add(Map.of("role", "user", "content", userMessage));

        return messages;
    }

    /**
     * 执行工具
     */
    private String executeTool(String toolName, String argumentsJson) {
        ToolMethodInfo info = toolMethodMap.get(toolName);
        if (info == null) {
            logger.warn("未找到工具: {}", toolName);
            return "错误: 未找到工具 " + toolName;
        }

        try {
            // 解析参数
            JsonNode args = objectMapper.readTree(argumentsJson);
            Method method = info.method;
            Parameter[] parameters = method.getParameters();
            Object[] methodArgs = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                String paramName = parameters[i].getName();
                if (paramName.startsWith("arg")) {
                    // 如果编译时没有 -parameters 选项，使用参数位置作为 key
                    paramName = paramName.toLowerCase();
                }
                JsonNode argValue = args.get(paramName);
                if (argValue != null) {
                    methodArgs[i] = convertJsonNodeToType(argValue, parameters[i].getType());
                } else {
                    methodArgs[i] = null;
                }
            }

            // 反射调用
            Object result = method.invoke(info.provider, methodArgs);
            return result != null ? result.toString() : "工具执行完成，无返回值";

        } catch (Exception e) {
            logger.error("执行工具 {} 失败", toolName, e);
            return "工具执行错误: " + e.getMessage();
        }
    }

    /** 类型转换辅助 */
    private Object convertJsonNodeToType(JsonNode node, Class<?> targetType) {
        if (node == null)
            return null;
        if (targetType == String.class)
            return node.asText();
        if (targetType == int.class || targetType == Integer.class)
            return node.asInt();
        if (targetType == long.class || targetType == Long.class)
            return node.asLong();
        if (targetType == double.class || targetType == Double.class)
            return node.asDouble();
        if (targetType == boolean.class || targetType == Boolean.class)
            return node.asBoolean();
        return node.asText();
    }

    /** Java 类型 → OpenAI JSON Schema 类型 */
    private String simpleTypeName(Class<?> type) {
        if (type == String.class)
            return "string";
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class)
            return "integer";
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class)
            return "number";
        if (type == boolean.class || type == Boolean.class)
            return "boolean";
        return "string";
    }

    /** 工具方法信息 */
    private record ToolMethodInfo(BuiltinToolProvider provider, Method method) {
    }

    /** 获取所有工具定义的只读副本 */
    public List<Map<String, Object>> getToolDefinitions() {
        return Collections.unmodifiableList(toolDefinitions);
    }
}
