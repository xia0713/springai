package com.example.springai.controller;

import com.example.springai.tool.SqlQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NL2SQL 自然语言查数据（Day52）。
 * <p>
 * 表结构注入 + 语法规则在 system prompt（软约束），
 * 真正的只读硬约束在 SqlSafetyValidator（代码层），
 * 生产的最后防线是只读数据库账号（三层防线）。
 */
@RestController
@RequestMapping("/api/nl2sql")
public class Nl2SqlController {

    /**
     * 表结构注入 —— NL2SQL 准确率的第一要素：
     * 列后加中文注释、枚举值写全（status 不写全模型会猜 'shipped' 查出 0 行）、只注入相关表。
     */
    private static final String SCHEMA_PROMPT = """
            你是数据分析助手，根据用户问题生成 SQL 并调用 queryDatabase 工具查询。

            表结构：
            CREATE TABLE demo_orders (
                order_id   VARCHAR(20),   -- 订单号
                product    VARCHAR(50),   -- 商品名称
                amount     NUMERIC(10,2), -- 金额（元）
                quantity   INT,           -- 数量
                status     VARCHAR(20),   -- 状态：待发货/已发货/已签收/已取消
                order_date DATE           -- 下单日期
            );

            规则：
            1. 只能生成 SELECT 语句，禁止任何增删改
            2. 必须带 LIMIT，最多返回 100 行
            3. 状态值只能用：待发货、已发货、已签收、已取消（中文精确匹配）
            4. 拿到查询结果后，用简洁的自然语言总结回答用户，附上关键数字
            """;

    private final ChatClient chatClient;

    public Nl2SqlController(ChatModel chatModel, JdbcTemplate jdbc) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SCHEMA_PROMPT)
                .defaultTools(new SqlQueryTool(jdbc))
                .defaultOptions(ChatOptions.builder().temperature(0.1).build())   // SQL 生成要低温
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
