package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.KnowledgeSearchTool;
import com.example.springai.tool.MemoryTool;
import com.example.springai.tool.OrderTool;
import com.example.springai.tool.ReportFlowTools;
import com.example.springai.tool.SqlQueryTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能助手完成版（Day63）—— 第 9 周全部能力的合体：
 * ReAct 引擎（防护矩阵）+ 全工具面 + 短期记忆（ChatMemory）+ 长期记忆（MemoryTool）。
 * 这是第 13 周综合项目的直系原型。
 * <p>
 * 演示模式的三把刀（综合项目收刀）：KnowledgeSearchTool owner=null、
 * MemoryTool 固定用户、内存态 ChatMemory —— Day82 全部换登录态+持久化。
 */
@RestController
@RequestMapping("/api/smart-assistant")
public class SmartAssistantController {

    private static final String ASSISTANT_PROMPT = """
            你是企业智能助手，具备完整能力：
            1. 查知识库文档（制度/政策/产品）→ searchKnowledge
            2. 查月度销售统计 → querySalesStats；查任意业务数据 → queryDatabase
            3. 查订单物流 → getOrderStatus；计算 → calculate
            4. 用户提到个人信息（姓名/偏好/习惯）→ 先 remember 保存；涉及用户个人的问题 → 先 recall
            复杂任务分步调用工具完成；信息不足或查不到时诚实说明，禁止编造。
            """;

    private final SimpleReActAgent agent;
    private final ChatMemory chatMemory;

    public SmartAssistantController(ChatModel chatModel, ChatMemory chatMemory,
                                    VectorStore vectorStore, JdbcTemplate jdbc) {
        this.chatMemory = chatMemory;
        // 全工具面（9 个工具）：演示可接受；生产按场景拆入口（Day55 规范 4.1）
        this.agent = new SimpleReActAgent(chatModel, ASSISTANT_PROMPT,
                new KnowledgeSearchTool(vectorStore, null),   // 演示模式不过滤 owner
                new ReportFlowTools(jdbc),
                new SqlQueryTool(jdbc),
                new OrderTool(),
                new CalculatorTool(),
                new MemoryTool(vectorStore, "zhangsan"));     // 演示固定用户
    }

    /** sessionId 区分短期记忆；跨会话靠长期记忆（MemoryTool） */
    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestParam(defaultValue = "s1") String sessionId,
            @RequestParam String message) {

        // ① 取【过去的】窗口历史（不含本轮消息），注入 ReAct 循环
        List<Message> history = new ArrayList<>(chatMemory.get(sessionId));

        // ② 带历史跑 ReAct（防护矩阵全程护航）
        var result = agent.run(message, history);

        // ③ 本轮对话存回短期记忆，供下一轮组装
        chatMemory.add(sessionId, new UserMessage(message));
        if (result.answer() != null) {
            chatMemory.add(sessionId, new AssistantMessage(result.answer()));
        }

        return Map.of(
                "completed", result.completed(),
                "terminateReason", result.terminateReason(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }
}
