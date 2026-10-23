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
 * 成本优化服务（Day72 建，Day78 升级为 Micrometer 指标）。
 * <p>
 * 指标三类型示范（Day78 1.2）：
 * - Counter：cacheHits / 模型调用 / tokensSaved（只增；tokensSaved.increment(N) 记数量）
 * - Gauge：cacheSize（瞬时值，可升可降）
 * - Timer：LLM 耗时分布（publishPercentileHistogram 才能在 Prometheus 算 P95 —— Day78 的坑）
 * <p>
 * 标签只用有限枚举（model=small/big）—— 高基数红线（question/userId 禁入）。
 * stats() 端点保留：人看 JSON、Prometheus 看端点，两不误。
 * ⚠️ 缓存 key 归一化 + 用户维度红线（Day72）：加入个性化时 key 必须含 userId。
 */
@Slf4j
@Service
public class CostOptimizedChatService {

    private final ChatClient bigModel;    // qwen3.8-27b：复杂问题
    private final ChatClient smallModel;  // deepseek-v4-flash：简单问题

    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    // ---- Micrometer 指标（Day78）：AtomicLong 全部退役 ----
    private final Counter cacheHitCounter;
    private final Counter smallModelCounter;
    private final Counter bigModelCounter;
    private final Counter tokensSavedCounter;
    private final Timer smallModelTimer;
    private final Timer bigModelTimer;

    private static final String[] COMPLEX_HINTS = {
            "分析", "总结", "对比", "为什么", "怎么", "如何", "方案", "写一个", "解释", "设计"
    };

    public CostOptimizedChatService(@Qualifier("qwenChatClient") ChatClient bigModel,
                                    @Qualifier("deepseekChatClient") ChatClient smallModel,
                                    MeterRegistry registry) {
        this.bigModel = bigModel;
        this.smallModel = smallModel;

        // Counter：只增累计；tokensSaved.increment(N) 记数量
        this.cacheHitCounter = Counter.builder("ai.chat.cache.hits")
                .description("缓存命中次数").register(registry);
        this.smallModelCounter = Counter.builder("ai.chat.model.calls")
                .tag("model", "small").description("小模型调用次数").register(registry);
        this.bigModelCounter = Counter.builder("ai.chat.model.calls")
                .tag("model", "big").description("大模型调用次数").register(registry);
        this.tokensSavedCounter = Counter.builder("ai.chat.tokens.saved")
                .description("缓存命中累计节省的 tokens").register(registry);

        // Timer：开 percentileHistogram，Prometheus 才能算 P95
        this.smallModelTimer = Timer.builder("ai.chat.llm.duration")
                .tag("model", "small").description("小模型调用耗时")
                .publishPercentileHistogram().register(registry);
        this.bigModelTimer = Timer.builder("ai.chat.llm.duration")
                .tag("model", "big").description("大模型调用耗时")
                .publishPercentileHistogram().register(registry);

        // Gauge：瞬时值（cache 大小可升可降）
        Gauge.builder("ai.chat.cache.size", cache, c -> c.size())
                .description("当前缓存条目数").register(registry);
    }

    /**
     * 带成本策略的问答：缓存命中 → 0 token；未命中 → 按复杂度路由大小模型（带耗时埋点）。
     */
    public Map<String, Object> chat(String question) {
        String key = normalize(question);

        // ① 精确缓存命中：0 token，不调用任何模型
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

        // ② 复杂度路由：简单 → 小模型，复杂 → 大模型（Timer 记录 LLM 耗时分布）
        boolean simple = isSimple(question);
        ChatClient client = simple ? smallModel : bigModel;
        Timer timer = simple ? smallModelTimer : bigModelTimer;
        if (simple) {
            smallModelCounter.increment();
        } else {
            bigModelCounter.increment();
        }

        ChatResponse resp = timer.record(() ->
                client.prompt().user(question).call().chatResponse());
        String answer = resp.getResult().getOutput().getText();

        // ③ 真实用量计量（已验证 API：ChatResponseMetadata.getUsage()）
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
                "cacheSize", cache.size()
        );
    }

    /** 缓存条目：答案 + 当时消耗的 tokens（命中时记为"省下的"） */
    private record CachedAnswer(String answer, int totalTokens) {}
}
