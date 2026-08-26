package com.example.springai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 召回再重排
 */
@Service
public class AdvancedRagService {

    private final HybridSearchService hybridRetriever;   // Day 37 的混合检索
    private final RerankingService reranker;             // 今天的重排序
    private final ChatClient chatClient;

    public AdvancedRagService(HybridSearchService hybridRetriever,
                              RerankingService reranker,
                              ChatClient.Builder builder) {
        this.hybridRetriever = hybridRetriever;
        this.reranker = reranker;
        this.chatClient = builder.build();
    }

    public String answer(String question) {
        // ① 召回（宽网）：混合检索召回 top 30 候选
        List<Document> broadCandidates = hybridRetriever.hybridSearch(question, 30);

        if (broadCandidates.isEmpty()) {
            return "抱歉，知识库中暂未收录该问题。";
        }

        // ② 精排（过滤）：重排序选出 top 5
        List<Document> topContext = reranker.rerank(question, broadCandidates, 5);

        // ③ 生成：top 5 拼成上下文
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

