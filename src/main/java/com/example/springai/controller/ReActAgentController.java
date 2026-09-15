package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.LoopDemoTool;
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
 * ReAct Agent 入口（Day58 建，Day62 加熔断演示）。
 * <p>
 * 工具全部复用第 8 周的 —— Agent 的地基是工具，今天只是把循环的"驾驶员"
 * 从 Spring AI 内部换成了我们自己的代码（步数熔断 + 每步可见）。
 * Day62：loopDemoAgent 挂两个故意坏掉的工具，验证防护矩阵（独立实例，不污染正常 agent）。
 */
@RestController
@RequestMapping("/api/react-agent")
public class ReActAgentController {

    private final SimpleReActAgent agent;
    private final SimpleReActAgent loopDemoAgent;   // Day62: 熔断演示专用

    public ReActAgentController(ChatModel chatModel, JdbcTemplate jdbc) {
        this.agent = new SimpleReActAgent(chatModel,
                new ReportFlowTools(jdbc), new OrderTool(),
                new CalculatorTool(), new NotificationTool());
        this.loopDemoAgent = new SimpleReActAgent(chatModel, new LoopDemoTool());
    }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam String task) {
        var result = agent.run(task);
        return Map.of(
                "completed", result.completed(),
                "terminateReason", result.terminateReason(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }

    /** Day62: 死循环/工具异常熔断演示（挂的是 LoopDemoTool 坏工具） */
    @GetMapping("/loop-demo")
    public Map<String, Object> loopDemo(@RequestParam String task) {
        var result = loopDemoAgent.run(task);
        return Map.of(
                "completed", result.completed(),
                "terminateReason", result.terminateReason(),
                "stepsUsed", result.stepsUsed(),
                "interventionCard", result.answer()
        );
    }
}
