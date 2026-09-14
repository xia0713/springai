package com.example.springai.controller;

import com.example.springai.tool.ReportFlowTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 报表流程助手（Day54）：一句话触发 查数据→写文案→存草稿→发邮件 全流程。
 * <p>
 * 编排者：模型自己（Spring AI 内部工具循环）；
 * 我们的工作：system prompt 声明流程意图 + 工具接口设计 + 失败兜底。
 */
@RestController
@RequestMapping("/api/report-assistant")
public class ReportAssistantController {

    private final ChatClient chatClient;

    /** 关键：system prompt 里写清流程 —— 编排意图靠提示词声明，执行靠模型自主串工具 */
    private static final String WORKFLOW_PROMPT = """
            你是报表助手。当用户要求生成销售报表时，严格按以下流程执行：
            1. 调用 querySalesStats 查询销售统计数据
            2. 基于数据撰写一段简洁的分析文案（含订单数、销售额、畅销品和建议）
            3. 调用 saveReportDraft 保存草稿，拿到草稿 ID
            4. 调用 sendReportEmail 发送（用草稿 ID + 用户指定的收件人）
            全部完成后，用一句话向用户确认结果。
            用户只是查询数据（没让生成报表）时，只调用 querySalesStats，不要存草稿或发邮件。
            """;

    public ReportAssistantController(ChatModel chatModel, ReportFlowTools reportFlowTools) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(WORKFLOW_PROMPT)
                .defaultTools(reportFlowTools)
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
