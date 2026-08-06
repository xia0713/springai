package com.example.springai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "sessionId不能为空")
    private String sessionId;

    @NotBlank(message = "消息不能为空")
    private String message;
}
