package com.example.springai.controller;

import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.NotificationTool;
import com.example.springai.tool.OrderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多工具助手（Day51）：3 个工具自动选择。
 * <p>
 * 验证场景：
 * ① 查订单 ② 计算 ③ 发通知 → 各自调对工具
 * ④ 订单号格式错 → 参数校验，模型友好转述
 * ⑤ 除零 → 工具异常 → 降级回传 → 模型友好提示（不 500）
 * ⑥ 白名单外收件人 → 拒绝
 * ⑦ "查 ORD-142 然后通知张三" → 顺序调用两个工具
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是企业助手，可以查订单、做计算、发通知。工具调用失败时，根据错误信息友好地告诉用户。")
                .defaultTools(new OrderTool(), new CalculatorTool(), new NotificationTool())
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
