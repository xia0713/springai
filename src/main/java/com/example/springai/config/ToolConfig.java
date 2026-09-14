package com.example.springai.config;

import com.example.springai.tool.ErpEmployeeTool;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具调用配置（Day51/53）。
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

    /**
     * ERP 员工查询工具（Day53）。
     * 带 @Value 构造参数（base-url / api-key），需显式声明为 Bean。
     */
    @Bean
    public ErpEmployeeTool erpEmployeeTool(@Value("${app.erp.base-url}") String baseUrl,
                                           @Value("${app.erp.api-key}") String apiKey) {
        return new ErpEmployeeTool(baseUrl, apiKey);
    }

    /**
     * 报表流程工具集（Day54）：查数据 → 存草稿 → 发邮件。
     * 需要 JdbcTemplate 做销售统计查询。
     */
    @Bean
    public com.example.springai.tool.ReportFlowTools reportFlowTools(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new com.example.springai.tool.ReportFlowTools(jdbc);
    }
}
