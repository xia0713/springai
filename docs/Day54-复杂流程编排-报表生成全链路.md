# Day 54：Function Calling 实战 —— 复杂流程编排（查数据→分析→生成→发送）

> 所属阶段：第二阶段 · 第 8 周「Function Calling 与业务系统对接」（Day50-56）
> 今日主题：一句话触发"生成月度报表"全流程 —— 多工具串行、中间结果传递、失败兜底
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day50-53 单工具/多工具/NL2SQL/API 对接已跑通

---

## 0. 今天要解决的问题

前四天你的 AI 已经会调工具了，但每次都是"一个意图一次调用"。真实业务要的是**完整任务链**：

```
用户：「帮我生成 8 月的销售报表，发给张三」
   ↓ 要 AI 自己完成：
① 查数据     → 调 querySalesStats("2026-08")
② 分析+写文案 → 模型自己干（这是它的本职）
③ 保存草稿   → 调 saveReportDraft(标题, 正文) → 得到 draftId
④ 发送邮件   → 调 sendReportEmail(draftId, 张三) ← 依赖 ③ 的结果！
   ↓
「报表已生成并发送给张三」
```

这是 Function Calling 的"毕业设计"：**4 步串行、步与步之间有数据依赖、还夹着不可逆的写操作**。跑通它，AI 才真正从"回答问题"变成"完成任务"。

---

## 1. 前置认知：谁在编排？

**模型是编排者，Spring AI 是执行引擎。** 你不需要写任何"先调 A 再调 B"的流程代码：

```
模型收到用户意图 → 决定第 1 步调什么工具
   ↓ Spring AI 执行工具，结果追加进对话
模型看到结果 → 决定第 2 步调什么（带着上一步的记忆）
   ↓ 循环……
模型认为任务完成 → 生成最终自然语言回答
```

Day51 场景⑦你已经见过 2 步串行（查订单→发通知），今天拉长到 4 步并引入"中间结果存储"。**你的工作不是写编排，而是设计好每个工具的"接口"和失败兜底。**

---

## 2. 核心知识点（40 分钟）

### 2.1 中间结果怎么传？—— 两种方式，各有适用

| 方式 | 做法 | 适用 | 今天怎么用 |
|---|---|---|---|
| **A. 上下文传递** | 模型"记住"上一步返回值，直接当下一步参数 | 数据**小**（几行文本） | 查询统计数据 → 模型拿去写文案 |
| **B. 显式存储 + 引用 ID** | 工具把大结果存起来（Map/DB），只返回一个 ID 给模型 | 数据**大**、要**跨请求**、要**可重试** | 报告草稿存 store，只给模型 `draftId` |

**为什么报表草稿要用 B？** 三个理由：
1. **省 token**：几千字的报告在模型上下文里重复出现多次（保存、发送都要带），存起来只传 36 字符的 ID
2. **可重试**：发送失败了，草稿还在 store 里 —— 重试时**不用重新查数据、重新写文案**，直接拿 draftId 重发
3. **可审计**：草稿是落地的中间产物，出了问题能查"当时发了什么"

> 这就是 Day53"结果格式化"的延伸：大结果不该进模型上下文 —— 但又不能丢，所以**存起来、给引用**。这个模式在企业集成里叫"引用传递"（pass by reference），你以后在 Agent（Day59 长期记忆）里还会见到它。

### 2.2 步骤设计的黄金原则：不可逆操作放最后

流程里有读操作（查数据）也有写操作（存草稿、发邮件）。**把不可逆/高危的操作放在流程末尾**：

```
✅ 好的顺序：查数据(读) → 存草稿(可撤销的写) → 发邮件(不可逆的写)
❌ 坏的顺序：发邮件(不可逆) → 存草稿   ← 邮件发了，草稿还没存，失败就抓瞎
```

好处：前面任何一步失败，最贵的那步还没发生，重试零成本。生产里"发邮件前先存草稿"就是这条原则的直接体现。

### 2.3 失败处理：重试 vs 回滚（补偿）

