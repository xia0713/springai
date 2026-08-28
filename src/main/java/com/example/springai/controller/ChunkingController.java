package com.example.springai.controller;

import com.example.springai.chunking.ChunkingResult;
import com.example.springai.chunking.ChunkingService;
import com.example.springai.chunking.ChunkingStrategy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 分块接口：暴露 5 种分块策略。
 */
@RestController
@RequestMapping("/api/chunking")
public class ChunkingController {

    private final ChunkingService chunkingService;

    public ChunkingController(ChunkingService chunkingService) {
        this.chunkingService = chunkingService;
    }

    /**
     * 列出 5 种分块策略
     * GET /api/chunking/strategies
     */
    @GetMapping("/strategies")
    public List<Map<String, String>> strategies() {
        return Arrays.stream(ChunkingStrategy.values())
                .map(s -> Map.of(
                        "name", s.name(),
                        "label", s.getLabel(),
                        "description", s.getDescription()))
                .toList();
    }

    /**
     * 上传文件分块（可选入库）
     * POST /api/chunking/upload?strategy=RECURSIVE_CHARACTER&store=false
     */
    @PostMapping("/upload")
    public ChunkingResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("strategy") ChunkingStrategy strategy,
            @RequestParam(value = "store", defaultValue = "false") boolean store) throws IOException {
        return chunkingService.chunk(file, strategy, store);
    }

    /**
     * 纯文本分块（可选入库），body 为原文
     * POST /api/chunking/text?strategy=SEMANTIC&store=false
     * Content-Type: text/plain
     */
    @PostMapping("/text")
    public ChunkingResult text(
            @RequestBody String text,
            @RequestParam("strategy") ChunkingStrategy strategy,
            @RequestParam(value = "store", defaultValue = "false") boolean store) {
        return chunkingService.chunk(text, strategy, store);
    }
}
