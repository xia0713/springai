# Day 73：AI 能力中台化 —— 统一网关 + SDK 封装（其他系统 5 行代码接入）

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：把 AI 能力从"散落在各 Controller"收口成"统一网关 + SDK"—— 统一接入、统一管控、统一鉴权
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day71 稳定性 + Day72 成本策略 —— 它们就是中台的"内核"，今天补"门面和客户端"

---

## 0. 今天要解决的问题

看你现在的代码：AI 能力散落在十几个 Controller 里（`/api/documents/**`、`/api/assistant/**`、`/api/cost/**`……）：

```
别的业务系统想用你的 AI 能力：
  ① 调哪个接口？（十几个入口，哪个是"标准答案"？）
  ② 怎么鉴权？（全是裸接口，谁都能打）
  ③ 挂了怎么办？（要自己处理 500）
  ④ 怎么接？（要引入 Spring AI 全家桶依赖、拷配置、学你的内部结构）
```

**中台化一句话：把能力收口到一个门面（网关），把复杂性锁在门面后面，外面只留一个 5 行代码的 SDK。**

```
业务系统 A / B / C
    ↓ 引入 AiSdkClient（薄客户端，无 Spring AI 依赖）
    ↓ HTTP + X-API-KEY
┌─ AiGatewayController（统一门面）─────────────┐
│  统一鉴权：API Key 校验（Day53 的能力）         │
│  统一管控：缓存+路由（Day72）/熔断降级（Day71）   │ ← 内核已就绪
│  统一审计：每次调用记日志（谁/何时/什么/耗时）     │
└──────────────────────────────────────────┘
```

---

## 1. 核心知识点（40 分钟）

### 1.1 中台化的三个统一

| 统一 | 含义 | 今天落在哪 |
|---|---|---|
| **统一接入** | 所有业务系统走同一个门面入口，不直连内部实现 | `/api/ai-gateway/**` 唯一入口 |
| **统一管控** | 稳定性（Day71）、成本（Day72）、审计在门面后统一生效，改一处全体生效 | 内核 = CostOptimizedChatService |
| **统一鉴权** | 业务系统凭 API Key 接入，按 Key 审计和限流 | `X-API-KEY` 校验（Day53 已练过） |

**判断标准：门面后面换实现（换模型/加缓存/改路由），接入方零改动 —— 这才是中台，否则只是又一个 Controller。**

### 1.2 与微服务体系的两种融合形态

| 形态 | 做法 | 适用 |
|---|---|---|
| **网关代理**（今天用） | 业务系统 → HTTP → 你的 AI 网关 → 内部能力 | ✅ 管控/审计/计费收口在网关，多语言友好 |
| SDK 直连 | 业务系统引 SDK → 直连模型厂商 | 少数超大流量场景（绕开你的网关带宽），但管控就散了 |

你公司是 Java 微服务体系（Feign/OpenFeign 那套），网关代理形态无缝融入：**AI 网关就是你微服务集群里的又一个服务**，注册中心/网关/鉴权中间件全部复用 —— 这就是"复用 Java 微服务经验"的具体含义。

### 1.3 SDK 设计四原则（薄客户端）

1. **零重依赖**：JDK 自带 `java.net.http.HttpClient` + Jackson（几乎所有 Java 项目都有）—— 不强迫业务系统引入 Spring AI 全家桶
2. **超时必配，且要懂场景**：Day53 的 3s/5s 是给普通接口的；**LLM 调用要放宽到 30s 级**（生成本来就慢）
3. **错误可读**：401 → "鉴权失败，检查 API Key"；超时 → "AI 服务超时"，绝不让业务方面对原始异常栈
4. **版本兼容**：响应加字段不删字段；接口路径带版本（`/api/ai-gateway/v1/**` 更严谨，Demo 用单版本 + 讲原则）

### 1.4 网关接口设计规范

```
鉴权：  X-API-KEY 请求头（密钥在配置中心/环境变量，不进代码）
审计：  每次调用记 [ai-gateway] 日志（调用方 Key / 问题 / 路由 / tokens / 耗时）
幂等：  GET 查询天然幂等；写操作走独立接口 + 白名单（Day55 红线）
响应：  结构稳定 —— {answer, route, tokens}，加字段可以，删字段要发版本
错误：  统一 {code, message} 语义（401 鉴权失败 / 403 未授权 / 429 限流 / 500 内部）
```

---

## 2. 实操作业（80 分钟）

> 目标：统一网关（鉴权+审计+复用 Day72 内核）+ 零依赖 SDK + 模拟业务系统接入，自检"5 行代码调用 AI"。

### 步骤 1：配置（application.yaml 追加）

```yaml
app:
  # AI 网关（Day73）：业务系统接入凭证
  ai-gateway:
    api-key: demo-ai-key-456
```

### 步骤 2：统一网关（新建 `controller/AiGatewayController.java`）

```java
package com.example.springai.controller;

import com.example.springai.service.CostOptimizedChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 能力统一网关（Day73）：统一接入 + 统一鉴权 + 统一审计。
 * 管控内核（Day71 熔断 / Day72 缓存+路由）在门面后面统一生效。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-gateway")
@RequiredArgsConstructor
public class AiGatewayController {

    private final CostOptimizedChatService chatService;

    @Value("${app.ai-gateway.api-key}")
    private String validKey;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestParam String question) {

        long start = System.currentTimeMillis();

        // ① 统一鉴权（Day53 能力复用）
        if (apiKey == null || !apiKey.equals(validKey)) {
            log.warn("[ai-gateway] 鉴权失败，key={}", apiKey == null ? "null" : "***");
            return Map.of("code", 401, "message", "API Key 无效");
        }

        // ② 统一管控：内核 = Day72 的缓存+路由（Day71 熔断在其调用的模型层同样生效）
        Map<String, Object> result = chatService.chat(question);

        // ③ 统一审计（Day82 审计体系的雏形）：谁 / 什么 / 走哪条路 / 花了多少
        log.info("[ai-gateway] key=***{}, q={}, route={}, tokens={}, costMs={}",
                validKey.substring(validKey.length() - 4), question,
                result.get("route"), result.get("tokens"),
                System.currentTimeMillis() - start);

        return result;
    }
}
```

