# Day 59：Agent 记忆体系 —— 短期记忆 + 长期记忆（个人知识库式）

> 所属阶段：第二阶段 · 第 9 周「Agent 基础原理与落地」（Day57-63）
> 今日主题：让 Agent 记住用户 —— 窗口内的短期记忆、跨会话的长期记忆、按需检索的记忆召回
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day58 手写 ReAct；第 5 周向量库（长期记忆的物理载体就是它！）

---

## 0. 今天要解决的问题

你 Day58 的 Agent 是"金鱼" —— 任务结束什么都不记得：

```
会话 A：用户「我叫张三，对花生严重过敏」    → Agent 记住了（仅本轮上下文）
（新会话）
会话 B：用户「我能吃花生酱吗？」          → Agent：请告诉我您的饮食限制... ❌
```

生产级助手必须有记忆分层：**刚才说的要记得（短期），上周说的也要记得（长期）**。

**今天最重要的认知：长期记忆 = 向量库 + RAG 思想。** 你第 5 周学的向量检索，物理载体就是记忆系统 —— 把"用户说过的重要事实"当文档入库，提问时按相似度召回。你已经握着 80% 的积木。

---

## 1. 核心知识点（40 分钟）

### 1.1 记忆的三层架构

| 层 | 存什么 | 载体 | 生命周期 | Spring AI 1.0.3 对应 |
|---|---|---|---|---|
| **短期记忆** | 当前对话的完整上下文 | ChatMemory（窗口） | 会话内，超窗丢最老 | `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` |
| **长期记忆** | 跨会话的关键事实（偏好、结论、承诺） | 向量库（带元数据） | 永久（可删） | `VectorStore.add/similaritySearch`（你已经用熟了） |
| **记忆检索** | 按当前话题召回相关记忆 | 相似度 top-N | 每次请求 | `filterExpression(type+userId)` |

**三层关系**：短期是"工作台"（全量但易失），长期是"档案柜"（精选但持久），检索是"取档案"的动作。

### 1.2 记忆写入的两种模式

| 模式 | 做法 | 优劣 |
|---|---|---|
| **Agentic（模型自主）** | 给 Agent 挂 `remember` / `recall` 两个工具，模型自己决定存什么取什么 | ✅ 教学直观、灵活；❌ 模型可能漏存/滥存 |
| **Pipeline（代码规则）** | 每轮对话结束，代码固定调用小模型提取"值得记的事实"再入库 | 生产更可控；成本可预算 |

今天用 **Agentic 模式**（最直观地理解"记忆也是工具"），生产建议混合：用户明确说"记住 X"走工具，其他由 pipeline 判断。

### 1.3 对话记忆的窗口与压缩（必懂原理）

`MessageWindowChatMemory(maxMessages=20)`：窗口满 20 条，**最老的消息静默丢弃**。两个后果：

1. 旧信息"失忆" —— 第 3 轮说的，第 25 轮就不记得了
2. **压缩的必要性**：生产做法 —— 接近窗口上限时，把最老 N 条用小模型**总结成一条摘要消息**放回窗口，等于"把工作台清干净但留一张便签"。这就是"对话记忆的存储与压缩"的核心思想（今天讲透原理，实现放第 13 周综合项目）

### 1.4 长期记忆的安全三原则（生产红线）

1. **隔离**：记忆必须带 `userId` 元数据，检索时强制过滤 —— **绝不能让 A 用户召回 B 用户的记忆**（Day45 权限思想的直接复用）
2. **可遗忘**：用户要求"忘掉 X"必须能真删（`vectorStore.delete`），这是隐私合规底线（Day82 深化）
3. **防污染**：记忆是用户喂的，可能掺假/注入 —— 高危决策不能只信记忆，要审计记忆写入（谁在何时存了什么）

---

## 2. 实操作业（80 分钟）

> 目标：短期记忆（ChatMemory 窗口）+ 长期记忆（MemoryTool 存取向量库），实现「隔了会话还能回忆起用户偏好」。

### 步骤 1：长期记忆工具（新建 `tool/MemoryTool.java`）

```java
package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 长期记忆工具（Day59）—— 个人知识库式长期记忆。
 * <p>
 * 本质：把用户关键事实当"文档"存向量库（metadata: type=agent_memory + userId），
 * 提问时按相似度召回 —— 就是 RAG 思想用在记忆上。
 * userId 由构造器注入（来自登录态，绝不让模型传 —— Day45 信任边界原则）。
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryTool {

    private final VectorStore vectorStore;
    private final String userId;   // 演示模式由 Controller 传入；生产从登录态取

    @Tool(description = "把用户告诉你的重要个人信息（偏好、过敏、称呼、习惯等）保存到长期记忆。用户说'记住/帮我记一下'时使用")
    public String remember(
            @ToolParam(description = "要记住的事实，一句话完整表述，如'用户对花生严重过敏'") String fact) {

        Document doc = new Document(fact, Map.of(
                "type", "agent_memory",
                "userId", userId,
                "createTime", System.currentTimeMillis()));
        vectorStore.add(List.of(doc));
        log.info("[memory] remember({}) for user {}", fact, userId);
        return "已记住：" + fact;
    }

    @Tool(description = "从长期记忆中检索用户的个人信息。当问题涉及用户个人偏好/历史信息，而当前对话中没有时使用")
    public String recall(
            @ToolParam(description = "要回忆的内容主题，如'饮食过敏'、'称呼'") String query) {

        // 安全三原则之隔离：必须同时过滤 type + userId，绝不跨用户召回
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.eq("type", "agent_memory"),
                b.eq("userId", userId)).build();

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3)
                        .similarityThreshold(0.5)
                        .filterExpression(filter).build());

        log.info("[memory] recall(query={}) → {} 条", query, docs.size());
        if (docs.isEmpty()) {
            return "长期记忆中没有相关信息";
        }
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("；", "记忆中相关的信息：", ""));
    }
}
```

