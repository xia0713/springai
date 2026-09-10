# Day 53：Function Calling 实战 —— 对接现有业务 API 接口

> 所属阶段：第二阶段 · 第 8 周「Function Calling 与业务系统对接」（Day50-56）
> 今日主题：让 AI 像一个"会说话的调用方"一样去调你公司的 REST 接口 —— 鉴权、参数映射、结果格式化、超时与大结果处理
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day50-51 工具调用、Day52 NL2SQL 已跑通

---

## 0. 今天要解决的问题

昨天 NL2SQL 直连了数据库。但真实企业里，**数据通常不让你直接查库** —— 都躲在微服务 API 后面：

```
用户系统   →  GET /api/erp/employees?department=研发
订单系统   →  GET /api/order/detail?orderId=xxx（需要 X-API-KEY 请求头）
库存系统   →  POST /api/stock/query（返回上千条 JSON）
```

这些接口有**鉴权**（API Key/Token）、有**参数格式要求**（编码、枚举、分页）、有**巨大的返回体**（一个列表接口几百 KB）、还有**慢接口**（下游抖动，8 秒不响应）。

**今天的核心思想一句话：工具（@Tool 方法）是"模型世界"和"HTTP 世界"之间的适配层 —— 模型只懂业务语义，HTTP 细节（鉴权、编码、分页、超时）全部由工具代码消化。**

### NL2SQL vs API 对接，什么时候用哪个？

| | NL2SQL（Day52） | API 对接（今天） |
|---|---|---|
| 适用 | 简单查询、数据在库里、允许直连 | 数据在微服务后、有业务逻辑、**生产主流** |
| 安全风险 | SQL 注入（靠校验器挡） | 越权调用（靠鉴权 + 参数白名单挡） |
| 灵活性 | 高（任意聚合） | 低（只能调已有接口） |
| 企业现实 | 内部分析场景多 | **绝大部分业务场景**（服务边界、权限、审计都好控） |

---

## 1. 架构：一次 API 调用的完整链路

```
用户：「研发部有哪些人？」
   ↓
模型理解语义 → 发起工具调用 queryEmployee(department="研发")
   ↓
ErpEmployeeTool.queryEmployee(...)   ← 适配层，今天的全部工作都在这
   ├─ ① 参数映射：部门"研发" → API 要的 deptCode（模型不懂编码）
   ├─ ② 接口鉴权：加 X-API-KEY 请求头（密钥来自配置，模型永远看不见）
   ├─ ③ 超时控制：connect 3s / read 5s（慢接口不拖死模型调用）
   ├─ ④ 结果格式化：API 返回 800KB JSON → 只挑 3 个字段、最多 5 条
   └─ ⑤ 返回简洁文本给模型
   ↓
模型拿到摘要 → 生成自然语言回答
```

**关键认知（必考）：鉴权密钥、接口地址、参数编码这些"接口细节"绝不能放进模型上下文。** 模型只负责"表达业务意图"（查谁、查什么部门），剩下的全是代码的事。这既省 token，又是安全边界 —— 模型上下文可能被注入（Day27 学过），密钥进了上下文就有泄露风险。

---

## 2. 核心知识点（40 分钟）

### 2.1 接口鉴权 —— 密钥只活在代码和配置里

```java
@Value("${app.erp.api-key}")
private String apiKey;

// 工具方法内部：
HttpRequest 请求带 header("X-API-KEY", apiKey)
```

三条纪律：
1. **密钥放配置**（application.yaml / 环境变量），不硬编码（你 `application.yaml` 里 LLM key 已经是这么放的）
2. **密钥不进 prompt** —— system prompt 里绝不出现 key
3. **工具参数里没有 key** —— 模型的 `@ToolParam` 只有业务参数（姓名、部门），key 是工具自己加的

### 2.2 参数映射 —— 模型说"人话"，接口要"编码"

模型生成参数用的是**业务语义**（用户怎么问它就怎么传），但接口要的是**系统编码**：

```
模型传："研发部" / "研发"
API 要：deptCode=RD

模型传："已签收"
API 要：status=RECEIVED
```

