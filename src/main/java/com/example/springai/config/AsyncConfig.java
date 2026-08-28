package com.example.springai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync          // 开启 @Async 能力
@EnableRetry          // 开启 @Retryable 能力
public class AsyncConfig {

    /**
     * 文档处理专用线程池。
     * 文档处理是 I/O 密集（Tika 解析 + embedding 网络调用），
     * 用 Java 21 虚拟线程最合适：几乎不占系统线程，天然支持高并发，不用调线程数。
     */
    @Bean(name = "documentTaskExecutor")
    public AsyncTaskExecutor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);      // Java 21 虚拟线程（Spring 6.1+ 支持）
        executor.setThreadNamePrefix("doc-");
        executor.initialize();
        return executor;
    }
}