### 步骤 2：短期记忆配置（新建 `config/MemoryConfig.java`）

```java
package com.example.springai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短期记忆配置（Day59）：窗口 20 条，超出丢最老。
 * 生产换 JDBC 仓库（spring-ai-starter-model-chat-memory-repository-jdbc）+ 摘要压缩。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
```

### 步骤 3：带记忆的助手（新建 `controller/MemoryAgentController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.MemoryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 带记忆的助手（Day59）：
 * - 短期：MessageChatMemoryAdvisor 自动注入窗口内历史（conversationId 动态传）
 * - 长期：MemoryTool（模型自主存取向量库）
 */
@RestController
@RequestMapping("/api/memory-agent")
public class MemoryAgentController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public MemoryAgentController(ChatModel chatModel, ChatMemory chatMemory, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个有记忆的个人助手。用户提到个人信息（偏好/过敏/称呼）时：
                        主动调用 remember 保存；回答涉及用户个人的问题时，先调用 recall 检索长期记忆。
                        """)
                .defaultTools(new MemoryTool(vectorStore, "zhangsan"))   // 演示：固定用户
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(ChatOptions.builder().temperature(0.3).build())
                .build();
    }

    /** sessionId 区分会话：同 sessionId 有短期记忆，跨 sessionId 只能靠长期记忆 */
    @GetMapping("/chat")
    public Map<String, String> chat(
            @RequestParam(defaultValue = "s1") String sessionId,
            @RequestParam String message) {
        String reply = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))   // 动态会话 ID
                .call()
                .content();
        return Map.of("reply", reply);
    }
}
```

### 步骤 4：验证（短期和长期分开测）

```bash
# ===== 短期记忆：同一 sessionId 连续对话 =====
curl "http://localhost:8080/api/memory-agent/chat?sessionId=s1&message=我叫张三，我对花生严重过敏，帮我记住"
curl "http://localhost:8080/api/memory-agent/chat?sessionId=s1&message=我叫什么名字？"
# 期望：答"张三"（靠窗口内历史）

# ===== 长期记忆：换一个新 sessionId（短期记忆为空！）=====
curl "http://localhost:8080/api/memory-agent/chat?sessionId=s2&message=我能吃花生酱吗？"
# 期望：模型调用 recall → 召回"对花生严重过敏" → 提醒不能吃
# 这是今天的过关铁证：跨会话回忆成功
```

---

## 3. 自检标准（不通过不许进 Day60）

- [ ] 同会话第 2 问能答出"张三"（短期记忆生效）；
- [ ] **新会话问"我能吃花生酱吗"，答对（长期记忆召回生效）** —— 过关铁证；
- [ ] 日志里看到 `[memory] remember/recall`，且 recall 的过滤条件是 type+userId 双过滤；
- [ ] 换个 userId（把 MemoryTool 构造参数改成 lisi）再问，**召不回**张三的记忆（隔离生效）；
- [ ] 能口头讲清：三层记忆各存什么/载体；窗口溢出的静默丢弃与压缩思路；记忆安全三原则。

---

## 4. 关键踩坑清单（必背）

0. **【实测踩坑】MessageChatMemoryAdvisor 的消息顺序会打炸严格网关**：
   反编译 1.0.3 字节码确认，`before()` 的组装是 `[记忆历史...] + [本次 instructions（含 SystemMessage）]`，
   即 SystemMessage 被挤到历史之后。严格校验的网关（qwen 中转）直接 400
   `"System message must be at the beginning."`。
   **解法**：放弃 Advisor 自动装配，手动组装 —— `chatMemory.add()` 存取 + 自己拼
   `[System] + history`（见 MemoryAgentController 最终实现）。与 Day58 手写循环同一哲学：
   控制权在自己手里，任何网关都兼容。
1. **记忆和文档混存不隔离** → 用户记忆混进知识库检索结果（隐私事故）。必须 `type + userId` 双过滤。
2. **让模型传 userId** → 信任边界破功（Day45 红线）。userId 只能来自登录态/代码注入。
3. **窗口溢出静默丢弃** → "第 3 轮说的第 25 轮忘"。生产用摘要压缩（把最老 N 条总结成便签）。
4. **InMemoryChatMemoryRepository 重启即丢** → 短期记忆也丢。生产换 JDBC 仓库。
5. **只存不删** → 用户"忘掉我"必须真删（vectorStore.delete 按 userId），隐私合规底线。
6. **记忆污染不设防** → 记忆是用户喂的，可能掺假。高危决策不能只信记忆，写入要审计。
7. **模型不调 remember 工具** → 说"帮我记住"模型只回"好的"不调工具。解法：system 指令用"必须先调用 remember 再回答"的强制表述，并查 `[memory] remember` 日志确认真的调了。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：Agent 记忆体系 —— 短期（ChatMemory 窗口）、长期（向量库 + RAG 思想）、检索（type+userId 双过滤）、以及生产三原则（隔离/可遗忘/防污染）。最大收获：**长期记忆不需要新轮子，它就是你第 5 周学的向量库换个用途。**

**明日（Day60）**：典型 Agent 场景 —— 数据分析助手、**运维排障助手**。作业做一个"输入报错日志 → 分步排查 → 给出方案"的排障 Agent（结合 Day58 的 ReAct + 今天的记忆），并学习"如何判断一个 Agent 好不好用"。

---

*文档生成日期：2026-09-08 · 技术版本：Spring AI 1.0.3 / Java 21*