映射在工具方法里做，用**枚举/Map 白名单**（别让模型直接拼编码 —— 它会编造）：

```java
private static final Map<String, String> DEPT_CODES = Map.of(
        "研发", "RD", "销售", "SALES", "财务", "FIN");
// 查不到 → 返回"支持的部门有：研发/销售/财务"，模型能理解并转告用户
```

这就是 Day51 参数校验的延续：**可预期错误返回提示，模型能转述；别抛它看不懂的异常**。

### 2.3 结果格式化 —— 大结果是上下文杀手

一个真实的列表接口动辄返回几百 KB JSON。直接 `return response` 给模型的后果：

- 上下文瞬间被撑爆（token 是钱！Day13 你算过成本）
- 模型在垃圾信息里"迷路"，答案质量下降

**格式化三板斧：**
1. **挑字段**：API 返回 15 个字段，模型只需要 3 个（姓名/部门/职位）→ 只取这 3 个
2. **限条数**：返回 200 条 → 最多给 5 条 + "共 200 人"的汇总
3. **转表格**：Markdown 表格（Day52 已验证模型对表格理解最好）

### 2.4 超时处理 —— 慢接口会拖死整个调用

RestClient 默认不设超时（或超长），下游一抖，模型的工具调用就挂在那里，用户请求跟着超时。**必须显式配置**：

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofSeconds(3));   // 建连超时
factory.setReadTimeout(Duration.ofSeconds(5));      // 读超时（下游处理时间）
RestClient erpClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .build();
```

超时抛出的异常走 **Day51 的 `ToolExecutionExceptionProcessor` 降级通道** —— 模型收到"接口超时"信息，回复用户"查询超时请稍后再试"，而不是 500。

### 2.5 常见坑速查（今天作业全部会踩到一遍）

| 坑 | 症状 | 解法 |
|---|---|---|
| 没配超时 | 接口卡 30s，用户请求全超时 | connect 3s / read 5s |
| 结果不做格式化 | token 爆炸、答案变差 | 挑字段 + 限条数 + 转表格 |
| 模型编造参数编码 | 传 `deptCode=yanfa`，接口 404 | 编码映射白名单 |
| 密钥写进 prompt | 泄露风险 | 密钥只在代码/配置 |
| 接口报错直接抛 | 用户看到 500 | 走降级，返回可读错误 |

---

## 3. 实操作业（80 分钟）

> 目标：在项目里造一个"模拟公司 ERP 接口"（带鉴权 + 分页 + 延迟开关 + 大结果），再写工具对接它，让用户自然语言查员工。

### 步骤 1：模拟 ERP 接口（新建 `controller/MockErpController.java`）

```java
package com.example.springai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 模拟公司 ERP 员工接口（Day53 被对接的"现有业务 API"）。
 * 特意包含真实企业的三个特征：
 * ① 鉴权：X-API-KEY 请求头校验
 * ② 分页：返回体含 total/page/records
 * ③ 延迟开关：?delayMs=8000 模拟慢接口（测超时用）
 */
@RestController
@RequestMapping("/api/mock-erp")
public class MockErpController {

    private static final String VALID_KEY = "demo-key-123";

    /** 模拟 23 个员工的大结果 */
    private static List<Map<String, Object>> employees() {
        String[] depts = {"研发部", "销售部", "财务部"};
        return IntStream.rangeClosed(1, 23)
                .mapToObj(i -> Map.<String, Object>of(
                        "id", i,
                        "empNo", "E%03d".formatted(i),
                        "name", "员工" + i,
                        "department", depts[i % 3],
                        "title", i % 2 == 0 ? "高级工程师" : "工程师",
                        "phone", "138%08d".formatted(i),
                        "email", "emp" + i + "@company.com",
                        "address", "某某市某某区某某路" + i + "号"   // 无关大字段，模拟臃肿返回
                ))
                .toList();
    }

