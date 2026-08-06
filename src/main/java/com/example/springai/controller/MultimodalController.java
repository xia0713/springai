package com.example.springai.controller;

import com.example.springai.service.MultimodalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/multimodal")
@RequiredArgsConstructor
public class MultimodalController {

    private final MultimodalService multimodalService;

    /**
     * 通过 URL 分析图片
     * GET /api/multimodal/analyze?url=https://xxx.com/image.jpg&question=图片里有什么
     */
    @GetMapping("/analyze")
    public Map<String, String> analyzeByUrl(
            @RequestParam String url,
            @RequestParam(required = false, defaultValue = "请详细描述这张图片的内容") String question) {
        String result = multimodalService.analyzeImageUrl(url, question);
        return Map.of("description", result);
    }

    /**
     * 通过上传文件分析图片
     * POST /api/multimodal/analyze (multipart/form-data)
     */
    @PostMapping("/analyze")
    public Map<String, String> analyzeByFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "请详细描述这张图片的内容") String question) {
        String result = multimodalService.analyzeImageFile(file, question);
        return Map.of("description", result);
    }
}