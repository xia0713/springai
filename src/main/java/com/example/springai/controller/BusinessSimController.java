package com.example.springai.controller;

import com.example.springai.sdk.AiSdkClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟"另一个业务系统"（Day73）：通过 SDK 走 HTTP 调用 AI 网关。
 * <p>
 * 证明接入方只需要 SDK，不需要懂 Spring AI ——
 * 本类的全部接入代码 = 构造 AiSdkClient + ai.chat()，5 行以内。
 */
@RestController
@RequestMapping("/api/biz-sim")
public class BusinessSimController {

    private final AiSdkClient ai;

    public BusinessSimController(@Value("${app.ai-gateway.api-key}") String apiKey) {
        // ===== 业务系统的全部接入代码（构造 + 随用随调）=====
        this.ai = new AiSdkClient("http://localhost:8080", apiKey);
    }

    /** 业务系统的某个普通功能，内部顺手用了 AI 能力 */
    @GetMapping("/report")
    public Map<String, Object> report(
            @RequestParam(defaultValue = "分析一下退货政策的核心要点") String question) {
        long start = System.currentTimeMillis();
        String answer = ai.chat(question);          // ← 业务代码里唯一的 AI 调用
        return Map.of(
                "answer", answer,
                "costMs", System.currentTimeMillis() - start
        );
    }
}
