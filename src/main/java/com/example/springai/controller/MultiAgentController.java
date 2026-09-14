package com.example.springai.controller;

import com.example.springai.agent.AgentResult;
import com.example.springai.agent.SimpleReActAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 Agent 协作（Day61）：需求分析师 → 开发工程师 流水线。
 * <p>
 * 复用 SimpleReActAgent 引擎，角色差异只在 system prompt（Day60 重载的落点）。
 * 设计要点：
 * - 角色拆分的真价值：prompt 聚焦 + 工具面收窄（本场景两个角色都不挂工具）+ 独立评估
 * - 结构化交接是生命线：输出模板写死在角色 prompt + 代码校验，带病传递直接熔断
 * - 全链路日志：每个 Agent 的步数/产出长度/耗时留痕
 */
@Slf4j
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    /** 角色 1：需求分析师 —— 输出格式是结构化交接的生命线 */
    private static final String ANALYST_PROMPT = """
            你是需求分析师。把用户需求拆解为功能点清单，严格按以下格式输出：
            ## 功能点清单
            F1: <功能描述> — 验收标准：<可验证的标准>
            F2: ...
            要求：3-5 个功能点，不写代码，不写技术选型，只写"做什么"和"怎么算做好"。
            """;

    /** 角色 2：开发工程师 —— 只基于分析师的结构化产出工作 */
    private static final String DEVELOPER_PROMPT = """
            你是资深开发工程师。基于给你的《功能点清单》输出技术方案，严格按以下格式：
            ## 技术方案
            ### F1 <功能点>
            - 实现思路：...
            - 涉及模块/表：...
            ### F2 ...
            ## 风险与工作量
            - 风险：...
            - 总工作量估算：...人天
            """;

    private final SimpleReActAgent analyst;
    private final SimpleReActAgent developer;

    public MultiAgentController(ChatModel chatModel) {
        // 角色差异只在 system prompt；分析师不挂工具（工具面收窄原则）
        this.analyst = new SimpleReActAgent(chatModel, ANALYST_PROMPT);
        this.developer = new SimpleReActAgent(chatModel, DEVELOPER_PROMPT);
    }

    @GetMapping("/develop")
    public Map<String, Object> develop(@RequestParam String requirement) {
        long start = System.currentTimeMillis();

        // ===== Agent 1：需求分析师 =====
        AgentResult analysis = analyst.run("分析以下需求并输出功能点清单：\n" + requirement);
        log.info("[multi-agent] 分析师完成，steps={}，产出长度={}", analysis.stepsUsed(),
                analysis.answer() == null ? 0 : analysis.answer().length());

        // ===== 交接校验（结构化是生命线）：不含"功能点清单"章节 = 不合格，终止而非带病传递 =====
        if (!analysis.completed() || analysis.answer() == null
                || !analysis.answer().contains("功能点清单")) {
            return Map.of(
                    "status", "ANALYST_FAILED",
                    "reason", "分析师产出不合格（缺功能点清单章节或被熔断），流程终止，需人工介入",
                    "rawOutput", String.valueOf(analysis.answer()));
        }

        // ===== 消息传递：交接的是结构化产物，不是散文 =====
        // ===== Agent 2：开发工程师 =====
        AgentResult dev = developer.run("基于以下需求分析输出技术方案：\n" + analysis.answer());
        log.info("[multi-agent] 工程师完成，steps={}，总耗时={}ms", dev.stepsUsed(),
                System.currentTimeMillis() - start);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", dev.completed() ? "SUCCESS" : "DEVELOPER_EXHAUSTED");
        result.put("requirementAnalysis", analysis.answer());
        result.put("techDesign", dev.answer());
        result.put("analystSteps", analysis.stepsUsed());
        result.put("developerSteps", dev.stepsUsed());
        return result;
    }
}
