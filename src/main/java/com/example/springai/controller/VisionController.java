package com.example.springai.controller;

import com.example.springai.service.ImageUnderstandingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;

/**
 * 多模态接口
 */
@RestController
@RequestMapping("/api/vision")
public class VisionController {

    private final ImageUnderstandingService visionService;

    public VisionController(ImageUnderstandingService visionService) {
        this.visionService = visionService;
    }

    /**
     * 上传图片 → 获取描述
     * POST /api/vision/describe
     * 参数：file（图片文件）、question（可选，提问内容）
     */
    @PostMapping("/describe")
    public ResponseEntity<Map<String, String>> describeImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "question", required = false) String question
    ) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传图片文件"));
        }

        String description = visionService.describeImage(file, question);
        return ResponseEntity.ok(Map.of(
                "fileName", file.getOriginalFilename(),
                "description", description
        ));
    }

    /**
     * 通过 URL 描述图片
     * POST /api/vision/describe-url
     * 参数：imageUrl（图片 URL）、question（可选）
     */
    @PostMapping("/describe-url")
    public ResponseEntity<Map<String, String>> describeImageUrl(
            @RequestParam("imageUrl") String imageUrl,
            @RequestParam(value = "question", required = false) String question
    ) throws MalformedURLException {
        String description = visionService.describeImageUrl(imageUrl, question);
        return ResponseEntity.ok(Map.of(
                "imageUrl", imageUrl,
                "description", description
        ));
    }

    /**
     * 多图对比
     * POST /api/vision/compare
     * 参数：file1、file2（两张图片）、question（可选）
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, String>> compareImages(
            @RequestParam("file1") MultipartFile file1,
            @RequestParam("file2") MultipartFile file2,
            @RequestParam(value = "question", required = false) String question
    ) throws IOException {
        String result = visionService.compareImages(file1, file2, question);
        return ResponseEntity.ok(Map.of("comparison", result));
    }
}
