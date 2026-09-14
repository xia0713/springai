package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.OpsTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维排障 Agent（Day60）：SimpleReActAgent 引擎 + 运维工具集。
 * <p>
 * 行业知识注入两层：
 * - 错误码手册 → 结构化工具 OpsTools.lookupErrorCode（稳定知识）
 * - 排查 SOP   → 定制 system prompt（易变知识）—— Day60 新增构造器重载的落点
 */
@RestController
@RequestMapping("/api/ops-agent")
public class OpsAgentController {

    /** 排查 SOP（行业知识注入方式②：易变知识写进 prompt） */
    private static final String OPS_SOP = """
            你是资深运维排障工程师，面对报错日志严格按 SOP 排查：
            1. 从日志中提取错误码，调用 lookupErrorCode 查手册
            2. 按手册指引调用 getSystemMetrics / checkDependency 收集事实（需要哪个查哪个，不必全查）
            3. 基于事实给出结论，必须包含三段：定位（错误码含义）→ 根因（哪个服务什么状态）→ 处置建议（限流/扩容/熔断/人工介入）
            4. 手册里没有的错误码，或日志信息不足以定位时，诚实说明需要更多信息，禁止编造
            """;

    private final SimpleReActAgent agent;

    public OpsAgentController(ChatModel chatModel) {
        // Day60 新增的构造器重载：注入排障 SOP
        this.agent = new SimpleReActAgent(chatModel, OPS_SOP, new OpsTools());
    }

    @GetMapping("/troubleshoot")
    public Map<String, Object> troubleshoot(@RequestParam String log) {
        var result = agent.run("以下是系统报错日志，请分步排查并给出结论和处置建议：\n" + log);
        return Map.of(
                "completed", result.completed(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }
}
