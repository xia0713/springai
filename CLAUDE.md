# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Run Commands

```bash
# Build the project
./mvnw clean compile

# Run tests
./mvnw test

# Run a single test
./mvnw test -Dtest=SpringAiApplicationTests

# Start the application
./mvnw spring-boot:run

# Package the application
./mvnw clean package
java -jar target/SpringAI-0.0.1-SNAPSHOT.jar
```

## Architecture Overview

This is a Spring Boot 3.4.4 application using Spring AI 1.0.3 to integrate with an OpenAI-compatible API through a third-party relay platform.

### Core Components

**ChatController** - Single REST controller exposing two endpoints:
- `GET /chat` - Synchronous chat completion, returns full response at once
- `GET /chat/stream` - Streaming chat completion (currently broken - see Known Issues)

Both endpoints accept query parameters:
- `query` (required): User's question
- `role` (optional, default "user"): Role identifier

The controller uses a hardcoded prompt template that prepends system instructions about being a helpful assistant, then appends the user query.

### Configuration

**application.yaml** - Key configuration:
- `spring.ai.openai.base-url`: Points to `https://ai-gateway.ztn.cn` (third-party relay platform)
- `spring.ai.openai.api-key`: API key for the relay platform (environment variable `OPENAI_API_KEY` recommended for production)
- `spring.ai.openai.chat.options.model`: Uses `qwen3.7-plus` model
- `spring.ai.openai.chat.options.temperature`: Set to 0.7 (adjust per use case: 0.1-0.3 for code/extraction, 0.7-0.9 for creative tasks)

### Maven Repository Configuration

The pom.xml bypasses the Alibaba Cloud Maven mirror (which hasn't synced Spring AI 1.0.3) by directly configuring Maven Central. This is critical for dependency resolution.

## Known Issues

**Streaming endpoint not actually streaming**: The `/chat/stream` endpoint currently returns a complete string instead of a reactive stream. The implementation uses `.collectList().block()` which synchronously blocks and collects all chunks, defeating the purpose of streaming. The return type is `String` instead of `Flux<String>`, and the endpoint lacks `produces = MediaType.TEXT_EVENT_STREAM_VALUE`.

## API Usage Examples

```bash
# Synchronous chat
curl "http://localhost:8080/chat?query=什么是SpringAI"

# Synchronous chat with custom role
curl "http://localhost:8080/chat?query=写一首诗&role=assistant"

# Streaming chat (once fixed, use -N to disable buffering)
curl -N "http://localhost:8080/chat/stream?query=讲一个故事"
```
