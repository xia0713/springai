# Day 52：Function Calling 实战 —— NL2SQL（自然语言查数据库）

> 所属阶段：第二阶段 · 第 8 周「Function Calling 与业务系统对接」（Day50-56）
> 今日主题：让用户说人话，AI 生成 SQL 查库返回表格 —— 表结构注入、语法约束、**只读安全防线**
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day50-51 工具调用与异常降级已跑通

---

## 0. 今天要解决的问题

企业里最有价值的数据不在文档里，在**数据库**里：

```
「上个月销售额最高的商品是什么？」
「有多少订单还处于待发货状态？」
「张三这个月报销了多少钱？」
```

RAG 回答不了这些问题（数据不在文档里）。你需要的是：**用户说人话 → AI 生成 SQL → 执行 → 返回表格结果**。这就是 NL2SQL，Function Calling 最经典、面试最爱问的场景。

**但它也是风险最高的场景** —— 一旦放开，AI 生成的 SQL 万一是 `DELETE FROM orders` 呢？所以今天的主题一半在「怎么查得准」，另一半在「**怎么保证绝不写库**」。

---

## 1. 架构：NL2SQL 的两条路线

| 路线 | 做法 | 评价 |
|---|---|---|
| A. 纯 Prompt 生成 | 把表结构塞进 prompt，让模型直接输出 SQL 文本，你拿去执行 | 能跑，但模型输出不受控、没有工具调用的自动重试 |
| **B. Function Calling 包装（今天用）** | 模型调用 `queryDatabase(sql)` 工具，工具内**先安全校验再执行** | ✅ 校验逻辑在代码里（硬约束），异常可降级，天然衔接 Day50-51 |

**选 B 的核心理由：安全校验必须是代码级硬约束，不能指望 prompt。** prompt 会"建议"，代码才"强制"。

```
用户问题
   ↓
模型（system prompt 里已注入表结构）生成 SQL，发起工具调用
   ↓
SqlQueryTool.queryDatabase(sql)
   ├─ ① 安全校验（白名单 SELECT + 黑名单关键字 + 禁分号多语句）
   ├─ ② 执行（JdbcTemplate.queryForList）
   └─ ③ 格式化成 Markdown 表格返回
   ↓
模型拿到表格数据 → 生成自然语言回答
```

---

## 2. 核心知识点（40 分钟）

### 2.1 表结构注入 —— NL2SQL 准确率的第一要素

模型不可能知道你的库长什么样。**把 DDL（建表语句）+ 列注释写进 system prompt**，是 NL2SQL 准确率的第一决定因素：

```
你能查询的表结构如下：
CREATE TABLE demo_orders (
    order_id   VARCHAR(20),   -- 订单号
    product    VARCHAR(50),   -- 商品名称
    amount     NUMERIC(10,2), -- 金额（元）
    quantity   INT,           -- 数量
    status     VARCHAR(20),   -- 状态：待发货/已发货/已签收/已取消
    order_date DATE           -- 下单日期
);
```

**三个注入技巧：**
1. **列后加中文注释** —— 模型靠这个理解"amount 是金额不是数量"
2. **枚举值写清楚** —— `status: 待发货/已发货/已签收/已取消`，不然模型会猜 `status='shipped'` 这种库里不存在的值
3. **只注入相关表** —— 库里 100 张表别全塞，按业务域给（大库要做"表路由"，即先让模型选表再生成 SQL，这是进阶话题）

### 2.2 SQL 语法约束 —— prompt 层软约束

system prompt 里明确规则（temperature 0.1，低温保证 SQL 稳定）：

```
规则：
1. 只能生成 SELECT 语句，禁止任何增删改操作
2. 必须带 LIMIT，最多返回 100 行
3. 只能查 demo_orders 表
4. 日期用 DATE 类型直接比较
```

**记住：这些是"软约束"，模型 99% 遵守，但 1% 的失守就是删库。** 硬约束在下一节。

