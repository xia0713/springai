package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MultiQueryRagService {

    private final QueryRewritingService rewriter;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public MultiQueryRagService(QueryRewritingService rewriter,
                                VectorStore vectorStore, ChatClient chatClient) {
        this.rewriter = rewriter;
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    public List<Document> retrieve(String question) {
        // ① 生成多个改写（含原始查询）
        List<String> queries = new ArrayList<>();
        queries.add(question);                 // 原始查询始终保留！
        queries.addAll(rewriter.rewrite(question));

        // ② 每个改写都检索，收集结果（用 Map 去重）
        Map<String, Document> merged = new LinkedHashMap<>();
        for (String q : queries) {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(q)
                            .topK(5)
                            .similarityThreshold(0.5)
                            .build()
            );
            for (Document doc : results) {
                merged.putIfAbsent(doc.getId(), doc);  // 按 ID 去重
            }
        }

        // ③ 返回去重后的合并结果（后续可加重排序 Day 38）
        return new ArrayList<>(merged.values());
    }

    public String multi(String question) {
        List<Document> docs = retrieve(question);

        if (docs.isEmpty()) {
            return "抱歉，知识库中暂未收录该问题。";
        }

        // ② 拼接上下文
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            context.append("[资料").append(i + 1).append("] ")
                    .append(docs.get(i).getText())
                    .append("\n\n");
        }

        // ③ 生成（Day 32 的 RAG Prompt）
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

