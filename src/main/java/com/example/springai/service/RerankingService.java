package com.example.springai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * 召回 + 精排。
 * <p>
 * 默认走 Cohere Rerank（Day 38 原版 cross-encoder 精排）；
 * 未配置 {@code cohere.api-key} 时，降级为「本地重排」——用 embedding 余弦相似度
 * 对候选重新打分排序，保证无外部依赖也能跑通单变量对比测试。
 */
@Service
public class RerankingService {

    private final RestClient restClient;
    private final EmbeddingModel embeddingModel;

    @Value("${cohere.api-key:}")
    private String apiKey;

    public RerankingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.cohere.ai/v1/rerank")
                .build();
    }

    /**
     * 重排序：把候选按真实相关性重新排序，返回 top N
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();

        // 无 Cohere Key → 本地重排（embedding 余弦相似度）
        if (apiKey == null || apiKey.isBlank() || "cohereApiKey".equals(apiKey)) {
            return localRerank(query, candidates, topN);
        }

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

    /**
     * 本地重排：query 与每个候选的 embedding 余弦相似度排序。
     */
    private List<Document> localRerank(String query, List<Document> candidates, int topN) {
        float[] queryVec = embeddingModel.embed(query);
        List<float[]> docVecs = embeddingModel.embed(
                candidates.stream().map(Document::getText).toList());

        record Scored(Document doc, double score) {}

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double score = cosine(queryVec, docVecs.get(i));
            candidates.get(i).getMetadata().put("rerank_score", score);
            scored.add(new Scored(candidates.get(i), score));
        }

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topN)
                .map(Scored::doc)
                .toList();
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // Cohere Rerank 响应结构
    public record RerankResponse(List<Result> results) {}
    public record Result(int index, double relevance_score) {
        public double relevanceScore() { return relevance_score; }
    }
}