### 2.3 安全校验 —— 代码层硬约束（今天的灵魂）

工具方法拿到模型生成的 SQL 后，**执行前必须校验**：

```java
public class SqlSafetyValidator {

    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|MERGE|EXECUTE|CALL|VACUUM|COPY)\\b");
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile(";|--|/\\*|\\*/");

    public static void validate(String sql) {
        String s = sql.strip();
        // ① 白名单：必须以 SELECT 开头
        if (!s.toUpperCase().startsWith("SELECT")) {
            throw new IllegalArgumentException("只允许 SELECT 查询");
        }
        // ② 黑名单：禁止写操作关键字
        if (FORBIDDEN_KEYWORDS.matcher(s).find()) {
            throw new IllegalArgumentException("SQL 包含禁止的关键字");
        }
        // ③ 禁止分号/注释：防多语句注入（"SELECT 1; DROP TABLE x"）
        if (FORBIDDEN_CHARS.matcher(s).find()) {
            throw new IllegalArgumentException("SQL 包含非法字符（分号/注释）");
        }
    }
}
```

三个校验缺一不可：
- **白名单开头**挡住一切非查询语句
- **黑名单关键字**挡住 `SELECT ... INTO` 之类的花活（用 `\b` 词边界，防 `UPDATED_AT` 这种列名误伤）
- **禁分号/注释**挡住多语句注入 —— 这是 SQL 注入的第一招

### 2.4 数据库层防线 —— 只读账号（生产必须）

校验是第一道，**只读数据库账号是最后防线**：

```
生产架构：应用连数据库用只读账号（只 GRANT SELECT）
        → 就算 AI 生成了 DELETE 且校验漏了，数据库也会拒绝
```

Demo 里我们用同一个 postgres 账号（本地开发），但**这个概念必须建立**：三层防线 = prompt 约束 → 代码校验 → DB 权限。生产环境靠第三层兜底。Day55 设计规范和 Day82 权限管控会再强化。

### 2.5 结果格式化 —— 让模型"看得懂"表格

`JdbcTemplate.queryForList(sql)` 返回 `List<Map<String, Object>>`，直接丢给模型太乱。**格式化成 Markdown 表格**回传：

```
| order_id | product | amount | status |
|----------|---------|--------|--------|
| ORD-001  | 蓝牙耳机 | 299.00 | 已签收 |
```

模型对表格的"阅读理解"比 JSON 数组更好，生成自然语言总结的质量更高。同时**审计日志**：每条模型生成的 SQL 记日志（谁问的、生成了什么 SQL、返回几行）—— 这是 Day82 审计要求的雏形。

---

## 3. 实操作业（80 分钟）

> 目标：建一张 `demo_orders` 业务表（带种子数据），实现"自然语言查数据"，返回表格结果。

### 步骤 1：演示表 + 种子数据（新建 `config/DemoDataConfig.java`）

