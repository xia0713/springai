package com.example.springai.controller;

import com.example.springai.tool.ErpEmployeeTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ERP 自然语言助手（Day53）：用户无需知道接口细节，自然语言即可完成接口调用。
 */
@RestController
@RequestMapping("/api/erp-assistant")
public class ErpAssistantController {

    private final ChatClient chatClient;

    public ErpAssistantController(ChatModel chatModel, ErpEmployeeTool erpEmployeeTool) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是企业助手，可以查询公司员工信息。")
                .defaultTools(erpEmployeeTool)
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
