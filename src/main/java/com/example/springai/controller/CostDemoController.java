package com.example.springai.controller;

import com.example.springai.service.CostOptimizedChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 成本优化演示（Day72）：缓存命中 0 token + 复杂度路由大小模型 + 用量统计。
 */
@RestController
@RequestMapping("/api/cost")
@RequiredArgsConstructor
public class CostDemoController {

    private final CostOptimizedChatService chatService;

    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String question) {
        return chatService.chat(question);
    }

    /** 用量统计：缓存命中率 / 大小模型分布 / 累计节省 tokens */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return chatService.stats();
    }
}