```java
package com.example.springai.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DemoDataConfig {

    /** NL2SQL 演示表：建表 + 种子数据（幂等：有数据不重灌） */
    @Bean
    public CommandLineRunner initDemoOrders(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS demo_orders (
                    order_id   VARCHAR(20) PRIMARY KEY,
                    product    VARCHAR(50),
                    amount     NUMERIC(10,2),
                    quantity   INT,
                    status     VARCHAR(20),
                    order_date DATE
                )
                """);
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM demo_orders", Integer.class);
            if (count != null && count > 0) {
                return;   // 已有数据，跳过
            }
            jdbc.batchUpdate("""
                INSERT INTO demo_orders (order_id, product, amount, quantity, status, order_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """, java.util.List.of(
                    new Object[]{"ORD-001", "蓝牙耳机", 299.00, 2, "已签收", java.time.LocalDate.of(2026, 8, 10)},
                    new Object[]{"ORD-002", "智能手表", 1299.00, 1, "已签收", java.time.LocalDate.of(2026, 8, 12)},
                    new Object[]{"ORD-003", "蓝牙耳机", 299.00, 1, "已发货", java.time.LocalDate.of(2026, 8, 20)},
                    new Object[]{"ORD-004", "运动鞋",   459.00, 1, "待发货", java.time.LocalDate.of(2026, 8, 25)},
                    new Object[]{"ORD-005", "键盘",     399.00, 2, "已发货", java.time.LocalDate.of(2026, 9, 1)},
                    new Object[]{"ORD-006", "蓝牙耳机", 299.00, 3, "已取消", java.time.LocalDate.of(2026, 9, 2)},
                    new Object[]{"ORD-007", "鼠标",     129.00, 5, "已发货", java.time.LocalDate.of(2026, 9, 3)},
                    new Object[]{"ORD-008", "智能手表", 1299.00, 2, "待发货", java.time.LocalDate.of(2026, 9, 5)},
                    new Object[]{"ORD-009", "显示器",   1899.00, 1, "已签收", java.time.LocalDate.of(2026, 9, 6)},
                    new Object[]{"ORD-010", "键盘",     399.00, 1, "已签收", java.time.LocalDate.of(2026, 9, 7)}
            ));
            System.out.println(">>> demo_orders 种子数据已灌入 10 条");
        };
    }
}
```

### 步骤 2：安全校验器（新建 `tool/SqlSafetyValidator.java`）

代码见 §2.3，放到 `tool` 包下。

### 步骤 3：SQL 查询工具（新建 `tool/SqlQueryTool.java`）

```java
package com.example.springai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
@RequiredArgsConstructor
public class SqlQueryTool {

    private final JdbcTemplate jdbc;

    @Tool(description = "在业务数据库上执行只读 SELECT 查询，返回 Markdown 表格结果。仅用于查询订单业务数据")
    public String queryDatabase(
            @ToolParam(description = "要执行的只读 SELECT SQL 语句，必须带 LIMIT，最多 100 行") String sql) {

        log.info(">>> 模型生成的 SQL: {}", sql);   // 审计：记录每条生成的 SQL

        // ① 安全校验（硬约束）
        SqlSafetyValidator.validate(sql);

        // ② 执行
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        if (rows.isEmpty()) {
            return "查询结果为空（0 行）";
        }

        // ③ 格式化成 Markdown 表格
        StringJoiner sj = new StringJoiner("\n");
        var columns = rows.get(0).keySet().stream().toList();
        sj.add("| " + String.join(" | ", columns) + " |");
        sj.add("|" + columns.stream().map(c -> "---").collect(java.util.stream.Collectors.joining("|")) + "|");
        for (Map<String, Object> row : rows) {
            sj.add("| " + columns.stream()
                    .map(c -> String.valueOf(row.get(c)))
                    .collect(java.util.stream.Collectors.joining(" | ")) + " |");
        }
        return "查询结果（" + rows.size() + " 行）：\n" + sj;
    }
}
```

### 步骤 4：控制器（新建 `controller/Nl2SqlController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.SqlQueryTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/nl2sql")
public class Nl2SqlController {

    private final ChatClient chatClient;

    /** 表结构注入 + 语法规则（system prompt）—— NL2SQL 准确率的第一要素 */
    private static final String SCHEMA_PROMPT = """
            你是数据分析助手，根据用户问题生成 SQL 并调用 queryDatabase 工具查询。

            表结构：
            CREATE TABLE demo_orders (
                order_id   VARCHAR(20),   -- 订单号
                product    VARCHAR(50),   -- 商品名称
                amount     NUMERIC(10,2), -- 金额（元）
                quantity   INT,           -- 数量
                status     VARCHAR(20),   -- 状态：待发货/已发货/已签收/已取消
                order_date DATE           -- 下单日期
            );

            规则：
            1. 只能生成 SELECT 语句，禁止任何增删改
            2. 必须带 LIMIT，最多返回 100 行
            3. 状态值只能用：待发货、已发货、已签收、已取消（中文精确匹配）
            4. 拿到查询结果后，用简洁的自然语言总结回答用户，附上关键数字
            """;