    @GetMapping("/employees")
    public Map<String, Object> list(
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "0") long delayMs) throws InterruptedException {

        // ① 鉴权：key 不对直接 401 语义
        if (!VALID_KEY.equals(apiKey)) {
            return Map.of("code", 401, "message", "API Key 无效");
        }
        // ② 延迟开关：模拟慢接口
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        // ③ 过滤 + 分页
        List<Map<String, Object>> filtered = employees().stream()
                .filter(e -> department == null || department.isBlank()
                        || department.equals(e.get("department")))
                .toList();
        List<Map<String, Object>> records = filtered.stream()
                .skip((long) (page - 1) * size)
                .limit(size)
                .toList();
        return Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "total", filtered.size(),
                        "page", page,
                        "records", records
                )
        );
    }
}
```

### 步骤 2：配置（application.yaml 追加）

```yaml
app:
  erp:
    base-url: http://localhost:8080
    api-key: demo-key-123
```

### 步骤 3：对接工具（新建 `tool/ErpEmployeeTool.java`）

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ERP 员工查询工具（Day53）—— 模型世界与 HTTP 世界的适配层。
 * <p>
 * 五件事：参数映射 / 接口鉴权 / 超时控制 / 结果格式化 / 错误可读化。
 * 鉴权密钥来自配置，绝不出现在模型上下文。
 */
public class ErpEmployeeTool {

    private final RestClient erpClient;
    private final String apiKey;

    /** 参数映射白名单：模型说"研发"，接口要 deptCode */
    private static final Map<String, String> DEPT_CODES = Map.of(
            "研发", "RD", "销售", "SALES", "财务", "FIN");
    private static final Map<String, String> DEPT_NAMES = Map.of(
            "RD", "研发部", "SALES", "销售部", "FIN", "财务部");

    public ErpEmployeeTool(@Value("${app.erp.base-url}") String baseUrl,
                           @Value("${app.erp.api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));   // 坑1：不配超时，慢接口拖死调用
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.erpClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Tool(description = "查询公司员工信息（姓名/部门/职位）。支持按部门查询，部门只支持：研发、销售、财务")
    public String queryEmployee(
            @ToolParam(description = "部门名称，只支持：研发、销售、财务；不传查全部") String department) {

        // ① 参数映射：模型给业务词，接口要编码（白名单，防模型编造编码）
        String deptCode = DEPT_CODES.get(department);
        if (department != null && !department.isBlank() && deptCode == null) {
            return "不支持的部门：" + department + "，目前只支持：研发、销售、财务";
        }
        String deptName = deptCode == null ? null : DEPT_NAMES.get(deptCode);

        // ② 调用接口：鉴权头在代码里加（密钥不进模型上下文）
        Map<String, Object> resp;
        try {
            resp = erpClient.get()
                    .uri(uri -> {
                        var b = uri.path("/api/mock-erp/employees");
                        if (deptName != null) b.queryParam("department", deptName);
                        return b.queryParam("page", 1).queryParam("size", 100).build();
                    })
                    .header("X-API-KEY", apiKey)                 // 鉴权
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // 坑2：接口报错/超时 → 可读错误回传（走 Day51 降级，模型能转述）
            return "员工查询接口调用失败：" + e.getClass().getSimpleName() + "，请稍后再试";
        }

        // 接口层错误码
        if (resp == null || !Integer.valueOf(0).equals(resp.get("code"))) {
            return "员工查询接口返回异常：" + (resp == null ? "空响应" : resp.get("message"));
        }

        // ③ 结果格式化：大结果是上下文杀手 —— 挑字段 + 限条数 + 转表格
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        int total = ((Number) data.get("total")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");

        int limit = Math.min(5, records.size());   // 坑3：不限条数，token 爆炸
        String table = records.stream()
                .limit(limit)
                .map(r -> "| %s | %s | %s |".formatted(
                        r.get("name"), r.get("department"), r.get("title")))
                .collect(Collectors.joining("\n"));

        return "查询到 %d 名员工，显示前 %d 名：\n| 姓名 | 部门 | 职位 |\n|---|---|---|\n%s"
                .formatted(total, limit, table);
    }
}
```

### 步骤 4：控制器（新建 `controller/ErpAssistantController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.ErpEmployeeTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/erp-assistant")
public class ErpAssistantController {

    private final ChatClient chatClient;

