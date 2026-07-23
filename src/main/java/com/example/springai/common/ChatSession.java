package com.example.springai.common;


import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatSession {

    // 存储对话历史
    private final List<Message> messages = new ArrayList<>();

    public ChatSession() {
        // 初始化系统提示词
        messages.add(new SystemMessage("你是一个耐心的Java编程老师，回答问题要简洁易懂，多用代码举例。"));
    }

    /**
     * 添加用户消息，并返回当前所有历史消息
     */
    public List<Message> addUserMessage(String userMessage) {
        messages.add(new UserMessage(userMessage));
        return new ArrayList<>(messages); // 返回副本，防止外部修改
    }

    /**
     * 添加模型的回答到历史
     */
    public void addAssistantMessage(String assistantMessage) {
        messages.add(new AssistantMessage(assistantMessage));
    }

    /**
     * 清空对话历史
     */
    public void clear() {
        messages.clear();
        messages.add(new SystemMessage("你是一个耐心的Java编程老师，回答问题要简洁易懂，多用代码举例。"));
    }
}