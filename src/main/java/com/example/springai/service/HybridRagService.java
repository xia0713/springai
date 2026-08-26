package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 混合检索
 */
@Service
public class HybridRagService {

    private final HybridSearchService hybridSearchService;
    private final ChatClient chatClient;

    public HybridRagService(HybridSearchService hybridSearchService,
                            ChatClient.Builder builder) {
        this.hybridSearchService = hybridSearchService;
        this.chatClient = builder.build();
    }

    public String answer(String question) {
        // ① 混合检索（替代原来的纯向量检索）
        List<Document> docs = hybridSearchService.hybridSearch(question);

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

