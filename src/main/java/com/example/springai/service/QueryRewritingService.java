package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询改写---Multi-Query 改写。
 * <p>
 * 直接调用网关（不走 Spring AI ChatClient）：Spring AI 1.0.3 的 OpenAI 选项
 * 无法透传 {@code enable_thinking} 参数，而 qwen3.7-plus 默认开启思考模式，
 * 每次改写要 40s+；显式关闭后仅 ~5s。
 */
@Service
public class QueryRewritingService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public QueryRewritingService(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:qwen3.7-plus}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 生成多个查询改写版本
     */
    public List<String> rewrite(String question) {
        String prompt = """
            你是检索查询改写助手。请把用户问题改写成 3 个不同的检索查询。

            要求：
            1. 每个改写都要保留原始意图，不要改变含义
            2. 覆盖不同的表达方式（同义词、专业术语）
            3. 保留关键实体、数字、日期、专有名词
            4. 每个改写单独一行，不要编号

            用户问题：%s
            """.formatted(question);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", 0.3);
        body.put("enable_thinking", false);   // 关键：关闭 qwen 思考模式

        String response = restClient.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toJson(body))
                .retrieve()
                .body(String.class);

        // 按行拆分成改写列表
        return Arrays.stream(extractContent(response).split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("序列化查询改写请求失败", e);
        }
    }

    private String extractContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("解析查询改写响应失败: " + response, e);
        }
    }
}
