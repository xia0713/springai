package com.example.springai.controller;

import com.example.springai.service.CostOptimizedChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 能力统一网关（Day73）：统一接入 + 统一鉴权 + 统一审计。
 * <p>
 * 管控内核（Day71 熔断 / Day72 缓存+路由）在门面后面统一生效 ——
 * 门面后换实现（换模型/改路由），接入方零改动，这才是中台。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-gateway")
@RequiredArgsConstructor
public class AiGatewayController {

    private final CostOptimizedChatService chatService;

    @Value("${app.ai-gateway.api-key}")
    private String validKey;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestParam String question) {

        long start = System.currentTimeMillis();

        // ① 统一鉴权（Day53 能力复用）
        if (apiKey == null || !apiKey.equals(validKey)) {
            log.warn("[ai-gateway] 鉴权失败，key={}", apiKey == null ? "null" : "***");
            return Map.of("code", 401, "message", "API Key 无效");
        }

        // ② 统一管控：内核 = Day72 的缓存+路由（模型层的 Day71 熔断同样生效）
        Map<String, Object> result = chatService.chat(question);

        // ③ 统一审计（Day82 审计体系的雏形）：谁 / 什么 / 走哪条路 / 花了多少
        log.info("[ai-gateway] key=***{}, q={}, route={}, tokens={}, costMs={}",
                validKey.substring(validKey.length() - 4), question,
                result.get("route"), result.get("tokens"),
                System.currentTimeMillis() - start);

        return result;
    }
}
