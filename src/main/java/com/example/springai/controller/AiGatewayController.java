package com.example.springai.controller;

import com.example.springai.service.CostOptimizedChatService;
import com.example.springai.util.SensitiveDataMasker;
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
 * AI 能力统一网关（Day73 建，Day80 简化）：
 * 统一接入 + 统一鉴权 + 统一审计。
 * <p>
 * Day80 方案 A：安全管线（拦截/双向脱敏）已下沉到 CostOptimizedChatService 服务内核，
 * 网关不再重复做安全校验 —— 所有入口（含未来新增）自动被保护。
 * 审计纪律：日志只记脱敏后的问题（Day79 红线）。
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

        // ② 统一管控：安全（拦截/脱敏）+ 成本（缓存/路由）都在服务内核，透传即可
        Map<String, Object> result = chatService.chat(question);

        // ③ 统一审计：问题先脱敏再落日志（Day79 红线），谁/什么/走哪条路/花了多少
        log.info("[ai-gateway] key=***{}, q={}, route={}, tokens={}, costMs={}",
                validKey.substring(validKey.length() - 4), SensitiveDataMasker.mask(question),
                result.get("route"), result.get("tokens"),
                System.currentTimeMillis() - start);

        return result;
    }
}
