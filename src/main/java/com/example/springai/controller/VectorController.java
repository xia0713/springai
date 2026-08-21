package com.example.springai.controller;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class VectorController {

    private final VectorStore vectorStore;

    public VectorController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 添加文本到向量库
     */
    @PostMapping("/vector/add")
    public Map<String, Object> add(@RequestBody List<String> texts) {
        List<Document> docs = texts.stream()
                .map(text -> new Document(text))
                .toList();

        vectorStore.add(docs);

        return Map.of(
                "success", true,
                "count", texts.size()
        );
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
                        .similarityThreshold(0.8)              // 相似度阈值，低于此值不返回（默认 0.0，即全部返回）
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
}

