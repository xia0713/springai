package com.example.springai.service;

import com.example.springai.dto.ChatRequest;
import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    // 内存对话历史（生产环境用 Redis）
    private final Map<String, List<Message>> conversationHistory = new ConcurrentHashMap<>();

    /**
     * 同步聊天
     */
    public String chat(ChatRequest request) {
        try {
            String sessionId = request.getSessionId();
            String userMessage = request.getMessage();

            // 获取历史对话
            List<Message> history = conversationHistory.computeIfAbsent(
                    sessionId, k -> new ArrayList<>()
            );

            // 构建带历史的请求
            String response = chatClient.prompt()
                    .messages(history)
                    .user(userMessage)
                    .call()
                    .content();

            // 保存到历史
            history.add(new UserMessage(userMessage));
            history.add(new AssistantMessage(response));

            // 只保留最近 10 轮（20 条消息），防止上下文过长
            if (history.size() > 20) {
                history.subList(0, history.size() - 20).clear();
            }

            log.info("聊天完成 sessionId={}, 消息长度={}", sessionId, userMessage.length());
            return response;

        } catch (Exception e) {
            log.error("聊天失败", e);
            throw new AiException("AI 回复失败: " + e.getMessage());
        }
    }

    /**
     * 流式聊天（SSE）
     */
    public Flux<String> streamChat(ChatRequest request) {
        try {
            String sessionId = request.getSessionId();
            String userMessage = request.getMessage();

            List<Message> history = conversationHistory.computeIfAbsent(
                    sessionId, k -> new ArrayList<>()
            );

            Flux<String> response = chatClient.prompt()
                    .messages(history)
                    .user(userMessage)
                    .stream()
                    .content();

            // 流式结束后保存历史（注意：流是惰性的，这里简化处理）
            history.add(new UserMessage(userMessage));

            log.info("流式聊天开始 sessionId={}", sessionId);
            return response;

        } catch (Exception e) {
            log.error("流式聊天失败", e);
            throw new AiException("AI 流式回复失败: " + e.getMessage());
        }
    }

    /**
     * 清空对话历史
     */
    public void clearHistory(String sessionId) {
        conversationHistory.remove(sessionId);
        log.info("清空对话历史 sessionId={}", sessionId);
    }
}
