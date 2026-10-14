package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本优化服务（Day72）：精确缓存 + 复杂度路由 + 用量统计。
 * <p>
 * 复用 Day71 的两个 ChatClient，角色重新定义：
 *   qwen3.8-27b = 大模型（复杂问题） / deepseek-v4-flash = 小模型（简单问题）。
 * <p>
 * ⚠️ 缓存 key 必须归一化；本服务无个性化上下文，若未来加入用户维度，
 *    key 必须含 userId（否则用户 A 的答案返回给用户 B —— Day45/59 的隔离教训）。
 */
@Slf4j
@Service
public class CostOptimizedChatService {

    private final ChatClient bigModel;    // qwen3.8-27b：复杂问题
    private final ChatClient smallModel;  // deepseek-v4-flash：简单问题

    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    // ---- 统计计数器（证明省了多少的口径）----
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong smallModelCalls = new AtomicLong();
    private final AtomicLong bigModelCalls = new AtomicLong();
    private final AtomicLong tokensSavedByCache = new AtomicLong();

    private static final String[] COMPLEX_HINTS = {
            "分析", "总结", "对比", "为什么", "怎么", "如何", "方案", "写一个", "解释", "设计"
    };

    public CostOptimizedChatService(@Qualifier("qwenChatClient") ChatClient bigModel,
                                    @Qualifier("deepseekChatClient") ChatClient smallModel) {
        this.bigModel = bigModel;
        this.smallModel = smallModel;
    }

    /**
     * 带成本策略的问答：缓存命中 → 0 token；未命中 → 按复杂度路由大小模型。
     */
    public Map<String, Object> chat(String question) {
        String key = normalize(question);

        // ① 精确缓存命中：0 token，不调用任何模型
        CachedAnswer hit = cache.get(key);
        if (hit != null) {
            cacheHits.incrementAndGet();
            tokensSavedByCache.addAndGet(hit.totalTokens());
            log.info("[cost] 缓存命中（节省 {} tokens）", hit.totalTokens());
            return Map.of(
                    "answer", "[缓存·0token] " + hit.answer(),
                    "route", "CACHE",
                    "tokens", 0);
        }

        // ② 复杂度路由：简单 → 小模型，复杂 → 大模型
        boolean simple = isSimple(question);
        ChatClient client = simple ? smallModel : bigModel;
        if (simple) {
            smallModelCalls.incrementAndGet();
        } else {
            bigModelCalls.incrementAndGet();
        }

        ChatResponse resp = client.prompt().user(question).call().chatResponse();
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

    /** 归一化缓存 key：去首尾空格 + 统一小写（英文场景），避免"你好"和"你好 " 两个 key */
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

    /** 用量统计口径：谁走了哪条路、缓存省了多少 */
    public Map<String, Object> stats() {
        return Map.of(
                "cacheHits", cacheHits.get(),
                "smallModelCalls", smallModelCalls.get(),
                "bigModelCalls", bigModelCalls.get(),
                "tokensSavedByCache", tokensSavedByCache.get(),
                "cacheSize", cache.size()
        );
    }

    /** 缓存条目：答案 + 当时消耗的 tokens（命中时记为"省下的"） */
    private record CachedAnswer(String answer, int totalTokens) {}
}
