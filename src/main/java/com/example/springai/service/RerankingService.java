package com.example.springai.service;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;


/**
 * 召回 + 精排
 */
@Service
public class RerankingService {

    private final RestClient restClient;

    @Value("${cohere.api-key:cohereApiKey}")
    private String apiKey;

    public RerankingService() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.cohere.ai/v1/rerank")
                .build();
    }

    /**
     * 重排序：把候选按真实相关性重新排序，返回 top N
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();

        // 提取候选文本
        List<String> docsText = candidates.stream()
                .map(Document::getText)
                .toList();

        // 调用 Cohere Rerank API
        var requestBody = Map.of(
                "model", "rerank-multilingual-v3.0",  // 多语言（中文用这个）
                "query", query,
                "documents", docsText,
                "top_n", topN
        );

        RerankResponse response = restClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(RerankResponse.class);

        // 按返回的 index 映射回 Document（已按相关性排序）
        return response.results().stream()
                .map(res -> {
                    Document doc = candidates.get(res.index());
                    doc.getMetadata().put("rerank_score", res.relevanceScore());
                    return doc;
                })
                .toList();
    }

    // Cohere Rerank 响应结构
    public record RerankResponse(List<Result> results) {}
    public record Result(int index, double relevance_score) {
        public double relevanceScore() { return relevance_score; }
    }
}

