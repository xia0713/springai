# Spring AI 基础开发脚手架

## 项目简介
基于 Spring Boot + Spring AI 1.0.3 的 AI 开发基础脚手架，开箱即用。

## 技术栈
- Java 17
- Spring Boot 3.3.x
- Spring AI 1.0.3
- 大模型：豆包 / 通义千问（OpenAI 兼容协议）

## 快速开始
1. 修改 application.yml 中的 api-key 为你自己的
2. 启动项目：SpringAiDemoApplication
3. 访问 http://localhost:8080/chat?message=你好 测试

## 接口清单

### 1. 基础聊天
GET /chat?message=xxx

### 2. 流式聊天
GET /chat/stream?message=xxx （SSE）

### 3. 多轮对话
GET /chat/multi-turn?message=xxx
GET /chat/clear

### 4. 结构化输出
GET /extract/user?text=xxx
GET /extract/keywords?text=xxx

### 5. 动态参数
GET /chat/with-options?message=xxx&model=xxx&temperature=0.7

### 6. 用量统计
GET /chat/with-usage?message=xxx

## 切换其他模型
修改 base-url 和 api-key 即可，支持所有 OpenAI 兼容协议的大模型。

## 注意事项
- API Key 注意保密，生产环境用环境变量或配置中心
- 生产环境对话历史建议存 Redis，不要存在内存
- 注意控制 Token 用量，设置消费上限
