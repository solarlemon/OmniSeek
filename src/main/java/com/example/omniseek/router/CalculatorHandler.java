package com.example.omniseek.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CalculatorHandler implements RouteHandler {
    private static final Logger logger = LoggerFactory.getLogger(CalculatorHandler.class);

    private static final Pattern EXPR_PATTERN = Pattern.compile("([\\d\\s+\\-*/().^%]+)");
    private final ScriptEngine scriptEngine;

    public CalculatorHandler() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.scriptEngine = manager.getEngineByName("JavaScript");
    }

    @Override
    public RouteType getRouteType() {
        return RouteType.CALCULATOR;
    }

    @Override
    public void handle(String userId,
            String userMessage,
            List<Map<String, String>> history,
            WebSocketSession session,
            Consumer<String> onChunk,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        // TODO：实现计算器路由处理逻辑
    }
}
