package com.example.springai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public ChatClient ragChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        return builder
                .defaultSystem("""
                你是企业知识库助手，只基于检索到的资料回答。
                资料中没有答案时，回复"抱歉，知识库未收录该问题"。
                回答简洁，先给结论再给依据。
                """)
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .similarityThreshold(0.6)
                                        .topK(5)
                                        .build())
                                .build()
                )
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.2)     // 事实问答用低温
                        .build())
                .build();
    }
}
