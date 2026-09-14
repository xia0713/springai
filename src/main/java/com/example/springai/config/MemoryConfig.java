package com.example.springai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短期记忆配置（Day59）：窗口 20 条，超出丢最老。
 * <p>
 * 生产两步升级：
 * ① 换 JDBC 仓库（spring-ai-starter-model-chat-memory-repository-jdbc），重启不丢；
 * ② 加摘要压缩：接近窗口上限时把最老 N 条用小模型总结成一条摘要放回。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
