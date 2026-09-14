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

/**
 * 手写 ReAct Agent（Day58）：显式驱动 思考→行动→观察 循环。
 * <p>
 * 核心三点：
 * ① internalToolExecutionEnabled(false) —— 关掉 Spring AI 内置循环，控制权回到代码
 *    （不加这个 = 内部把工具执行掉，本循环永远只见最终答案，熔断形同虚设）
 * ② 循环内自己执行工具、用 conversationHistory() 重建 Prompt、再次调用
 * ③ MAX_STEPS 熔断 —— Day57 可靠性数学（95%¹⁰≈60%）的工程对策
 */
@Slf4j
public class SimpleReActAgent {

    private static final int MAX_STEPS = 8;

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

        for (int step = 1; step <= MAX_STEPS; step++) {
            // 终止条件：模型不再发起工具调用 = 它认为任务完成
            if (!response.hasToolCalls()) {
                String answer = response.getResult().getOutput().getText();
                log.info("[Agent] 任务完成，共 {} 步", step - 1);
                return AgentResult.success(answer, step - 1);
            }

            // Action：打印模型请求的工具调用，然后自己执行（Observation 由 manager 追加进历史）
            final int stepNo = step;   // lambda 引用循环变量需 effectively final
            response.getResult().getOutput().getToolCalls()
                    .forEach(tc -> log.info("[Agent step {}] Action: {}({})", stepNo, tc.name(), tc.arguments()));
            var toolResult = toolCallingManager.executeToolCalls(prompt, response);

            // ② 带着新历史进入下一轮思考（消息不可变，必须重建 Prompt）
            prompt = new Prompt(toolResult.conversationHistory(), options);
            response = chatModel.call(prompt);
        }

        // ③ 步数熔断：返回结构化结果，不抛异常
        log.warn("[Agent] 达到最大步数 {}，熔断", MAX_STEPS);
        return AgentResult.exhausted(MAX_STEPS);
    }
}
