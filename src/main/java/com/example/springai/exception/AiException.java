package com.example.springai.exception;

import lombok.Getter;

@Getter
public class AiException extends RuntimeException {
    private final int code;

    public AiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public AiException(String message) {
        this(400, message);
    }
}