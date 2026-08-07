package com.example.springai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class EmbeddingConfig {

    @Value("${app.embedding.base-url}")
    private String baseUrl;

    @Value("${app.embedding.api-key}")
    private String apiKey;

    /**
     * Embedding 专属，走 micuapi.ai（ztn 不支持 embedding）
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return new OpenAiEmbeddingModel(
                embeddingApi,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("gemini-embedding-001")
                        .build(),
                RetryTemplate.defaultInstance(),
                ObservationRegistry.NOOP);
    }
}
