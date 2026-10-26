package com.example.springai.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成本优化服务（Day72 建，Day78 升级 Micrometer，Day80 管线下沉）。
 * <p>
 * Day80 方案 A：安全管线从 Controller 下沉到服务内核 ——
 * 所有调用方（网关/直连/未来入口）自动被保护，想绕都绕不过。
 * 流程：审核拦截 → 入站脱敏 → 缓存(脱敏 key) → 路由调用 → 出站脱敏 → 缓存存脱敏答案。
 * <p>
 * ⚠️ 准确性边界（Day80 讨论结论）：单向脱敏对"知识/咨询/生成"类零影响；
 * 对"要用真实敏感数据执行操作"的场景（如按手机号查订单）会失效 ——
 * 生产解法是可逆脱敏（占位符化 + 本地映射表 + 工具执行前还原），记入综合项目。
 */
@Slf4j
@Service
public class CostOptimizedChatService {

    private final ChatClient bigModel;    // qwen3.8-27b：复杂问题
    private final ChatClient smallModel;  // deepseek-v4-flash：简单问题
    private final ContentSafetyService contentSafetyService;

    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    // ---- Micrometer 指标（Day78）----
    private final Counter cacheHitCounter;
    private final Counter smallModelCounter;
    private final Counter bigModelCounter;
    private final Counter tokensSavedCounter;
    private final Counter blockedCounter;
    private final Timer smallModelTimer;
    private final Timer bigModelTimer;

    private static final String[] COMPLEX_HINTS = {
            "分析", "总结", "对比", "为什么", "怎么", "如何", "方案", "写一个", "解释", "设计"
    };

    public CostOptimizedChatService(@Qualifier("qwenChatClient") ChatClient bigModel,
                                    @Qualifier("deepseekChatClient") ChatClient smallModel,
                                    ContentSafetyService contentSafetyService,
                                    MeterRegistry registry) {
        this.bigModel = bigModel;
        this.smallModel = smallModel;
        this.contentSafetyService = contentSafetyService;

        this.cacheHitCounter = Counter.builder("ai.chat.cache.hits")
                .description("缓存命中次数").register(registry);
        this.smallModelCounter = Counter.builder("ai.chat.model.calls")
                .tag("model", "small").description("小模型调用次数").register(registry);
        this.bigModelCounter = Counter.builder("ai.chat.model.calls")
                .tag("model", "big").description("大模型调用次数").register(registry);
        this.tokensSavedCounter = Counter.builder("ai.chat.tokens.saved")
                .description("缓存命中累计节省的 tokens").register(registry);
        this.blockedCounter = Counter.builder("ai.chat.safety.blocked")
                .description("内容安全拦截次数").register(registry);

        this.smallModelTimer = Timer.builder("ai.chat.llm.duration")
                .tag("model", "small").description("小模型调用耗时")
                .publishPercentileHistogram().register(registry);
        this.bigModelTimer = Timer.builder("ai.chat.llm.duration")
                .tag("model", "big").description("大模型调用耗时")
                .publishPercentileHistogram().register(registry);

        Gauge.builder("ai.chat.cache.size", cache, c -> c.size())
                .description("当前缓存条目数").register(registry);
    }

    /**
     * 带成本+安全策略的问答（Day80 方案 A）：
     * 拦截 → 入站脱敏 → 缓存(脱敏 key) → 路由调用 → 出站脱敏 → 缓存存脱敏答案。
     * 拦截时返回 code=403 的响应体，调用方透传即可。
     */
    public Map<String, Object> chat(String question) {
        // ① Day80 安全管线（服务内核层，保护所有入口）：拦截 → 入站脱敏
        ContentSafetyService.SafetyResult safety = contentSafetyService.checkInput(question);
        if (!safety.passed()) {
            blockedCounter.increment();
            log.info("[cost] 内容安全拦截（{}），不调 LLM", safety.reason());
            return Map.of(
                    "code", 403,
                    "message", safety.reason(),
                    "route", "BLOCKED",
                    "tokens", 0);
        }
        String masked = safety.maskedQuestion();   // 之后全程只用脱敏文本
        String key = normalize(masked);            // 缓存 key 也是脱敏后的

        // ② 精确缓存命中：0 token
        CachedAnswer hit = cache.get(key);
        if (hit != null) {
            cacheHitCounter.increment();
            tokensSavedCounter.increment(hit.totalTokens());
            log.info("[cost] 缓存命中（节省 {} tokens）", hit.totalTokens());
            return Map.of(
                    "answer", "[缓存·0token] " + hit.answer(),
                    "route", "CACHE",
                    "tokens", 0);
        }

        // ③ 复杂度路由：简单 → 小模型，复杂 → 大模型
        boolean simple = isSimple(masked);
        ChatClient client = simple ? smallModel : bigModel;
        Timer timer = simple ? smallModelTimer : bigModelTimer;
        if (simple) {
            smallModelCounter.increment();
        } else {
            bigModelCounter.increment();
        }

        ChatResponse resp = timer.record(() ->
                client.prompt().user(masked).call().chatResponse());   // 发给 LLM 的就是脱敏文本
        String answer = contentSafetyService.maskOutput(              // 出站脱敏
                resp.getResult().getOutput().getText());

        // ④ 真实用量计量 + 缓存存【脱敏后】的答案（缓存里也没有敏感原文）
        Usage usage = resp.getMetadata().getUsage();
        int totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();

        cache.put(key, new CachedAnswer(answer, totalTokens));

        return Map.of(
                "answer", answer,
                "route", simple ? "SMALL(deepseek)" : "BIG(qwen)",
                "tokens", totalTokens
        );
    }

    /** 归一化缓存 key：去首尾空格 + 统一小写（英文场景） */
    private String normalize(String q) {
        return q == null ? "" : q.strip().toLowerCase();
    }

    /** 规则路由：短 && 无复杂意图词 → 简单（走小模型） */
    private boolean isSimple(String q) {
        if (q == null || q.length() > 30) {
            return false;
        }
        for (String hint : COMPLEX_HINTS) {
            if (q.contains(hint)) {
                return false;
            }
        }
        return true;
    }

    /** 用量统计口径：人看 JSON（Prometheus 看指标端点，两不误） */
    public Map<String, Object> stats() {
        return Map.of(
                "cacheHits", cacheHitCounter.count(),
                "smallModelCalls", smallModelCounter.count(),
                "bigModelCalls", bigModelCounter.count(),
                "tokensSavedByCache", tokensSavedCounter.count(),
                "blocked", blockedCounter.count(),
                "cacheSize", cache.size()
        );
    }

    /** 缓存条目：脱敏后的答案 + 当时消耗的 tokens */
    private record CachedAnswer(String answer, int totalTokens) {}
}
