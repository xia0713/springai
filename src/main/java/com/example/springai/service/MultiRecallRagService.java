package com.example.springai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 多路召回编排
 */
@Service
public class MultiRecallRagService {

    private final VectorStore vectorStore;           // 向量检索
    private final HybridSearchService hybridSearch;  // Day 37 混合检索（含 BM25）
    private final QueryRewritingService rewriter;    // Day 39 查询改写
    private final RerankingService reranker;         // Day 38 重排序
    private final ChatClient chatClient;

    public MultiRecallRagService(VectorStore vectorStore,
                                 HybridSearchService hybridSearch,
                                 QueryRewritingService rewriter,
                                 RerankingService reranker,
                                 ChatClient.Builder builder) {
        this.vectorStore = vectorStore;
        this.hybridSearch = hybridSearch;
        this.rewriter = rewriter;
        this.reranker = reranker;
        this.chatClient = builder.build();
    }

    /**
     * 召回（不含生成）：并行多路召回 + 去重 + 重排序，返回 top 5。
     * 供测试单独测 Recall@N。
     */
    public List<Document> retrieve(String question) {
        // 并行召回：多路同时跑
        CompletableFuture<List<Document>> hybridFuture =
                CompletableFuture.supplyAsync(() -> hybridSearch.hybridSearch(question));

        CompletableFuture<List<Document>> multiQueryFuture =
                CompletableFuture.supplyAsync(() -> {
                    List<Document> result = new ArrayList<>();
                    // ① 查询改写（Day 39）
                    List<String> queries = new ArrayList<>();
                    queries.add(question);                        // 原始问题保留一路
                    queries.addAll(rewriter.rewrite(question));   // 3 个改写版本
                    // 多 Query 扩展（每个改写版本检索）
                    for (String q : queries) {
                        result.addAll(vectorStore.similaritySearch(
                                SearchRequest.builder()
                                        .query(q)
                                        .topK(10)
                                        .similarityThreshold(0.4)   // 阈值调低，广召回
                                        .build()
                        ));
                    }
                    return result;
                });

        // 等待所有路完成，合并 + 去重
        List<Document> candidates = Stream.of(hybridFuture, multiQueryFuture)
                .map(CompletableFuture::join)   // 阻塞等全部完成
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        Document::getId, d -> d, (a, b) -> a, LinkedHashMap::new
                ))
                .values().stream().toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        // ④ 重排序（Day 38，用原始 query！）
        return reranker.rerank(question, candidates, 5);
    }

    public String answer(String question) {
        List<Document> topContext = retrieve(question);

        if (topContext.isEmpty()) {
            return "抱歉，知识库中暂未收录该问题。";
        }

        // ⑤ 生成
        String context = topContext.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        String prompt = """
            你是企业知识库助手。基于以下资料回答问题：
            【资料】%s
            【问题】%s
            资料中没有答案时礼貌拒答。
            """.formatted(context, question);

        return chatClient.prompt()
                .user(prompt)
                .options(ChatOptions.builder().temperature(0.2).build())
                .call()
                .content();
    }
}