多步流程失败时，按操作性质选策略：

| 操作性质 | 策略 | 例子 |
|---|---|---|
| **幂等读** | 直接重试 | 查数据失败 → 再查一次（Day43 指数退避） |
| **幂等写**（同一 ID 重复写无害） | 重试 + 确定性 ID | 保存草稿（draftId 固定，重存覆盖） |
| **不可逆写** | **补偿**，不是重试 | 邮件发出去了收不回 → 只能"再发一封更正邮件"，或设计成"先草稿后人确认" |

**核心认知：重试只对幂等操作安全。** 对发邮件这种不可逆操作盲目重试 = 用户收到 3 封一样的邮件。今天的方案：草稿先落 store（幂等），发送失败后**草稿不丢**，重发走 draftId —— 把"重试"收敛到一个不可逆动作上，且留给用户决定。

### 2.4 模型写的 SQL vs 工具里写死的 SQL（Day52 的对照）

今天的查询工具和 Day52 有个本质区别：

```
Day52 NL2SQL：模型生成 SQL → 必须上校验器（模型会犯错、会被诱导）
Day54 固定报表：SQL 写死在工具里，参数化传参 → 模型只传月份参数，无注入面
```

**设计准则：能用固定 SQL 就别让模型写 SQL。** 模型写 SQL 的场景是"任意查询需求"（分析师用）；固定报表场景，写死的参数化 SQL 更安全更快。工具暴露给模型的"面"越小越安全。

### 2.5 每步都留痕（审计）

多步流程出问题时，最需要的是"每一步发生了什么"。**每个工具方法进出都打日志**：

```
[step1] querySalesStats(month=2026-08) → 订单 6 笔，销售额 4355.00
[step2] saveReportDraft(title=8月销售报表) → draftId=RP-xxxx
[step3] sendReportEmail(draftId=RP-xxxx, to=张三) → 发送成功
```

这是 Day82 审计日志的雏形，现在养成习惯。

---

## 3. 实操作业（80 分钟）

> 目标：3 个工具组成"报表生成"流程，一句话触发：查数据 → 写文案（模型干）→ 存草稿 → 发邮件。

### 步骤 1：报表流程工具（新建 `tool/ReportFlowTools.java`）

