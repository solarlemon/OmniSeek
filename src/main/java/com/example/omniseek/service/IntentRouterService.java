package com.example.omniseek.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.omniseek.router.RouteType;
import com.example.omniseek.service.LLMIntentClassifier;

import java.util.regex.Pattern;

@Service
public class IntentRouterService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRouterService.class);

    private final LLMIntentClassifier llmClassifier;
    private final boolean llmEnabled;
    private final double confidenceThreshold;

    // 规则模式（示例，可按需扩展）
    private static final Pattern CALCULATOR_PATTERN = Pattern.compile(
            ".*(计算|等于|是多少|\\+|\\-|\\*|/|平方|根号|积分|求导).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(你好|您好|hi|hello|嗨|早上好|下午好|晚上好)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_SEARCH_PATTERN = Pattern.compile(
            ".*(新闻|天气|股票|汇率|今天|实时|最新).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL_CALLING_PATTERN = Pattern.compile(
            ".*(切片数量|切片总数|文档总数|知识库统计|知识库中|有多少.*文档|有多少.*切片|向量库|统计信息|切片数|文档数|多少用户|用户数量|几位用户|多少个用户|上传.*文件|文档.*数量|谁的会话|哪个会话|消息最多|几条消息|聊天记录|最近.*上传|统计|查询.*数据|数据库).*",
            Pattern.CASE_INSENSITIVE);

    public IntentRouterService(LLMIntentClassifier llmClassifier,
            @Value("${ai.intent.llm-enabled:true}") boolean llmEnabled,
            @Value("${ai.intent.llm-confidence-threshold:0.7}") double confidenceThreshold) {
        this.llmClassifier = llmClassifier;
        this.llmEnabled = llmEnabled;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 路由决策主入口
     * 
     * @param userMessage 用户消息
     * @return RouteType
     */
    public RouteType route(String userMessage) {
        // 1. 快速规则匹配
        RuleResult ruleResult = matchByRule(userMessage);

        // 2. 如果规则结果确定度高（例如计算器、问候语）或 LLM 未启用，直接返回
        if (!llmEnabled || ruleResult.isHighConfidence()) {
            logger.debug("规则决策，置信度高：{} -> {}", userMessage, ruleResult.routeType);
            return ruleResult.routeType;
        }

        // 3. 调用 LLM 辅助判断
        String llmIntent = llmClassifier.classifyIntent(userMessage);
        if (llmIntent != null) {
            try {
                RouteType llmRoute = RouteType.valueOf(llmIntent);
                // 如果 LLM 结果与规则结果一致，直接使用；否则可进一步融合（这里以 LLM 为准）
                logger.info("LLM 辅助决策：规则={}, LLM={}, 最终={}", ruleResult.routeType, llmRoute, llmRoute);
                return llmRoute;
            } catch (IllegalArgumentException e) {
                logger.warn("LLM 返回非法意图：{}", llmIntent);
            }
        }

        // 4. 降级：使用规则结果或默认知识库路由
        logger.warn("LLM 调用失败，降级使用规则结果：{}", ruleResult.routeType);
        return ruleResult.routeType;
    }

    private RuleResult matchByRule(String message) {
        // 计算器优先级最高
        if (CALCULATOR_PATTERN.matcher(message).matches()) {
            return new RuleResult(RouteType.CALCULATOR, true);
        }
        // MCP 工具调用（知识库统计、系统信息等）
        if (TOOL_CALLING_PATTERN.matcher(message).matches()) {
            return new RuleResult(RouteType.TOOL_CALLING, true);
        }
        // 问候/闲聊 -> 直接回答
        if (GREETING_PATTERN.matcher(message).matches()) {
            return new RuleResult(RouteType.DIRECT_ANSWER, true);
        }
        // 明显需要网络搜索的
        if (WEB_SEARCH_PATTERN.matcher(message).matches()) {
            // 这里置信度可设为中等，因为可能误判
            return new RuleResult(RouteType.WEB_SEARCH, false);
        }
        // 默认知识库路由，置信度低
        return new RuleResult(RouteType.KNOWLEDGE_BASE, false);
    }

    private static class RuleResult {
        RouteType routeType;
        boolean highConfidence; // true 表示规则非常确定，无需 LLM

        RuleResult(RouteType routeType, boolean highConfidence) {
            this.routeType = routeType;
            this.highConfidence = highConfidence;
        }

        boolean isHighConfidence() {
            return highConfidence;
        }
    }
}