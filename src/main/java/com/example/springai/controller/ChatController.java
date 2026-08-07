package com.example.springai.controller;

import com.example.springai.dto.ChatRequest;
import com.example.springai.pojo.User;
import com.example.springai.service.ChatService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/chat")
public class ChatController {
    @Autowired
    private ChatModel chatModel;
    private final ChatClient chatClient;

    private final ChatService chatService;
    public ChatController(ChatClient.Builder builder, ChatService chatService) {
        this.chatClient = builder.build();
        this.chatService = chatService;
    }


    /**
     * 最简单聊天:直接传字符串
     * GET /chat/simple?prompt=你好
     */
    @GetMapping("/simple")
    public String simpleChat(@RequestParam String prompt) {
        return chatModel.call(prompt);
    }


    /**
     * 流式聊天（SSE）
     * GET /api/chat/stream?sessionId=user-001&message=你好
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String message) {
        ChatRequest request = new ChatRequest();
        request.setSessionId(sessionId);
        request.setMessage(message);
        return chatService.streamChat(request);
    }

    /**
     * 多轮对话接口（无会话 id 版本，固定会话名）
     * 连续提问,模型能记住上下文
     */
    @GetMapping("/multi-turn")
    public String chatMultiTurn(@RequestParam String message) {
        ChatRequest request = new ChatRequest();
        request.setSessionId("default-session");
        request.setMessage(message);
        return chatService.chat(request);
    }

    /**
     * 清空对话历史,重新开始
     */
    @GetMapping("/clear")
    public String clearChat() {
        chatService.clearHistory("default-session");
        return "对话已清空,可以重新开始";
    }

    /**
     * 提示词模板示例
     */
    @GetMapping(value = "/template", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithTemplate(@RequestParam String language,
                                         @RequestParam String question) {

        String template = "你是一个{language}专家,请用通俗易懂的方式回答:{question}";

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
    /**
     * 用户信息提取接口
     * 输入一段描述文本,自动提取成结构化的 User 对象
     */
    @GetMapping("/extract/user")
    public User extractUser(@RequestParam String text) {
        // 1. 创建转换器,指定目标类型
        BeanOutputConverter<User> converter = new BeanOutputConverter<>(User.class);

        // 2. 构建提示词模板
        String template = "从下面的文本中提取用户信息.\n\n" +
                "文本内容:\n" +
                "{text}\n\n" +
                "{format}\n\n" +
                "注意:只返回JSON格式,不要任何解释文字、不要markdown标记、不要代码块.";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        Prompt prompt = promptTemplate.create(Map.of(
                "text", text,
                "format", converter.getFormat()  // 自动注入格式要求
        ));

        // 3. 调用大模型
        String jsonResponse = chatClient.prompt(prompt)
                .call()
                .content();

        // 4. 把 JSON 转成 User 对象
        User user = converter.convert(jsonResponse);

        System.out.println("提取结果:" + user);
        return user;
    }

    /**
     * 提取关键词列表
     */
    @GetMapping("/extract/keywords")
    public List<String> extractKeywords(@RequestParam String text) {
        ListOutputConverter converter = new ListOutputConverter(new DefaultConversionService());


        String template = "从下面文本中提取5个核心关键词,用逗号分隔.\n\n" +
                "文本:{text}\n\n" +
                "{format}";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        Prompt prompt = promptTemplate.create(Map.of(
                "text", text,
                "format", converter.getFormat()
        ));

        String response = chatClient.prompt(prompt).call().content();
        return converter.convert(response);
    }


    @GetMapping("/extract/user-safe")
    public User extractUserSafe(@RequestParam String text) {
        try {
            BeanOutputConverter<User> converter = new BeanOutputConverter<>(User.class);

            String template = "从文本中提取用户信息,只返回纯JSON,不要任何其他内容.\n" +
                    "文本:{text}\n" +
                    "{format}";

            PromptTemplate promptTemplate = new PromptTemplate(template);
            Prompt prompt = promptTemplate.create(Map.of("text", text, "format", converter.getFormat()));

            String jsonResponse = chatClient.prompt(prompt).call().content();

            // 清理可能的 markdown 标记（比如模型返回了 ```json ... ```）
            jsonResponse = jsonResponse.replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            return converter.convert(jsonResponse);
        } catch (Exception e) {
            System.out.println("解析失败:" + e.getMessage());
            // 返回空对象或默认值,不崩溃
            return new User();
        }
    }


    /**
     * 调参口诀:
     * 要准确 → temperature 调低,maxTokens 限制住
     * 要创意 → temperature 调高
     * 输出总是重复 → 加 frequencyPenalty频率惩罚,减少重复内容	0-1,默认 0
     * 回答总是跑题 → 降低 temperature + 加强系统提示词
     * presencePenalty	存在惩罚,鼓励说新话题	0-1,默认 0
     * topP	核采样,和 temperature 二选一调	默认 0.9 不用动
     */

    /**
     * 动态切换模型 + 可调参数
     * 通过参数指定用哪个模型、温度多少、最多输出多少字
     */
    @GetMapping("/with-model")
    public String chatWithModel(
            @RequestParam String message,
            @RequestParam(defaultValue = "doubao-2.1-pro-32k") String model,
            @RequestParam(defaultValue = "0.7") Double temperature,
            @RequestParam(defaultValue = "1000") Integer maxTokens) {

        return chatClient.prompt()
                .user(message)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .call()
                .content();
    }

    /**
     * 带用量统计的聊天接口
     */
    @GetMapping("/with-usage")
    public Map<String, Object> chatWithUsage(@RequestParam String message) {
        ChatResponse response = chatClient.prompt()
                .user(message)
                .call()
                .chatResponse();  // 拿完整响应,不只是 content

        String content = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();

        // 估算费用（示例单价,实际以官方为准）
        double inputCost = usage.getPromptTokens() * 0.0008 / 1000;
        double outputCost = usage.getCompletionTokens() * 0.002 / 1000;
        double totalCost = inputCost + outputCost;

        return Map.of(
                "answer", content,
                "promptTokens", usage.getPromptTokens(),
                "completionTokens", usage.getCompletionTokens(),
                "totalTokens", usage.getTotalTokens(),
                "estimatedCostYuan", String.format("%.6f", totalCost)
        );
    }


    @Resource(name = "deepseekChatClient")
    private ChatClient deepseekChatClient;

    @Resource(name = "qwenChatClient")
    private ChatClient qwenClient;

    /**
     * 多厂商切换
     */
    @GetMapping("/provider")
    public String chatByProvider(@RequestParam String message,
                                 @RequestParam(defaultValue = "deepseek") String provider) {
        if ("deepseek".equals(provider)) {
            return deepseekChatClient.prompt().user(message).call().content();
        } else if ("qwen".equals(provider)) {
            return qwenClient.prompt().user(message).call().content();
        }
        throw new IllegalArgumentException("不支持的模型厂商：" + provider);
    }

}
