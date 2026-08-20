package com.example.springai.service;


import com.example.springai.dto.ChatRequest;
import com.example.springai.dto.CodeReviewResult;
import com.example.springai.exception.AiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeReviewService {

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final PromptTemplate reviewTemplate;
    private final BeanOutputConverter<CodeReviewResult> converter;

    // 注入注入检测的正则（Day 27 输入防火墙的轻量实现）
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略.{0,10}(之前|以上|上述).{0,10}(指令|规则)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("输出.{0,10}(系统提示|system\\\s*prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你现在是.{0,10}(DAN|不受限制|越狱)", Pattern.CASE_INSENSITIVE)
    );

    public CodeReviewService(ChatModel chatModel,
                             ChatClient.Builder chatClientBuilder,
                             @Value("classpath:prompts/code-review.st") Resource templateResource) {
        this.chatModel = chatModel;
        this.chatClient = chatClientBuilder.build();
        this.reviewTemplate = new PromptTemplate(templateResource);   // Prompt 外置
        this.converter = new BeanOutputConverter<>(CodeReviewResult.class);
    }

    /**
     * 审查一段 Java 代码。
     */
    public CodeReviewResult review(String code) {
        // 1. 入参校验（Day 24 鲁棒性）
        validateInput(code);

        // 2. 构造 Prompt：注入代码 + 输出格式约束
        Prompt prompt = reviewTemplate.create(Map.of(
                "code", code,
                "format", converter.getFormat()   // 自动生成 JSON Schema
        ));

        // 3. 调模型（低温已配在 yml，这里也可显式覆盖）
        String raw = chatClient.prompt(prompt).call().content();

        // 4. 解析 + 兜底（Day 23 的安全解析）
        return safeConvert(raw);
    }


    /**
     * 混合双路流式审查：
     * - event = "content" : 原始文本片段（实时流式给前端渲染）
     * - event = "done"    : 最终的结构化 JSON（解析完成后的 CodeReviewResult）
     *
     * 实现方式：流式收集所有文本片段，在 doFinally 阶段对完整文本做 JSON 解析，
     * 并以一条 "done" SSE 事件追加到流的末尾。
     */
    public Flux<ServerSentEvent<String>> reviewStream(String code) {
        validateInput(code);

        Prompt prompt = reviewTemplate.create(Map.of(
                "code", code,
                "format", converter.getFormat()
        ));

        // 线程安全地收集流式文本片段
        StringBuilder fullContent = new StringBuilder();

        Flux<ServerSentEvent<String>> contentStream = chatClient.prompt(prompt)
                .stream()
                .content()
                .filter(chunk -> chunk != null && !chunk.isEmpty())
                .map(chunk -> {
                    fullContent.append(chunk);
                    return ServerSentEvent.<String>builder()
                            .event("content")
                            .data(chunk)
                            .build();
                });

        // 流结束后追加一条 "done" 事件，携带解析后的结构化结果
        return contentStream.concatWith(
                Flux.defer(() -> {
                    try {
                        CodeReviewResult result = safeConvert(fullContent.toString());
                        return Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("done")
                                        .data(toJson(result))
                                        .build()
                        );
                    } catch (Exception e) {
                        // 解析失败时返回错误事件，前端可据此降级展示
                        return Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("error")
                                        .data("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                                        .build()
                        );
                    }
                })
        );
    }

    // ---------- 私有方法 ----------

    private void validateInput(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("代码不能为空");
        }
        if (code.length() > 20000) {
            throw new IllegalArgumentException("代码过长，单次审查请控制在 20000 字符以内");
        }
        // 输入防火墙：明显注入直接拒
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(code).find()) {
                throw new IllegalArgumentException("输入包含异常内容，已拒绝");
            }
        }
    }

    /**
     * 兜底解析：模型偶尔在 JSON 外包 markdown 代码块或加解释文字。
     */
    private CodeReviewResult safeConvert(String raw) {
        try {
            return converter.convert(raw);
        } catch (Exception e) {
            // 兜底：提取第一个 {...} JSON
            Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher m = jsonPattern.matcher(raw);
            if (m.find()) {
                return converter.convert(m.group());
            }
            throw new IllegalStateException("无法解析模型输出: " + raw);
        }
    }

    private String toJson(CodeReviewResult result) {
        try {
            return new ObjectMapper().writeValueAsString(result);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
    }
}

