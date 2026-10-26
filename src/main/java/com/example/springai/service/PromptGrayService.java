package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 提示词灰度服务（Day76）：多版本注册表 + 按用户 hash 灰度 + 运行时秒级回滚 + A/B 计数。
 * <p>
 * 核心认知：提示词是"运行时变更的代码"—— 所以需要版本管理 + 灰度 + 秒级回滚。
 * hash 稳定性是 A/B 的生命线：同一用户永远同一版本。
 * <p>
 * ⚠️ 跨天交互坑（Day72）：提示词切版本后，缓存里还是旧版答案 ——
 *    综合项目做缓存时 key 必须加 prompt 版本维度，或切版本时清缓存。
 * 生产演进：注册表落库（提示词=数据不是代码）+ 网关统一取用（Day73 统一管控的延伸）。
 */
@Slf4j
@Service
public class PromptGrayService {

    private final ChatClient chatClient;

    /** 提示词注册表：promptKey → (version → 文本)。生产从 DB/配置中心加载，演示内置 */
    private volatile Map<String, Map<String, String>> promptRegistry;
    /** 灰度比例（0-100）：volatile 保证多线程立即可见 —— 秒级回滚的物理基础 */
    private volatile int grayPercent;
    private volatile String defaultVersion;

    /** A/B 观测口径：per-version 调用计数 */
    private final AtomicLong v1Calls = new AtomicLong();
    private final AtomicLong v2Calls = new AtomicLong();
    /** 分流一致性验证：userId → 最近版本（观测"同一用户是否稳定"） */
    private final Map<String, String> userVersionMap = new ConcurrentHashMap<>();

    public PromptGrayService(@Qualifier("primaryChatClient") ChatClient chatClient,
                             @Value("${app.prompt-gray.percent}") int percent,
                             @Value("${app.prompt-gray.default-version}") String defaultVersion) {
        this.chatClient = chatClient;
        this.grayPercent = percent;
        this.defaultVersion = defaultVersion;
        // 生产：注册表从 DB/配置中心加载；演示：内置两个版本（文本与 application.yaml 语义一致）
        this.promptRegistry = Map.of(
                "knowledge-qa", Map.of(
                        "v1", """
                                你是企业知识库助手。基于已知信息回答，先给结论再给依据，简洁准确。
                                """,
                        "v2", """
                                你是企业知识库助手（v2 实验版）。
                                回答结构固定为：【结论】→【依据】→【补充说明】。
                                依据必须引用资料来源；资料中没有的信息明确说"资料未覆盖"，禁止编造。
                                """));
    }

    /** 灰度问答：路由 → 取对应版本提示词 → 生成 → 记录版本（审计需要） */
    public Map<String, Object> chat(String userId, String question) {
        String version = route(userId);
        if ("v1".equals(version)) {
            v1Calls.incrementAndGet();
        } else {
            v2Calls.incrementAndGet();
        }
        userVersionMap.put(userId, version);

        String systemPrompt = promptRegistry
                .getOrDefault("knowledge-qa", Map.of())
                .getOrDefault(version,
                        promptRegistry.get("knowledge-qa").get(defaultVersion));

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        return Map.of("answer", answer, "promptVersion", version);
    }

    /** 按用户 hash 灰度路由：确定性 —— 同一用户永远同一版本（A/B 的生命线） */
    public String route(String userId) {
        int bucket = Math.abs(userId.hashCode() % 100);
        return bucket < grayPercent ? "v2" : "v1";
    }

    /** 秒级回滚/放量：改一个数字，不重启。回滚 = setGrayPercent(0) */
    public Map<String, Object> setGrayPercent(int percent) {
        if (percent < 0 || percent > 100) {
            return Map.of("ok", false, "message", "比例必须在 0-100");
        }
        int old = this.grayPercent;
        this.grayPercent = percent;
        log.warn("[prompt-gray] 灰度比例 {}% → {}%（{}）", old, percent, percent == 0 ? "回滚" : "放量");
        return Map.of("ok", true, "oldPercent", old, "newPercent", percent);
    }

    /** A/B 观测口径：分流分布（应接近配置比例）+ 一致性追踪 */
    public Map<String, Object> stats() {
        long v1 = v1Calls.get(), v2 = v2Calls.get();
        long total = v1 + v2;
        return Map.of(
                "grayPercent", grayPercent,
                "v1Calls", v1,
                "v2Calls", v2,
                "v2Share", total == 0 ? "0%" : String.format("%.1f%%", v2 * 100.0 / total),
                "trackedUsers", userVersionMap.size()
        );
    }
}
