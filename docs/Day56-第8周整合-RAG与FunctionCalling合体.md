# Day 56：第 8 周整合 —— RAG + Function Calling 合体（智能助手雏形）

> 所属阶段：第二阶段 · 第 8 周收官整合日
> 今日主题：一个入口同时能查文档和查业务数据，AI 自动路由 —— 你综合项目的原型
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：第 7 周 RAG（Day29-49）+ 第 8 周 Function Calling（Day50-55）全部完成

---

## 0. 今天要解决的问题

你现在有两个"半成品"：

```
知识库助手（第 7 周）：
   /api/documents/ask          → 只能查文档（RAG）
   「退货政策是什么？」✅
   「8月销售额多少？」❌ （数据不在文档里）

数据助手（第 8 周）：
   /api/nl2sql/ask             → 只能查数据库
   「8月销售额多少？」✅
   「退货政策是什么？」❌ （数据不在库里）
```

用户不管这些 —— 他要**一个入口问所有问题**。今天就把它们合体：

```
「退货政策是什么？」 → AI 自动选 searchKnowledge → RAG 检索 → 带来源回答
「8月卖了多少钱？」  → AI 自动选 queryBusinessData → SQL 查库 → 精确数字
「查一下ORD-142状态然后发通知」→ AI 自动选 OrderTool → NotificationTool
```

**自检标准原文：用户提问自动判断是查文档还是查数据，对应处理算过关。**

---

## 1. 核心认知：路由不需要"路由器"

新手会想："我要不要先写一个分类器，判断问题类型，再转发到 RAG 或 SQL？"

**不需要。Day51 的多工具自动选择就是路由器** —— 把两类工具都挂上，靠 description 把边界写清楚，模型自己路由：

```
统一助手
   ├─ searchKnowledge   "搜索企业知识库文档（制度/政策/产品说明）..."
   ├─ queryBusinessData "执行只读 SQL 查业务数据（订单/销售额）..."
   ├─ querySalesStats   "查询月度销售统计报表..."（固定报表，更快更稳）
   └─ OrderTool / NotificationTool ...
        ↑
模型看问题语义 → 自动选对工具域
```

### 两种"查"的本质区别（模型路由的依据，也是你要讲清的面试点）

| | RAG（查文档） | SQL（查数据） |
|---|---|---|
| 数据形态 | 非结构化文本 | 结构化表格 |
| 匹配方式 | 语义相似度（模糊） | 精确条件（确定） |
| 结果特征 | 相关片段 + 需要归纳 | 数字/行集 |
| 失败形态 | 检索不到 → 拒答 | 0 行 / SQL 错 → 说明 |
| 适合问题 | "退货政策是什么""年假几天" | "8月销售额""有多少待发货" |

**经验法则（写给 system prompt 也写进你的脑子里）**：问"是什么/怎么规定"→ 文档；问"多少/几个/排名"→ 数据。

---

## 2. 核心知识点（40 分钟）

### 2.1 统一入口架构

```
用户提问
   ↓
EnterpriseAssistantController（唯一入口）
   ↓  system prompt：能力清单 + 路由经验法则 + 拒答规则
模型自动选工具：
   ├─ 文档类 → searchKnowledge（内含 owner 权限过滤，Day45 能力）
   ├─ 数据类 → querySalesStats（写死 SQL，Day54 模式）/ queryBusinessData（NL2SQL+校验器，Day52 能力）
   └─ 动作类 → OrderTool / NotificationTool（白名单，Day51 能力）
   ↓
每步 [step] 审计日志（Day54 习惯）
   ↓
汇总生成最终回答
```

**这就是你第 13 周综合项目「企业智能助手平台」的骨架。** 后面两周（Agent、场景方案）都是往这个骨架上加肌肉。

### 2.2 安全能力组装 —— 整合日是把防线全部接上

今天不是写新功能，是**把前面五天的安全能力组装进统一入口**：

| 防线 | 来源 | 在今天的位置 |
|---|---|---|
| owner 权限过滤 | Day45 | searchKnowledge 内部 |
| SQL 三重校验 | Day52 | queryBusinessData 内部 |
| 工具异常降级 | Day51 | 全局 ToolConfig |
| 写操作白名单 | Day51/54 | NotificationTool / sendReportEmail |
| 不可逆放最后 | Day54 | 流程型任务的工具顺序 |

