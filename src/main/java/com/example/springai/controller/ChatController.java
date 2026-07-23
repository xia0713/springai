package com.example.springai.controller;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatClient chatClient;
    @Autowired
    private ChatModel chatModel;
    // 构造注入，Spring AI 自动帮我们配置好 ChatClient
    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }
    @GetMapping("/simple")
    public String simpleChat(@RequestParam String prompt) {
        chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        // 1. 最简单：直接传字符串
        String reply = chatModel.call(prompt);

// 2. 需要系统提示词 + 用户消息
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage("你是翻译助手"),
                new UserMessage("把hello翻译成中文")
        )));
        String text = response.getResult().getOutput().getText();

// 3. 流式输出
        Disposable subscribe = chatModel.stream("写一首诗")
                .doOnNext(chunk -> System.out.print(chunk))
                .subscribe();


        return chatModel.call(prompt);
    }

    /**
     * 异步调用（不阻塞主线程）
     */
//    @GetMapping("/chat/async")
//    public CompletableFuture<String> chatAsync(@RequestParam String message) {
//        return chatClient.prompt()
//                .user(message)
//                .call()
//                .entity(String.class)
//                .toFuture();
//    }
}
