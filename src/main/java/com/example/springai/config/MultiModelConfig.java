package com.example.springai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class MultiModelConfig {

    /**
     * 豆包模型
     */
    @Bean
    public ChatClient deepseekChatClient() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://ai-gateway.ztn.cn")
                .apiKey("sk-RtUONsbppcygBmXyRI56E1evZVh9sqhAwYtphlK0Jxjey2tv")
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-v4-flash").build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return ChatClient.builder(chatModel).build();
    }

    /**
     * 通义千问模型（也是OpenAI兼容协议）
     */
    @Bean
    public ChatClient qwenChatClient() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://ai-gateway.ztn.cn")
                .apiKey("sk-RtUONsbppcygBmXyRI56E1evZVh9sqhAwYtphlK0Jxjey2tv")
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model("qwen3.7-plus").build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return ChatClient.builder(chatModel).build();
    }
}
