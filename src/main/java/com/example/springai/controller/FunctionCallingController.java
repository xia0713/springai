package com.example.springai.controller;

import com.example.springai.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Function Calling 演示（Day50）：天气查询。
 * <p>
 * 用户用自然语言问天气，模型自动判断并调用 WeatherTool。
 * 关键注册方式：ChatClient.builder(chatModel).defaultTools(工具对象)
 */
@RestController
@RequestMapping("/api/weather")
public class FunctionCallingController {

    private final ChatClient chatClient;

    public FunctionCallingController(ChatModel chatModel) {
        // 关键：把带 @Tool 的工具对象注册进 ChatClient
        this.chatClient = ChatClient.builder(chatModel)
                .defaultTools(new WeatherTool())
                .build();
    }

    /**
     * 自然语言天气问答。
     * GET /api/weather/ask?question=北京今天天气怎么样
     */
    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        String answer = chatClient.prompt()
                .user(question)
                .call()
                .content();
        return Map.of("answer", answer);
    }
}
