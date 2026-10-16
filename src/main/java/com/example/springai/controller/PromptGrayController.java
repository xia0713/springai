package com.example.springai.controller;

import com.example.springai.service.PromptGrayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提示词灰度演示（Day76）：
 * - /chat          灰度问答（同一 userId 永远同一版本）
 * - /stats         A/B 观测（分流分布应接近配置比例）
 * - /percent?p=    秒级放量/回滚（p=0 即回滚，不重启）
 */
@RestController
@RequestMapping("/api/prompt-gray")
@RequiredArgsConstructor
public class PromptGrayController {

    private final PromptGrayService service;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestParam String userId,
            @RequestParam String question) {
        return service.chat(userId, question);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @PostMapping("/percent")
    public Map<String, Object> setPercent(@RequestParam int p) {
        return service.setGrayPercent(p);
    }
}