```java
package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报表流程工具集（Day54）：查数据 → 存草稿 → 发邮件。
 * <p>
 * 设计要点：
 * - 中间结果两种传法：统计数据小 → 上下文传递；报告草稿大 → 存 store 只给 draftId（引用传递）
 * - 不可逆操作（发邮件）放流程最后
 * - SQL 写死参数化（对照 Day52 模型写 SQL：固定报表不需要模型生成 SQL，无注入面）
 */
@Slf4j
@RequiredArgsConstructor
public class ReportFlowTools {

    private final JdbcTemplate jdbc;

    /** 草稿存储（中间结果显式存储；生产换 DB/Redis） */
    private static final Map<String, String> DRAFTS = new ConcurrentHashMap<>();

    /** 邮件白名单（Day51 的安全原则延续） */
    private static final Set<String> ALLOWED_RECEIVERS = Set.of("张三", "李四", "王五");

    /** 已发送记录（演示用） */
    public static final List<String> SENT_EMAILS = new java.util.concurrent.CopyOnWriteArrayList<>();

    // ---------- 工具 1：查数据（读，幂等） ----------

    @Tool(description = "查询指定月份的销售统计数据（订单数、总销售额、最畅销商品）。月份格式如 2026-08 或 8月")
    public String querySalesStats(
            @ToolParam(description = "月份，格式如 2026-08 或 8月") String month) {

        // 参数归一化：模型可能传 "8月" 或 "2026-08"，适配层统一成 YYYY-MM
        String ym = normalizeMonth(month);
        if (ym == null) {
            return "月份格式不支持：" + month + "，请用 2026-08 或 8月 这样的格式";
        }

        // SQL 写死 + 参数化（无注入面），排除已取消订单
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS total_sales
                FROM demo_orders
                WHERE to_char(order_date, 'YYYY-MM') = ? AND status <> '已取消'
                """, ym);
        List<Map<String, Object>> top = jdbc.queryForList("""
                SELECT product, SUM(amount) AS sales
                FROM demo_orders
                WHERE to_char(order_date, 'YYYY-MM') = ? AND status <> '已取消'
                GROUP BY product ORDER BY sales DESC LIMIT 1
                """, ym);

        String topProduct = top.isEmpty() ? "无" : top.get(0).get("product") + "（" + top.get(0).get("sales") + " 元）";
        log.info("[step] querySalesStats({}) → 订单 {} 笔，销售额 {}", ym,
                stats.get("order_count"), stats.get("total_sales"));

        return ym + " 月销售统计：订单 " + stats.get("order_count") + " 笔，总销售额 "
                + stats.get("total_sales") + " 元，最畅销商品：" + topProduct;
    }

    // ---------- 工具 2：存草稿（写，但幂等/可覆盖） ----------

    @Tool(description = "保存报表草稿，返回草稿 ID。发送邮件前必须先保存草稿")
    public String saveReportDraft(
            @ToolParam(description = "报表标题") String title,
            @ToolParam(description = "报表正文（基于销售统计数据撰写的分析）") String content) {

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("报表内容为空");
        }
        String draftId = "RP-" + UUID.randomUUID().toString().substring(0, 8);
        DRAFTS.put(draftId, "标题: " + title + "\n\n" + content);
        log.info("[step] saveReportDraft({}) → {}", title, draftId);
        return "草稿已保存，ID: " + draftId;
    }

    // ---------- 工具 3：发邮件（写，不可逆 → 放流程最后） ----------

    @Tool(description = "把已保存的报表草稿通过邮件发送给指定收件人。收件人必须是白名单内的人。发送失败时不要重复重试，先告知用户")
    public String sendReportEmail(
            @ToolParam(description = "草稿 ID（来自 saveReportDraft 的返回）") String draftId,
            @ToolParam(description = "收件人姓名") String receiver) {

        // 安全控制：白名单
        if (!ALLOWED_RECEIVERS.contains(receiver)) {
            return "拒绝发送：" + receiver + " 不在允许的收件人名单内";
        }
        // 依赖前一步结果：草稿必须存在（这就是"中间结果引用传递"）
        String draft = DRAFTS.get(draftId);
        if (draft == null) {
            return "草稿 " + draftId + " 不存在，请先调用 saveReportDraft 保存草稿";
        }

        SENT_EMAILS.add(receiver + " 收到: " + draftId);
        log.info("[step] sendReportEmail({} → {}) 成功", draftId, receiver);
        return "已将报表（" + draftId + "）发送给 " + receiver;
    }

    /** 月份归一化："8月"/"8 月" → 2026-08；"2026-8"/"2026-08" → 2026-08 */
    private String normalizeMonth(String month) {
        if (month == null) return null;
        String m = month.strip();
        Matcher shortM = Pattern.compile("^(\\d{1,2})月?$").matcher(m);
        if (shortM.matches()) {
            return "2026-" + String.format("%02d", Integer.parseInt(shortM.group(1)));
        }
        Matcher fullM = Pattern.compile("^(\\d{4})-(\\d{1,2})$").matcher(m);
        if (fullM.matches()) {
            return fullM.group(1) + "-" + String.format("%02d", Integer.parseInt(fullM.group(2)));
        }
        return null;
    }

    /** 供测试查看草稿 */
    public static Map<String, String> drafts() {
        return DRAFTS;
    }
}
```

### 步骤 2：Bean 注册（`ToolConfig` 追加）

```java
@Bean
public ReportFlowTools reportFlowTools(JdbcTemplate jdbc) {
    return new ReportFlowTools(jdbc);
}
```

### 步骤 3：控制器（新建 `controller/ReportAssistantController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.ReportFlowTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/report-assistant")
public class ReportAssistantController {

    private final ChatClient chatClient;

