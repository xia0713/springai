package com.example.springai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 查询改写---Multi-Query 改写
 */
@Service
public class QueryRewritingService {

    private final ChatClient chatClient;

    public QueryRewritingService(ChatClient.Builder builder) {
        // 用独立的轻量 ChatClient（不接 RAG Advisor）
        this.chatClient = builder.build();
    }

    /**
     * 生成多个查询改写版本
     */
    public List<String> rewrite(String question) {
        String prompt = """
            你是检索查询改写助手。请把用户问题改写成 3 个不同的检索查询。

            要求：
            1. 每个改写都要保留原始意图，不要改变含义
            2. 覆盖不同的表达方式（同义词、专业术语）
            3. 保留关键实体、数字、日期、专有名词
            4. 每个改写单独一行，不要编号

            用户问题：%s
            """.formatted(question);

        String response = chatClient.prompt()
                .user(prompt)
                .options(ChatOptions.builder().temperature(0.3).build())
                .call()
                .content();

        // 按行拆分成改写列表
        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}

