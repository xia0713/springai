package com.example.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 手写 ReAct Agent（Day58 建，Day62 升级为防护矩阵版）：显式驱动 思考→行动→观察 循环。
 * <p>
 * 循环核心（Day58）：
 * ① internalToolExecutionEnabled(false) —— 关掉 Spring AI 内置循环，控制权回到代码
 * ② 循环内自己执行工具、用 conversationHistory() 重建 Prompt、再次调用
 * <p>
 * 防护矩阵（Day62）：
 * ① MAX_STEPS 步数熔断 —— Day57 可靠性数学的工程对策
 * ② 重复调用熔断 —— 同一调用键连续 ≥3 次 = 模型卡死在原地
 * ③ 耗时预算熔断 —— 总耗时超预算（步数不多但每步都慢的场景）
 * ④ 工具异常兜底 —— 手动构建的 ToolCallingManager 不走容器降级 Bean，异常必须自己接
 * 全部熔断出口统一产出【人工介入单】—— 熔断即交接班，不是报错。
 */
@Slf4j
public class SimpleReActAgent {

    private static final int MAX_STEPS = 8;
    private static final int REPEAT_CALL_LIMIT = 3;     // Day62: 同一调用键连续重复上限
    private static final long TIME_BUDGET_MS = 120_000; // Day62: 总耗时预算 120s

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final String systemPrompt;   // Day60: 可定制 —— 行业知识注入方式②（排障 SOP 等）

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个能干活的任务代理。面对复杂任务：
            1. 先想清楚需要哪几步（可以先用一两句话说明思路）
            2. 逐步调用工具完成，每一步根据上一步结果决定下一步
            3. 信息足够后，直接给出最终答案（不要再调用工具）
            4. 任务无法完成时，诚实说明原因，不要编造
            """;

    public SimpleReActAgent(ChatModel chatModel, Object... toolObjects) {
        this(chatModel, null, toolObjects);
    }

    /**
     * Day60 新增：允许为不同场景注入定制 system prompt（如运维排障 SOP）。
     * systemPrompt 传 null 时用默认通用版。
     */
    public SimpleReActAgent(ChatModel chatModel, String systemPrompt, Object... toolObjects) {
        this.chatModel = chatModel;
        this.toolCallingManager = ToolCallingManager.builder().build();
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build();
        this.toolCallbacks = Arrays.asList(provider.getToolCallbacks());
        this.systemPrompt = (systemPrompt == null || systemPrompt.isBlank())
                ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    public AgentResult run(String task) {
        long start = System.currentTimeMillis();
        List<Message> conversation = new ArrayList<>(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(task)));

        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)   // ① 关键：自己开循环
                .temperature(0.2)
                .build();

        Prompt prompt = new Prompt(conversation, options);
        ChatResponse response = chatModel.call(prompt);

        List<String> trace = new ArrayList<>();   // Day62: 执行轨迹（人工介入单用）
        String lastCallKey = null;                // Day62: 上一轮调用键
        int repeatCount = 0;

        for (int step = 1; step <= MAX_STEPS; step++) {
            // ===== 防护3：耗时预算熔断 =====
            if (System.currentTimeMillis() - start > TIME_BUDGET_MS) {
                log.warn("[Agent] 耗时超预算熔断（>{}ms）", TIME_BUDGET_MS);
                return AgentResult.humanIntervention(AgentResult.TIME_BUDGET, step - 1,
                        buildInterventionCard(trace, AgentResult.TIME_BUDGET,
                                "总耗时超过 " + TIME_BUDGET_MS / 1000 + "s 预算"));
            }

            // 终止条件：模型不再发起工具调用 = 它认为任务完成
            if (!response.hasToolCalls()) {
                String answer = response.getResult().getOutput().getText();
                log.info("[Agent] 任务完成，共 {} 步", step - 1);
                return AgentResult.success(answer, step - 1);
            }

            // 本轮调用键（工具名+参数；多工具调用排序后拼接，保证比较稳定）
            String callKey = response.getResult().getOutput().getToolCalls().stream()
                    .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                    .sorted()
                    .collect(Collectors.joining(" | "));

            // ===== 防护2：重复调用检测（连续相同调用 = 模型卡死在原地）=====
            if (callKey.equals(lastCallKey)) {
                repeatCount++;
            } else {
                repeatCount = 1;
                lastCallKey = callKey;
            }
            trace.add("step" + step + ": " + callKey);

            // Action：打印模型请求的工具调用，然后自己执行（Observation 由 manager 追加进历史）
            final int stepNo = step;   // lambda 引用循环变量需 effectively final
            response.getResult().getOutput().getToolCalls()
                    .forEach(tc -> log.info("[Agent step {}] Action: {}({})", stepNo, tc.name(), tc.arguments()));

            if (repeatCount >= REPEAT_CALL_LIMIT) {
                log.warn("[Agent] 死循环熔断：连续 {} 次相同调用", repeatCount);
                return AgentResult.humanIntervention(AgentResult.REPEAT_LOOP, step - 1,
                        buildInterventionCard(trace, AgentResult.REPEAT_LOOP,
                                "连续 " + repeatCount + " 次重复相同工具调用，模型卡死在该动作上"));
            }

            // ===== 防护4：工具异常兜底（手动 manager 不走容器降级 Bean，必须自己接）=====
            try {
                var toolResult = toolCallingManager.executeToolCalls(prompt, response);

                // ② 带着新历史进入下一轮思考（消息不可变，必须重建 Prompt）
                prompt = new Prompt(toolResult.conversationHistory(), options);
                response = chatModel.call(prompt);
            } catch (Exception e) {
                log.error("[Agent] 工具执行异常熔断", e);
                return AgentResult.humanIntervention(AgentResult.TOOL_ERROR, step - 1,
                        buildInterventionCard(trace, AgentResult.TOOL_ERROR,
                                "工具执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        // ===== 防护1：步数熔断（Day58 已有，Day62 起统一走介入单）=====
        log.warn("[Agent] 达到最大步数 {}，熔断", MAX_STEPS);
        return AgentResult.exhausted(MAX_STEPS);
    }

    /**
     * Day62: 人工介入单 —— 熔断即交接班，让人 30 秒内能接手。
     * 必含四要素：终止原因 / 卡点说明 / 已执行轨迹 / 处置建议。
     */
    private String buildInterventionCard(List<String> trace, String reason, String detail) {
        return """
                【人工介入单】
                终止原因: %s
                卡点说明: %s
                已执行轨迹:
                %s
                处置建议: 人工检查工具可用性与参数，或改写任务描述后重试
                """.formatted(reason, detail,
                trace.isEmpty() ? "  （尚无工具调用）" : "  " + String.join("\n  ", trace));
    }
}
