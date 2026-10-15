package com.example.springai.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * AI 能力 SDK（Day73）—— 业务系统接入的唯一依赖。
 * <p>
 * 设计四原则：
 * ① 薄：JDK 自带 HttpClient + Jackson（几乎所有 Java 项目都有），不引入 Spring AI
 * ② 超时适配 LLM：30s（生成本来就慢；Day53 的 3s/5s 是普通接口的值，别照搬）
 * ③ 错误可读：401/超时都转成带人话的 AiSdkException
 * ④ 响应结构兼容：只依赖 answer 字段，网关加字段不影响旧 SDK
 */
public class AiSdkClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiSdkClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 智能问答：内部自动享受 缓存/模型路由/熔断降级（业务系统无感知）。
     * 业务系统的全部接入代码 = 构造本类 + 调本方法。
     */
    @SuppressWarnings("unchecked")
    public String chat(String question) {
        try {
            String url = baseUrl + "/api/ai-gateway/chat?question="
                    + URLEncoder.encode(question, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-API-KEY", apiKey)
                    .timeout(Duration.ofSeconds(30))   // LLM 场景放宽（生成本来就慢）
                    .GET()
                    .build();
            HttpResponse<String> resp =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 401) {
                throw new AiSdkException("鉴权失败：请检查 API Key");
            }
            if (resp.statusCode() != 200) {
                throw new AiSdkException("AI 网关异常：HTTP " + resp.statusCode());
            }
            Map<String, Object> body = objectMapper.readValue(resp.body(), Map.class);
            return String.valueOf(body.get("answer"));
        } catch (AiSdkException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiSdkException("AI 服务超时，请稍后重试");
        } catch (Exception e) {
            throw new AiSdkException("AI 调用失败：" + e.getMessage(), e);
        }
    }

    /** SDK 统一异常：可读信息在 message，原始异常在 cause */
    public static class AiSdkException extends RuntimeException {
        public AiSdkException(String message) { super(message); }
        public AiSdkException(String message, Throwable cause) { super(message, cause); }
    }
}