    public Nl2SqlController(ChatModel chatModel, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SCHEMA_PROMPT)
                .defaultTools(new SqlQueryTool(jdbc))
                .defaultOptions(ChatOptions.builder().temperature(0.1).build())   // SQL 生成要低温
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
```

### 步骤 5：验证（5 个场景）

```bash
# ① 简单聚合
curl "http://localhost:8080/api/nl2sql/ask?question=总共有多少个订单"

# ② 条件查询
curl "http://localhost:8080/api/nl2sql/ask?question=有多少订单还在待发货状态"

# ③ 分组聚合（GROUP BY）
curl "http://localhost:8080/api/nl2sql/ask?question=每个商品的销售额是多少，按金额从高到低排"

# ④ 时间条件
curl "http://localhost:8080/api/nl2sql/ask?question=9月份的总销售额"

# ⑤ 安全测试（关键！）：诱导写库，应被拒绝
curl "http://localhost:8080/api/nl2sql/ask?question=把所有订单状态改成已签收"
curl "http://localhost:8080/api/nl2sql/ask?question=删掉demo_orders表"
```

---

## 4. 自检标准（不通过不许进 Day53）

- [ ] ①②③④：用户日常语言提问，返回的数字**和数据库实际数据一致**（自己 `SELECT COUNT(*)` 对一下）；
- [ ] 日志里能看到模型生成的 SQL，且都是合法 SELECT；
- [ ] ⑤ 安全测试：诱导写库时，**数据没被改动**（`demo_orders` 还是 10 条、状态没变），模型回复"只能查询"之类的拒绝信息 —— **这条是今天的过关底线**；
- [ ] 能口头讲清三层防线：prompt 约束（软）→ 代码校验（硬）→ 只读账号（兜底）；
- [ ] 能讲清表结构注入三技巧：列中文注释、枚举值写全、只注入相关表。

---

## 5. 关键踩坑清单（必背）

1. **只靠 prompt 拦写操作** → 模型会被"越狱"诱导出 DELETE。**必须代码层校验**，prompt 只是第一道软约束。
2. **黑名单误伤列名** → `UPDATE_TIME` 这种列名会被 `\bUPDATE\b` 误伤吗？不会（词边界），但 `SELECT updated_at FROM ...` 里 `updated_at` 是一个词也不会误伤。校验器改动要跑安全测试。
3. **允许分号** → 多语句注入（`SELECT 1; DROP TABLE x`）。分号必须禁。
4. **枚举值没写全** → 模型会猜 `status='shipped'`，查出来永远 0 行。列注释里写全中文枚举值。
5. **temperature 太高** → SQL 生成要 0.1 以下，高了同一问题生成不同 SQL，结果不稳定。
6. **不限行数** → 大表无 LIMIT 一查几十万行，工具返回值直接撑爆模型上下文。强制 LIMIT。
7. **生产用高权限账号连库** → 本地 demo 用 postgres 无所谓，生产必须只读账号（三层防线的最后一层）。

---

## 6. 今日小结 + 明日预告

**今天你完成了**：Function Calling 最经典的落地场景 —— 表结构注入让模型"懂"你的库、工具包装让安全校验成为硬约束、三层防线（prompt/代码/DB）守住只读底线。这个"自然语言查数据"能力，直接对接你公司的业务库就是生产力的开始。

**明日（Day53）**：对接现有业务 API 接口 —— 让 AI 通过自然语言调用你公司**已有的** HTTP 接口（接口鉴权、参数映射、结果格式化、超时与返回过大的处理）。NL2SQL 是查库，明天是调 API，两者合起来覆盖了企业里绝大部分数据入口。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
