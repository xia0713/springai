package com.example.springai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeRequest {
    @NotBlank(message = "内容不能为空")
    private String content;

    private String category;
    private String source;
}