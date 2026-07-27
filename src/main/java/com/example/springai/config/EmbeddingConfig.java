package com.example.springai.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class EmbeddingConfig {

    /**
     * Embedding 专属，走 micuapi.ai（ztn 不支持 embedding）
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel() {
        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl("https://www.micuapi.ai")
                .apiKey("sk-LQKMaAjMTlyRxutKOHMC7OF0e3CoXfog46V1hxmRYq7z1xWw")
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
