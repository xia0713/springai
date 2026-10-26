package com.example.springai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class MultiModelConfig {

    @Value("${app.gateway.base-url}")
    private String baseUrl;

    @Value("${app.gateway.api-key}")
    private String apiKey;

    /**
     * 备用模型（DeepSeek：小/快/便宜，简单问题与降级用）
     */
    @Bean
    public ChatClient deepseekChatClient() {
        return buildChatClient("deepseek-v4-flash");
    }

    /**
     * 主模型（复杂问题）—— Day76：qwen3.8-27b 平台下架，替换为 glm-5.3-flash
     */
    @Bean
    public ChatClient primaryChatClient() {
        return buildChatClient("glm-5.3-flash");
    }

    private ChatClient buildChatClient(String model) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
