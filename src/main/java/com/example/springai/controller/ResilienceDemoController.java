package com.example.springai.controller;

import com.example.springai.service.ResilientChatService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/resilience")
@RequiredArgsConstructor
public class ResilienceDemoController {

    private final ResilientChatService chatService;
    private final CircuitBreakerRegistry cbRegistry;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestParam String question,
            @RequestParam(defaultValue = "false") boolean chaos) {
        long start = System.currentTimeMillis();
        String answer = chatService.chat(question, chaos);
        return Map.of(
                "answer", answer,
                "costMs", System.currentTimeMillis() - start   // 熔断 OPEN 时应毫秒级
        );
    }

    /** 观察熔断器状态：CLOSED/OPEN/HALF_OPEN + 失败率（Resilience4j 标准公开 API） */
    @GetMapping("/cb-status")
    public Map<String, Object> cbStatus() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("llm");
        CircuitBreaker.Metrics m = cb.getMetrics();
        return Map.of(
                "state", cb.getState().name(),
                "failureRate", String.format("%.1f%%", m.getFailureRate()),
                "bufferedCalls", m.getNumberOfBufferedCalls(),
                "failedCalls", m.getNumberOfFailedCalls()
        );
    }
}
