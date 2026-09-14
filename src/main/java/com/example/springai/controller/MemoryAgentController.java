package com.example.springai.controller;

import com.example.springai.tool.MemoryTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 带记忆的助手（Day59）—— 手动组装消息版。
 * <p>
 * ⚠️ 为什么不用 MessageChatMemoryAdvisor？
 * 实测（反编译 1.0.3 字节码确认）：它的 before() 组装顺序是
 *   processed = [记忆历史...] + [本次 instructions（含 SystemMessage）]
 * 即 SystemMessage 会被挤到历史之后 —— 严格校验的网关（qwen 中转）直接报
 *   400 "System message must be at the beginning."
 * 所以这里手动组装：System 永远在最前，历史随后 —— 与 Day58 手写循环同一哲学：
 * 控制权在自己手里，且任何网关都兼容。
 */
@RestController
@RequestMapping("/api/memory-agent")
public class MemoryAgentController {

    private static final String SYSTEM_PROMPT = """
            你是一个有记忆的个人助手，严格遵守：
            1. 用户提到任何个人信息（姓名、偏好、过敏、称呼、习惯等）时，必须先调用 remember 工具保存，再回答
            2. 问题涉及用户个人信息而当前对话中没有答案时，必须先调用 recall 工具检索长期记忆，再回答
            3. 长期记忆中没有相关信息时，如实说不知道，不要编造
            """;

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;

    public MemoryAgentController(ChatModel chatModel, ChatMemory chatMemory, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
    }

    /** sessionId 区分会话：同 sessionId 有短期记忆，跨 sessionId 只能靠长期记忆 */
    @GetMapping("/chat")
    public Map<String, String> chat(
            @RequestParam(defaultValue = "s1") String sessionId,
            @RequestParam String message) {

        // ① 用户消息先入短期记忆（窗口 20 条，超出丢最老）
        chatMemory.add(sessionId, new UserMessage(message));

        // ② 手动组装：System 永远第一位 + 窗口内历史（含刚加入的这条）
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.addAll(chatMemory.get(sessionId));

        // ③ 调模型：挂长期记忆工具（内部循环执行工具，System 顺序在内部循环中保持不变）
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new MemoryTool(vectorStore, "zhangsan"))   // 演示固定用户；生产从登录态取
                .build()
                .getToolCallbacks();
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .build();
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        String reply = response.getResult().getOutput().getText();

        // ④ 回复入短期记忆，供下一轮组装
        chatMemory.add(sessionId, new AssistantMessage(reply));

        return Map.of("reply", reply);
    }
}
