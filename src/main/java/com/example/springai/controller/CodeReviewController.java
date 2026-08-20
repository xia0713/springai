package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.CodeReviewResult;
import com.example.springai.service.CodeReviewService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 提示词与攻击审查
 */
@RestController
@RequestMapping("/api/code-review")
public class CodeReviewController {

    private final CodeReviewService reviewService;

    public CodeReviewController(CodeReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 提交代码审查。
     * 请求体：{"code": "..."}  或直接传原始代码文本。
     */
    @PostMapping("/review")
    public CodeReviewResult review(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", body.get("text"));
        return reviewService.review(code);
    }

    /**
     * 提交代码审查（混合双路流式输出）：
     * - event=content: 原始文本片段（实时渲染用）
     * - event=done:    解析后的结构化 JSON
     * - event=error:   解析失败时的降级提示
     */
    @PostMapping(value = "/review/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reviewStream(@RequestBody Map<String, String> body) {
        String code = body.getOrDefault("code", body.get("text"));
        return reviewService.reviewStream(code);
    }

}

