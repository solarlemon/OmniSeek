package com.example.omniseek.mcp.core;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置工具注册表
 * 自动发现并注册所有实现 BuiltinToolProvider 的工具
 */
@Slf4j
@Component
public class BuiltinToolRegistry {

    private final List<BuiltinToolProvider> toolProviders;
    private final Map<String, Class<?>> registeredToolClasses = new ConcurrentHashMap<>();
    private final Map<String, String> displayNames = new ConcurrentHashMap<>();

    public BuiltinToolRegistry(List<BuiltinToolProvider> toolProviders) {
        this.toolProviders = toolProviders;
    }

    @PostConstruct
    public void init() {
        log.info("开始注册内置工具，发现 {} 个", toolProviders.size());
        for (BuiltinToolProvider provider : toolProviders) {
            String name = provider.getToolName();
            registeredToolClasses.put(name, provider.getClass());
            displayNames.put(name, provider.getDisplayName());
            log.info("注册工具: {} ({})", name, provider.getDisplayName());
        }
    }

    /** 获取所有工具实例（用于注入到 Agent） */
    public List<Object> getAllToolObjects() {
        return registeredToolClasses.keySet().stream()
                .map(this::createInstance)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Object createInstance(String toolName) {
        try {
            return registeredToolClasses.get(toolName)
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("创建工具实例失败: {}", toolName, e);
            return null;
        }
    }

    public boolean hasTool(String toolName) {
        return registeredToolClasses.containsKey(toolName);
    }
}
