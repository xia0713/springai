package com.example.springai.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ResilientChatService {

    private final ChatClient primary;
    private final ChatClient backup;

    /** 降级用：近期问答缓存（演示级；Day72 升级成正式缓存策略） */
    private final Map<String, String> answerCache = new ConcurrentHashMap<>();

    public ResilientChatService(@Qualifier("primaryChatClient") ChatClient primary, @Qualifier("deepseekChatClient") ChatClient backup) {
        this.primary = primary;
        this.backup = backup;
    }

    @RateLimiter(name = "llm")
    @CircuitBreaker(name = "llm",fallbackMethod = "fallbackToBackup")
    public String chat(String question, boolean chaos){
        if(chaos){
            throw new IllegalStateException("模拟主模型故障");
        }
        String anser=primary.prompt().user(question).call().content();
        answerCache.put(question,anser); // 成功答案进缓存，供降级用
        return anser;
    }

    public String fallbackToBackup(String question, boolean chaos, Throwable t){
        log.warn("fallbackToBackup",t);
        try {
            String content = backup.prompt().user(question).call().content();
            return "[备用模型] " +content;
        }catch (Exception e){
            // 降级②：备用也挂 → 查缓存 → 兜底话术（统一出口）
            String cached = answerCache.get(question);
            if (cached != null) {
                return "[缓存答案] " + cached;
            }
            log.error("[resilience] 备用模型也不可用({})，走兜底话术", e.getMessage());
            return "[服务降级] AI 服务繁忙，您的问题已记录，恢复后将优先处理，请稍后重试。";

        }

    }
}
