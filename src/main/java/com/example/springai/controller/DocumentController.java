package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.service.DocumentProcessingService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentProcessingService processingService;
    private final VectorStore vectorStore;

    public DocumentController(DocumentProcessingService processingService, VectorStore vectorStore, ChatClient ragChatClient) {
        this.processingService = processingService;
        this.vectorStore = vectorStore;
        this.ragChatClient = ragChatClient;
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


    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public String streamsearch(
            @RequestParam String query) {
        return processingService.streamsearch(query);
    }



    /**
     * 相似度搜索，返回最相关的 Top3
     */
    @GetMapping("/vector/search")
    public List<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam String category,
            @RequestParam(defaultValue = "3") Integer topK) {

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.6)              // 相似度阈值，低于此值不返回（默认 0.0，即全部返回）
                        .filterExpression(StringUtils.isBlank(category)?null:"category == '"+category+"'")  // 元数据过滤表达式
                        .build()
        );

        return results.stream()
                .map(doc -> Map.of(
                        "content", doc.getText(),
                        "score", doc.getMetadata().getOrDefault("distance", 0)
                ))
                .toList();
    }


    private final ChatClient ragChatClient;



    @GetMapping("/ask")
    public QaResponse ask(@RequestParam String query) {
        // 就这一行！QuestionAnswerAdvisor 自动完成检索+生成
        String answer = ragChatClient.prompt()
                .user(query)
                .call()
                .content();
        return new QaResponse(answer);
    }



    public record QaRequest(String question) {}
    public record QaResponse(String answer) {}


    public record UploadResult(String filename, int chunkCount, int totalChars) {}
}

