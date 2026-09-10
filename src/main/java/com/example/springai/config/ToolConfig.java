package com.example.springai.config;

import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具调用配置（Day51）。
 */
@Configuration
public class ToolConfig {

    /**
     * 工具异常降级：工具抛异常时，不炸 HTTP 请求，
     * 而是把错误信息作为工具结果回传给模型，由模型生成友好提示。
     * <p>
     * alwaysThrow(false) = 降级模式（生产推荐）；true = 直接抛（调试期定位问题用）。
     * 全局生效：所有 @Tool 方法抛出的异常都经此处理器。
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .build();
    }
}
