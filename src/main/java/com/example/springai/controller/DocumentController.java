package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.service.DocumentProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
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



    public record QaResponse(String answer) {}


    public record UploadResult(String filename, int chunkCount, int totalChars) {}




    @GetMapping("/askWithSource")
    public ChatAnswer askWithSource(String question) {
        // ① 生成答案（Day 32 的 QuestionAnswerAdvisor 自动完成检索+生成）
        ChatResponse response = ragChatClient.prompt()
                .user(question)
                .call()
                .chatResponse();

        String answer = response.getResult().getOutput().getText();

        // ② 提取来源（关键：从 response metadata 拿检索文档）
        List<Source> sources = extractSources(response);

        return new ChatAnswer(answer, sources);
    }

    private List<Source> extractSources(ChatResponse response) {
        long start = System.currentTimeMillis();

        // 从 response metadata 取出检索到的文档
        List<Document> documents = response.getMetadata()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        log.info("RAG 查询 | 问题: {} | 检索块数: {} | 耗时: {}ms","question",
//                question,
                documents == null ? 0 : documents.size(),
                System.currentTimeMillis() - start
        );

        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // 去重：多个 chunk 可能来自同一文件，一个文件只保留一条
        Map<String, Source> byFile = new LinkedHashMap<>();
        for (Document doc : documents) {

            log.info("  检索块 | 相似度: {} | 来源: {} | 内容: {}",
                    doc.getMetadata().getOrDefault("distance", "?"),
                    doc.getMetadata().getOrDefault("source", "?"),
                    doc.getText().substring(0, Math.min(50, doc.getText().length()))
            );


            String file = String.valueOf(
                    doc.getMetadata().getOrDefault("source", "知识库")
            );
            byFile.computeIfAbsent(file, k ->
                    new Source(file, excerpt(doc.getText()))
            );
        }
        return new ArrayList<>(byFile.values());
    }

    // 提取文本片段的前 100 字作为摘要
    private String excerpt(String text) {
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    // 返回结构：答案 + 来源列表
    public record ChatAnswer(String answer, List<Source> sources) {}
    public record Source(String filename, String excerpt) {}

}