### 2.3 拒答的统一策略

两个工具域的"空结果"要给模型统一的可理解信号：

```
RAG 检索为空   → 返回 "知识库中未找到相关内容"   （模型转述拒答）
SQL 查询 0 行  → 返回 "查询结果为空（0 行）"      （模型说明而非编造）
都不合适       → system prompt 声明 "不属于以上能力的问题，诚实说不知道"
```

**统一思想：每个工具的失败都要以"模型能理解并转述"的方式返回 —— 这是 Day51 错误分级原则在整合场景的应用。**

### 2.4 权限的一个诚实说明（重要）

你向量库现在的测试语料（`RagTestCorpus` 的 77 条 chunk）**没有 owner 元数据**。所以今天的 `searchKnowledge`：
- **不传 currentUser** → 不加 owner 过滤，能搜到全部语料（演示模式）
- **传 currentUser** → 加 `owner == 当前用户` 过滤（Day45 模式，只搜到本人上传的文档）

生产上**必须**统一成"永远过滤"（Day45 的结论），今天的开关只是为了让测试语料能搜到。这个妥协写进代码注释。

---

## 3. 实操作业（80 分钟）

> 目标：`KnowledgeSearchTool` + 统一入口 `EnterpriseAssistantController`，三类问题自动路由。

### 步骤 1：知识库检索工具（新建 `tool/KnowledgeSearchTool.java`）

```java
package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库检索工具（Day56）—— 把第 7 周的 RAG 能力包装成工具。
 * ⚠️ 生产必须加 owner 过滤（Day45）；演示模式允许不过滤（测试语料无 owner 元数据）。
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final VectorStore vectorStore;
    private final String owner;   // null = 演示模式不过滤；非空 = 只搜该用户的文档

    @Tool(description = "搜索企业知识库文档（制度、政策、产品说明、员工手册等）。回答'是什么/怎么规定'类问题用这个")
    public String searchKnowledge(
            @ToolParam(description = "检索关键词或问题") String query) {

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.6);
        if (owner != null && !owner.isBlank()) {
            builder.filterExpression(new FilterExpressionBuilder().eq("owner", owner).build());
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());

        log.info("[step] searchKnowledge(query={}) → {} 条", query, docs.size());

        if (docs.isEmpty()) {
            return "知识库中未找到相关内容";
        }
        return docs.stream()
                .map(d -> "[资料·来源:%s] %s".formatted(
                        d.getMetadata().getOrDefault("source", "未知"),
                        d.getText()))
                .collect(Collectors.joining("\n\n"));
    }
}
```

### 步骤 2：统一入口（新建 `controller/EnterpriseAssistantController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.KnowledgeSearchTool;
import com.example.springai.tool.OrderTool;
import com.example.springai.tool.ReportFlowTools;
import com.example.springai.tool.SqlQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 企业智能助手统一入口（Day56）—— RAG + Function Calling 合体。
 * 这是第 13 周综合项目的骨架原型。
 */
@RestController
@RequestMapping("/api/enterprise-assistant")
public class EnterpriseAssistantController {

    private final ChatClient chatClient;

