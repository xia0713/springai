package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.NotificationTool;
import com.example.springai.tool.OrderTool;
import com.example.springai.tool.ReportFlowTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ReAct Agent 入口（Day58）。
 * <p>
 * 工具全部复用第 8 周的 —— Agent 的地基是工具，今天只是把循环的"驾驶员"
 * 从 Spring AI 内部换成了我们自己的代码（步数熔断 + 每步可见）。
 */
@RestController
@RequestMapping("/api/react-agent")
public class ReActAgentController {

    private final SimpleReActAgent agent;

    public ReActAgentController(ChatModel chatModel, JdbcTemplate jdbc) {
        this.agent = new SimpleReActAgent(chatModel,
                new ReportFlowTools(jdbc), new OrderTool(),
                new CalculatorTool(), new NotificationTool());
    }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam String task) {
        var result = agent.run(task);
        return Map.of(
                "completed", result.completed(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }
}
