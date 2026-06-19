package com.example.omniseek.router;

public enum RouteType {
    KNOWLEDGE_BASE("knowledge_base", "RAG知识库"),
    WEB_SEARCH("web_search", "联网搜索"),
    CALCULATOR("calculator", "计算器"),
    TOOL_CALLING("tool_calling", "MCP工具调用"),
    DIRECT_ANSWER("direct_answer", "直接回答");

    private final String code;
    private final String description;

    RouteType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RouteType fromCode(String code) {
        for (RouteType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return DIRECT_ANSWER;
    }
}
