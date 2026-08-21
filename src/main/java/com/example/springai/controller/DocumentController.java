package com.example.springai.controller;

import com.example.springai.service.DocumentProcessingService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentProcessingService processingService;

    public DocumentController(DocumentProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping("/upload")
    public UploadResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        List<Document> chunks = processingService.process(file);

        // 返回解析结果摘要
        return new UploadResult(
                file.getOriginalFilename(),
                chunks.size(),                    // 分块数量
                chunks.stream()
                        .map(Document::getText)
                        .mapToInt(String::length)
                        .sum()                        // 总字符数
        );
    }

    public record UploadResult(String filename, int chunkCount, int totalChars) {}
}

