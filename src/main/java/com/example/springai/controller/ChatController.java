package com.example.springai.controller;


import com.example.springai.common.ChatSession;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/chat")
public class ChatController {
    @Autowired
    private ChatModel chatModel;
    private final ChatClient chatClient;
    private final ChatSession chatSession;

    // 构造注入
    public ChatController(ChatClient.Builder builder, ChatSession chatSession) {
        this.chatClient = builder.build();
        this.chatSession = chatSession;
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
     * 流式输出接口（1.x 版本写法）
     * 这个我们 Day10 才学，今天不用急
     */
//    @GetMapping(value = "/chat/stream", produces = "text/event-stream")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }

    @GetMapping("/translator")
    public String translate(@RequestParam String text) {
        return chatClient.prompt()
                .system("你是一个专业翻译官，把所有输入翻译成英文")
                .user(text)
                .call()
                .content();
    }

    /**
     * 多轮对话接口
     * 连续提问，模型能记住上下文
     */
    @GetMapping("/multi-turn")
    public String chatMultiTurn(@RequestParam String message) {
        // 1. 把用户问题加入历史，拿到完整历史列表
        List<Message> history = chatSession.addUserMessage(message);

        // 2. 带着历史调用大模型
        String response = chatClient.prompt()
                .messages(history)  // 关键：传入完整对话历史
                .call()
                .content();

        // 3. 把模型回答也加入历史
        chatSession.addAssistantMessage(response);

        return response;
    }

    /**
     * 清空对话历史，重新开始
     */
    @GetMapping("/clear")
    public String clearChat() {
        chatSession.clear();
        return "对话已清空，可以重新开始";
    }

    /**
     * 提示词模板示例
     */
    @GetMapping(value = "/template", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithTemplate(@RequestParam String language,
                                         @RequestParam String question) {

        String template = "你是一个{language}专家，请用通俗易懂的方式回答：{question}";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        String promptText = promptTemplate.render(Map.of(
                "language", language,
                "question", question
        ));


        return chatClient.prompt()
                .user(promptText)
                .stream()
                .content();
    }
}
