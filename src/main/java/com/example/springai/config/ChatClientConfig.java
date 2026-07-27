package com.example.springai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class ChatClientConfig {

    /**
     * Chat 平台走 ai-gateway.ztn.cn（支持图片等多模态）
     */
    @Bean
    @Primary
    public ChatModel chatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://ai-gateway.ztn.cn")
                .apiKey("sk-RtUONsbppcygBmXyRI56E1evZVh9sqhAwYtphlK0Jxjey2tv")
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen3.7-plus")
                        .temperature(0.7)
                        .maxTokens(2000)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    @Bean
    @Primary
    public ChatClient chatClient(@Qualifier("chatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
