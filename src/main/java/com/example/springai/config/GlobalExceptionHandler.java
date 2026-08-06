package com.example.springai.config;


import com.example.springai.exception.AiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 所有接口的异常统一在这里处理，返回友好提示，不暴露堆栈信息
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


    /**
     * 处理参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.error("处理参数异常", e);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", 400,
                "message", "系统繁忙，请稍后重试"
        ));
    }

    /**
     * AI 业务异常
     */
    @ExceptionHandler(AiException.class)
    public ResponseEntity<Map<String, Object>> handleAiException(AiException e) {
        log.error("AI业务异常: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "code", e.getCode(),
                "message", e.getMessage()
        ));
    }

    /**
     * 其他异常兜底
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.internalServerError().body(Map.of(
                "code", 500,
                "message", "系统繁忙，请稍后重试"
        ));
    }
}

