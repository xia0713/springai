package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 可配置 RAG：通过开关逐项叠加优化，方便做单变量对比测试（Day 24 单变量原则）。
 * <ul>
 *   <li>rag.hybrid   —— V1 混合检索（Day 37）</li>
 *   <li>rag.rerank   —— V2 重排序（Day 38）</li>
 *   <li>rag.rewrite  —— V3 查询改写（Day 39）</li>
 * </ul>
 * 召回与生成拆分：{@link #retrieve(String)} 只做召回，供测试单独测 Recall@N。
 */
@Service
public class ConfigurableRagService {

    // 优化开关（通过配置控制，方便对比测试）
    private final boolean enableHybrid;     // Day 37 混合检索
    private final boolean enableRerank;     // Day 38 重排序
    private final boolean enableRewrite;    // Day 39 查询改写

    private final VectorStore vectorStore;
    private final HybridSearchService hybridSearch;
    private final QueryRewritingService rewriter;
    private final RerankingService reranker;
    private final ChatClient chatClient;

    public ConfigurableRagService(
            @Value("${rag.hybrid:false}") boolean enableHybrid,
            @Value("${rag.rerank:false}") boolean enableRerank,
            @Value("${rag.rewrite:false}") boolean enableRewrite,
            VectorStore vectorStore,
            HybridSearchService hybridSearch,
            QueryRewritingService rewriter,
            RerankingService reranker,
            ChatClient.Builder builder) {
        this.enableHybrid = enableHybrid;
        this.enableRerank = enableRerank;
        this.enableRewrite = enableRewrite;
        this.vectorStore = vectorStore;
        this.hybridSearch = hybridSearch;
        this.rewriter = rewriter;
        this.reranker = reranker;
        this.chatClient = builder.build();
    }

    /**
     * 召回（不含生成）：返回 top 5 文档，供测试单独计算 Recall@5。
     */
    public List<Document> retrieve(String question) {
        List<Document> candidates;

        // ① 查询改写（开关控制）
        List<String> queries = new ArrayList<>();
        queries.add(question);
        if (enableRewrite) {
            queries.addAll(rewriter.rewrite(question));
        }

        // ② 召回（开关控制：纯向量 or 混合检索）
        if (enableHybrid) {
            candidates = new ArrayList<>(hybridSearch.hybridSearch(question, 20));
        } else {
            candidates = new ArrayList<>(vectorStore.similaritySearch(
                    SearchRequest.builder().query(question).topK(20).build()
            ));
        }

        // ③ 多 Query 扩展（改写开关控制）
        for (int i = 1; i < queries.size(); i++) {
            candidates.addAll(vectorStore.similaritySearch(
                    SearchRequest.builder().query(queries.get(i)).topK(10).build()
            ));
        }

        // 去重（按文档 ID）
        Map<String, Document> dedup = new LinkedHashMap<>();
        candidates.forEach(d -> dedup.putIfAbsent(d.getId(), d));
        candidates = new ArrayList<>(dedup.values());

        if (candidates.isEmpty()) {
            return List.of();
        }

        // ④ 重排序（开关控制）
        if (enableRerank) {
            return reranker.rerank(question, candidates, 5);
        }
        return candidates.subList(0, Math.min(5, candidates.size()));
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