    public EnterpriseAssistantController(ChatModel chatModel,
                                         VectorStore vectorStore,
                                         JdbcTemplate jdbc) {
        // 演示模式：owner 传 null 不过滤；传用户名则按 Day45 过滤
        KnowledgeSearchTool knowledgeTool = new KnowledgeSearchTool(vectorStore, null);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是企业智能助手，具备以下能力：
                        1. 查知识库文档：制度、政策、产品说明、员工手册 → 用 searchKnowledge
                        2. 查月度销售统计 → 用 querySalesStats
                        3. 查任意业务数据（订单/商品/状态）→ 生成 SQL 用 queryDatabase
                        4. 查订单物流 → 用 getOrderStatus；计算 → 用 calculate

                        路由经验：问"是什么/怎么规定"→ 查文档；问"多少/几个/排名"→ 查数据。
                        回答规则：
                        - 文档类回答标注来源
                        - 数据类回答给出精确数字
                        - 检索/查询都为空时，诚实说"未找到"，禁止编造
                        """)
                .defaultTools(knowledgeTool, new ReportFlowTools(jdbc), new SqlQueryTool(jdbc),
                        new OrderTool(), new CalculatorTool())
                .defaultOptions(ChatOptions.builder().temperature(0.2).build())
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
```

### 步骤 3：验证（6 个问题，覆盖全部路由）

```bash
# ① 文档类 → 应走 searchKnowledge（RAG）
curl "http://localhost:8080/api/enterprise-assistant/ask?question=退货需要什么条件"

# ② 文档类（产品知识）
curl "http://localhost:8080/api/enterprise-assistant/ask?question=蓝牙耳机X1的续航多久"

# ③ 数据类（固定报表）→ 应走 querySalesStats
curl "http://localhost:8080/api/enterprise-assistant/ask?question=8月份销售额是多少"

# ④ 数据类（灵活查询）→ 应走 queryDatabase 生成 SQL
curl "http://localhost:8080/api/enterprise-assistant/ask?question=有多少订单还在待发货状态"

# ⑤ 动作类 → 应走 getOrderStatus
curl "http://localhost:8080/api/enterprise-assistant/ask?question=ORD-142到哪了"

# ⑥ 都不沾边 → 诚实拒答
curl "http://localhost:8080/api/enterprise-assistant/ask?question=今天股市行情怎么样"
```

**判别方法**：看启动日志里的 `[step]` 行 —— 每种问题走了哪个工具一目了然。

---

## 4. 自检标准（不通过不许进 Day57）

- [ ] ①②（文档类）走了 `searchKnowledge`，回答**带来源**；
- [ ] ③（固定报表）走了 `querySalesStats`，④（灵活查询）走了 `queryDatabase` —— 两种数据查询也分对了；
- [ ] ⑤（动作类）走了 `getOrderStatus`；
- [ ] ⑥ 模型**诚实说不知道**，不编造；
- [ ] 能口头讲清：为什么不需要单独写"路由器"（工具自动选择就是路由）；RAG 和 SQL 两种"查"的差异表。

---

## 5. 关键踩坑清单（必背）

1. **先写分类器再转发** → 多余。工具 description 就是路由器，Day51 的能力在能力域级别的应用。
2. **工具挂太多** → description 全塞进每次请求，token 翻倍还干扰选择。按场景收窄（Day55 规范第 4.1 条）。
3. **拒答不统一** → RAG 空结果和 SQL 0 行要都给模型可理解的文本信号，否则模型编造。
4. **演示模式的 owner 开关忘了生产关掉** → 生产必须永远过滤（Day45 结论），代码注释里标了 ⚠️。
5. **一个入口挂所有工具却用一个 temperature** → 工具路由 0.2 合适，但分析文案类可能要高些 —— 按场景分 ChatClient（Day55 规范）。

---

## 6. 第 8 周收官 + 阶段位置

```
第 8 周（Day50-56）Function Calling 与业务系统对接 → ✅ 今天全部完成
├─ Day50-54：单工具 → 多工具 → NL2SQL → API 对接 → 流程编排
├─ Day55：《企业 AI 工具调用设计规范》
└─ Day56：RAG + FC 合体 → 企业智能助手原型 ✅

第二阶段进度：Day29-56 完成（28/42 天）
├─ 第 5-7 周 RAG 全链路+工程化  ✅
├─ 第 8 周 Function Calling    ✅ 今天
├─ 第 9 周 Agent（Day57-63）   ⏭ 明天开始
└─ 第 10 周 落地场景认知（Day64-70） 待开始
```

**你手里现在有了一个真正的"企业智能助手"原型**：知识库问答 + 数据查询 + 工具调用 + 权限隔离 + 安全规范。第 13 周的综合项目就是它的成熟版。

**明日（Day57，进入第 9 周 Agent）**：Agent 核心概念 —— 规划、工具调用、记忆、反思四大能力，当前 Agent 的能力边界（哪些是伪需求），常见框架对比。今天是"认知日"不写代码，输出《Agent 能力与场景分析报告》。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