### 步骤 3：SDK（新建 `sdk/AiSdkClient.java` —— 业务系统唯一要引入的东西）

```java
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
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 智能问答：内部自动享受 缓存/模型路由/熔断降级（业务系统无感知）。
     * 5 行接入的全部核心就是这一行调用。
     */
    public String chat(String question) {
        try {
            String url = baseUrl + "/api/ai-gateway/chat?question="
                    + URLEncoder.encode(question, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-API-KEY", apiKey)
                    .timeout(Duration.ofSeconds(30))   // LLM 场景放宽
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
```

### 步骤 4：模拟业务系统接入（新建 `controller/BusinessSimController.java`）

```java
package com.example.springai.controller;

import com.example.springai.sdk.AiSdkClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模拟"另一个业务系统"（Day73）：通过 SDK 走 HTTP 调用 AI 网关。
 * 证明接入方只需要 SDK + 3 行核心代码，不需要懂 Spring AI。
 */
@RestController
@RequestMapping("/api/biz-sim")
public class BusinessSimController {

    private final AiSdkClient ai;

    public BusinessSimController(@Value("${app.ai-gateway.api-key}") String apiKey) {
        // ===== 业务系统的全部接入代码（就这 1 行构造 + 后面随用随调）=====
        this.ai = new AiSdkClient("http://localhost:8080", apiKey);
    }

    /** 业务系统的某个普通功能，内部顺手用了 AI 能力 */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(defaultValue = "分析一下退货政策的核心要点") String question) {
        long start = System.currentTimeMillis();
        String answer = ai.chat(question);          // ← 业务代码里唯一的 AI 调用
        return Map.of(
                "answer", answer,
                "costMs", System.currentTimeMillis() - start
        );
    }
}
```

### 步骤 5：验证（5 行接入证明 + 鉴权 + 缓存穿透网关）

```bash
# ① 无 Key → 401 语义
curl "http://localhost:8080/api/ai-gateway/chat?question=你好"

# ② 带 Key → 走网关（内部是 Day72 的缓存+路由）
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" --data-urlencode "question=分析一下退货政策的核心要点"

# ③ 同一问题第二次 → 网关层缓存命中（[缓存·0token]）
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" --data-urlencode "question=分析一下退货政策的核心要点"

# ④ 关键验证：模拟业务系统经 SDK 调用 —— 证明"5 行代码接入"
curl "http://localhost:8080/api/biz-sim/report"

# ⑤ 审计日志：看服务日志里的 [ai-gateway] 行（key 后4位/route/tokens/costMs）
```

---

## 3. 自检标准（不通过不许进 Day74）

- [ ] ①②：无 Key 被拒（401 语义），带 Key 正常应答 —— 统一鉴权生效；
- [ ] ③：同一问题经网关二次调用命中缓存（`[缓存·0token]`）—— **管控内核对网关调用方同样生效**；
- [ ] ④：`/api/biz-sim/report` 走 SDK → HTTP → 网关全链路通 —— **接入方不依赖 Spring AI**；
- [ ] 看自己写的 `BusinessSimController`：接入代码只有构造 SDK + `ai.chat()` —— **5 行标准达成**；
- [ ] 日志里有 `[ai-gateway]` 审计行；
- [ ] 能口头讲清：三个统一各自解决什么；SDK 四原则；网关代理 vs SDK 直连的取舍。

---

## 4. 关键踩坑清单（必背）

1. **把内部 Controller 直接当"API"给外部用** → 内部接口没有鉴权/审计/稳定性承诺，改一次断一次。对外能力必须过网关门面。
2. **SDK 太厚** → 引入了 Spring AI 依赖的 SDK 是负担不是便利。薄客户端 + HTTP，业务系统零学习成本。
3. **SDK 超时照搬普通接口** → Day53 的 3s/5s 会让 LLM 调用大面积超时。按场景配：网关交互 30s。
4. **鉴权 Key 硬编码** → yaml/配置中心，且日志里只打后 4 位（今天日志示范了）。
5. **网关响应结构随意改** → 加字段兼容，删字段=断所有接入方，要走版本。
6. **"中台"只有入口没有内核** → 门面后面没有统一管控（缓存/路由/熔断/审计）就只是包了层壳的路由器 —— 你今天复用的 Day71/72 内核才是"中台"成色。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：AI 能力中台化的第一块完整拼图 —— 统一网关（鉴权+审计）+ 零依赖 SDK + 5 行接入验证。你的项目从"一堆 Demo 接口"变成了"有标准接入方式的 AI 能力提供方"。**三天的治理成果（Day71 稳定性 + Day72 成本 + 今天门面）= 你 Day73 作业里那个"5 行代码"背后的全部含金量。**

**明日（Day74）**：并发性能优化 —— 异步处理、批量向量化、向量检索性能（你的 3072 维无索引问题今天有解）、流式输出内存控制。作业：**压测知识库到 50 并发稳定运行（响应 ≤2s，成功率 ≥99%）** —— 第三阶段第一次用数字说话。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
