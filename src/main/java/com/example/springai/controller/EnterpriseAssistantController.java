package com.example.springai.controller;

import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.KnowledgeSearchTool;
import com.example.springai.tool.OrderTool;
import com.example.springai.tool.ReportFlowTools;
import com.example.springai.tool.SqlQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 企业智能助手统一入口（Day56）—— RAG + Function Calling 合体。
 * <p>
 * 路由不写分类器：工具 description 就是路由器，模型自动选能力域。
 * 这是第 13 周综合项目「企业智能助手平台」的骨架原型。
 */
@RestController
@RequestMapping("/api/enterprise-assistant")
public class EnterpriseAssistantController {

    private final ChatClient chatClient;

    public EnterpriseAssistantController(ChatModel chatModel,
                                         VectorStore vectorStore,
                                         JdbcTemplate jdbc) {
        // 演示模式：owner 传 null 不过滤（测试语料无 owner 元数据）；生产传当前登录用户
        KnowledgeSearchTool knowledgeTool = new KnowledgeSearchTool(vectorStore, null);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是企业智能助手，具备以下能力：
                        1. 查知识库文档：制度、政策、产品说明、员工手册 → 用 searchKnowledge
                        2. 查月度销售统计 → 用 querySalesStats
                        3. 查任意业务数据（订单/商品/状态）→ 生成 SQL 用 queryDatabase
                        4. 查订单物流 → 用 getOrderStatus；计算 → 用 calculate

                        路由经验：问"是什么/怎么规定"→ 查文档；问"多少/几个/排名"→ 查数据。
                        回答规则：
                        - 文档类回答标注来源
                        - 数据类回答给出精确数字
                        - 检索/查询都为空时，诚实说"未找到"，禁止编造
                        """)
                .defaultTools(knowledgeTool, new ReportFlowTools(jdbc), new SqlQueryTool(jdbc),
                        new OrderTool(), new CalculatorTool())
                .defaultOptions(ChatOptions.builder().temperature(0.2).build())
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