    /** 关键：system prompt 里写清流程 —— 编排意图靠提示词声明，执行靠模型自主串工具 */
    private static final String WORKFLOW_PROMPT = """
            你是报表助手。当用户要求生成销售报表时，严格按以下流程执行：
            1. 调用 querySalesStats 查询销售统计数据
            2. 基于数据撰写一段简洁的分析文案（含订单数、销售额、畅销品和建议）
            3. 调用 saveReportDraft 保存草稿，拿到草稿 ID
            4. 调用 sendReportEmail 发送（用草稿 ID + 用户指定的收件人）
            全部完成后，用一句话向用户确认结果。
            """;

    public ReportAssistantController(ChatModel chatModel, ReportFlowTools reportFlowTools) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(WORKFLOW_PROMPT)
                .defaultTools(reportFlowTools)
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
```

### 步骤 4：验证（4 个场景）

```bash
# ① 完整流程（核心）：一句话触发 4 步串行
curl "http://localhost:8080/api/report-assistant/ask?question=帮我生成8月的销售报表并发给张三"
# 期望日志依次出现：[step] querySalesStats → saveReportDraft → sendReportEmail
# 期望回答：一句话确认

# ② 验证中间结果存储：看草稿真的落了 store
# （可在测试里调 ReportFlowTools.drafts() 或加个 debug 接口查看）

# ③ 白名单拒绝：流程走到最后一步被拒
curl "http://localhost:8080/api/report-assistant/ask?question=生成9月报表发给赵六"
# 期望：前两步照常执行，发送被拒，模型告知用户白名单限制

# ④ 只查不发：验证模型不越权多发邮件
curl "http://localhost:8080/api/report-assistant/ask?question=查一下8月的销售数据"
# 期望：只调 querySalesStats，不存草稿不发邮件（description 的边界声明生效）
```

---

## 4. 自检标准（不通过不许进 Day55）

- [ ] 场景①：日志里**依次**出现 3 个 `[step]`，回答是"已发送"类确认 —— 全流程自动串起来；
- [ ] 草稿 store 里能查到内容（中间结果**显式存储**生效）；
- [ ] 场景③：白名单拒绝时，**前面步骤的成果没丢**（草稿还在 store，重发不用重查数据）；
- [ ] 场景④：只查数据时模型不乱发邮件（工具 description 的"何时不用我"声明生效）；
- [ ] 能口头讲清：中间结果两种传法（上下文 vs 引用 ID）各自适用场景；重试只对幂等操作安全；不可逆操作放流程最后。

---

## 5. 关键踩坑清单（必背）

1. **以为要自己写编排代码** → 模型 + Spring AI 内部循环就是编排器，你只设计工具接口和 system prompt 流程声明。
2. **大中间结果走上下文** → 报告几千字在上下文里滚几遍，token 翻倍还容易截断。存 store 传 ID。
3. **对不可逆操作重试** → 用户收 3 封重复邮件。重试只对幂等操作安全，不可逆的用补偿/人工确认。
4. **不可逆操作放流程中间** → 后面步骤一失败，前面的"已发送"没法撤。**写操作放最后**。
5. **让模型写所有 SQL** → 固定报表的 SQL 写死参数化，模型只传参数（面越小越安全）。
6. **流程没日志** → 出了问题全靠猜。每个工具进出打 `[step]` 日志。

---

## 6. 今日小结 + 明日预告

**今天你完成了**：Function Calling 的"毕业设计" —— 4 步串行流程（查→写→存→发），中间结果两种传法（上下文/引用 ID）、不可逆操作收尾、幂等与补偿的失败策略。至此你已经掌握了让 AI 完成复杂任务的全部基本手法。

**明日（Day55）**：Function Calling 的收官 —— 成本与性能优化（每次工具调用都是 token！）、工具选型策略（什么逻辑适合做成工具）、边界控制（哪些操作绝不能开放给 AI）。作业是输出一份《企业 AI 工具调用设计规范》，把这一周的经验沉淀成团队可用的文档。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
