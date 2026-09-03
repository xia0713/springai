package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.model.DocumentBatchTask;
import com.example.springai.service.BatchDocumentService;
import com.example.springai.service.DocumentManagementService;
import com.example.springai.service.DocumentProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
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
    private final BatchDocumentService batchService;   // 构造器里加这个依赖
    private final DocumentManagementService managementService;   // 构造器追加

    public DocumentController(DocumentProcessingService processingService, VectorStore vectorStore, BatchDocumentService batchService, DocumentManagementService managementService, ChatClient ragChatClient) {
        this.processingService = processingService;
        this.vectorStore = vectorStore;
        this.batchService = batchService;
        this.managementService = managementService;
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


    /**
     * 批量上传：多文件，秒回 taskId。
     * ⚠️ currentUser 仅演示用，生产从登录态拿。
     */
    @PostMapping("/batch/upload")
    public Map<String, String> batchUpload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String currentUser) throws IOException {
        String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
        String taskId = batchService.submit(files, owner);
        return Map.of(
                "taskId", taskId,
                "total", String.valueOf(files.size()),
                "owner", owner,
                "hint", "轮询 GET /api/documents/batch/" + taskId + " 查进度"
        );
    }

    /** 查询批次进度 */
    @GetMapping("/batch/{taskId}")
    public DocumentBatchTask batchStatus(@PathVariable String taskId) {
        return batchService.getStatus(taskId);
    }


    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public String streamsearch(
            @RequestParam String query) {
        return processingService.streamsearch(query);
    }



    /**
     * 相似度搜索，返回最相关的 Top3。
     * Day45: 加 owner 权限过滤 —— 只能看到 owner==当前用户 或 public 的文档。
     * ⚠️ currentUser 仅用于本地演示，生产必须从登录态/JWT 取，不能信任前端参数。
     */
    @GetMapping("/vector/search")
    public List<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String currentUser,
            @RequestParam(defaultValue = "3") Integer topK) {

        // 权限过滤：无 currentUser 则只见 public 的文档
        // ⚠️ and()/or() 要求 Op 类型，不能拿 build() 后的 Expression 去 and，全程用 Op 最后再 build
        String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op op = b.eq("owner", owner);

        // 可选叠加 category 过滤：(owner==我) AND (category==x)
        if (StringUtils.isNotBlank(category)) {
            op = b.and(op, b.eq("category", category));
        }
        Filter.Expression filter = op.build();

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.6)              // 相似度阈值，低于此值不返回（默认 0.0，即全部返回）
                        .filterExpression(filter)              // Day45: 类型安全 Filter（替代字符串拼接）
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
    public QaResponse ask(
            @RequestParam String query,
            @RequestParam(required = false) String currentUser) {
        // Day45: 问答也要按 owner 过滤（QuestionAnswerAdvisor 从 qa_filter_expression 读动态过滤）
        String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
        String answer = ragChatClient.prompt()
                .user(query)
                .advisors(spec -> spec.param("qa_filter_expression", "owner == '" + owner + "'"))
                .call()
                .content();
        return new QaResponse(answer);
    }



    public record QaResponse(String answer) {}


    public record UploadResult(String filename, int chunkCount, int totalChars) {}




    @GetMapping("/askWithSource")
    public ChatAnswer askWithSource(String question,
                                    @RequestParam(required = false) String currentUser) {
        // ① 生成答案（QuestionAnswerAdvisor 自动完成检索+生成）
        String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
        ChatResponse response = ragChatClient.prompt()
                .user(question)
                .advisors(spec -> spec.param("qa_filter_expression", "owner == '" + owner + "'"))
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



    /** 列出所有已注册文档 */
    @GetMapping("/list")
    public List<Map<String, Object>> listDocuments() {
        return managementService.listAll();
    }

    /**
     * 删除文档（按 docId）。
     * 用 @RequestParam 接收，避免 docId 里含冒号/中文时路径编码报 400/404 的坑。
     */
    @DeleteMapping("/delete")
    public Map<String, String> deleteDocument(@RequestParam("docId") String docId) {
        managementService.deleteDocument(docId);
        return Map.of("message", "deleted", "docId", docId);
    }

    /** 更新文档：重新上传同一 docId，内容变了才先删后插 */
    @PutMapping("/update")
    public Map<String, String> updateDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String currentUser) throws Exception {
        String filename = file.getOriginalFilename();
        String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
        String result = managementService.updateDocument(filename, file, owner);
        return Map.of("result", result, "filename", filename);
    }

    // 提取文本片段的前 100 字作为摘要
    private String excerpt(String text) {
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    // 返回结构：答案 + 来源列表
    public record ChatAnswer(String answer, List<Source> sources) {}
    public record Source(String filename, String excerpt) {}

}

