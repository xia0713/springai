package com.example.springai.controller;

import com.example.springai.service.MultiRecallRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 排查接口（Day46/49）。
 * <p>
 * 暴露多路召回的每一步中间结果，用于定位「检索不到 / 答案不准 / 幻觉」问题时，
 * 先分清是检索环节还是生成环节的问题。
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class RagDebugController {

    private final MultiRecallRagService multiRecallRagService;

    /**
     * 召回调试：看清每一步。
     * GET /api/debug/retrieve?question=报销要多久
     */
    @GetMapping("/retrieve")
    public Map<String, Object> debugRetrieve(@RequestParam String question) {
        return multiRecallRagService.debugRetrieve(question);
    }
}