    public ErpAssistantController(ChatModel chatModel, ErpEmployeeTool erpEmployeeTool) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是企业助手，可以查询公司员工信息。")
                .defaultTools(erpEmployeeTool)
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
```

> `ErpEmployeeTool` 带 `@Value` 构造参数，需要声明成 Bean。在 `ToolConfig` 里加：
> ```java
> @Bean
> public ErpEmployeeTool erpEmployeeTool(@Value("${app.erp.base-url}") String baseUrl,
>                                        @Value("${app.erp.api-key}") String apiKey) {
>     return new ErpEmployeeTool(baseUrl, apiKey);
> }
> ```

### 步骤 5：验证（5 个场景）

```bash
# ① 正常查询：自然语言 → 工具 → API → 表格
curl "http://localhost:8080/api/erp-assistant/ask?question=研发部有哪些人"

# ② 全量查询 + 大结果格式化（23 人只显示 5 人 + 汇总）
curl "http://localhost:8080/api/erp-assistant/ask?question=公司现在有多少员工"

# ③ 参数映射失败提示
curl "http://localhost:8080/api/erp-assistant/ask?question=市场部有谁"
# 期望：模型提示只支持 研发/销售/财务

# ④ 超时（关键）：临时把 mock 接口的 delayMs 打开验证 —— 直接 curl 模拟慢接口：
curl "http://localhost:8080/api/mock-erp/employees?delayMs=8000&X-API-KEY=demo-key-123" -m 2
# 或者在工具 uri 里加 queryParam("delayMs", 8000) 临时验证，超时后模型应友好提示"调用失败"

# ⑤ 鉴权验证（关键）：直接 curl 无 key 调 mock 接口，应被拒绝
curl "http://localhost:8080/api/mock-erp/employees"
# 期望：{"code":401,"message":"API Key 无效"}
```

---

## 4. 自检标准（不通过不许进 Day54）

- [ ] ①②：自然语言查员工，返回**表格 + 总数汇总**，且 23 人只显示 5 人（格式化生效）；
- [ ] ③：不支持的部门，模型**友好提示**支持哪些（参数映射白名单生效）；
- [ ] ④：慢接口场景，请求**不 500**，模型提示"调用失败请稍后再试"（超时 + 降级生效）；
- [ ] ⑤：mock 接口无 key 直接调用被拒（鉴权生效）；
- [ ] 日志/代码里确认：**模型上下文中没有出现 API Key**（鉴权只在工具代码里）；
- [ ] 能口头讲清：工具是"模型世界与 HTTP 世界的适配层"，五件事各自解决什么问题。

---

## 5. 关键踩坑清单（必背）

1. **密钥进模型上下文** → 注入攻击（Day27）可能让模型吐出 key。密钥只在配置和代码。
2. **不配超时** → 下游一抖，模型调用挂死，整个用户请求超时。connect 3s / read 5s 起步。
3. **结果原样返回** → 几百 KB JSON 撑爆上下文 + token 烧钱 + 模型迷路。挑字段、限条数、转表格。
4. **让模型生成参数编码** → 它会编造 `deptCode=yanfa`。编码映射用白名单，模型只给业务词。
5. **接口错误直接抛异常** → 用户看 500。catch 住转成可读文本回传（Day51 降级通道兜底）。
6. **分页参数不控制** → size 传 10000，接口返回巨大。工具侧固定合理分页参数。

---

## 6. 今日小结 + 明日预告

**今天你完成了**：Function Calling 对接"现有业务 API"的完整范式 —— 工具作为适配层，消化了鉴权（密钥不进上下文）、参数映射（白名单防编造）、结果格式化（挑字段/限条数/转表格）、超时控制（3s/5s）和错误可读化。加上 Day52 的 NL2SQL，你已覆盖企业数据的两大入口：**库和接口**。

**明日（Day54）**：复杂流程 —— 多工具串行调用、任务分步执行、中间结果存储与上下文传递、失败回滚与重试。作业是「用户数据报表生成」流程：查数据 → 分析 → 生成文案 → 发送邮件，一句话触发全流程自动完成。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
