# Day 71：大模型 API 稳定性治理 —— 重试、熔断、降级、多模型路由

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：给 LLM 调用装上微服务级的稳定性 —— 内建重试认知、熔断三态机、多模型自动故障切换、限流
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21 + Resilience4j
> 前置：Day13 的 `MultiModelConfig`（已验证：qwenChatClient + deepseekChatClient 双模型底座已存在）

---

## 0. 今天要解决的问题

你现在的 LLM 调用是"裸奔"的：

```
主模型挂了（网关抖动 / 限流 429 / 维护）→ 用户看到 500
所有流量继续往挂掉的模型上打 → 雪崩
高峰期请求无节制 → 打爆 key 配额，全员失败
```

这正是你 Java 微服务经验的主场 —— **熔断/降级/限流那套思想原样适用，只是对象换成了 LLM API**。

**你已有的底座（盘点，不重造）：**
- `MultiModelConfig`（Day13）：两个 ChatClient Bean —— `qwenChatClient`（qwen3.8-27b，主）+ `deepseekChatClient`（deepseek-v4-flash，备），同一 OpenAI 兼容网关。今天是把它从"手动双模型"升级成"**自动故障切换**"
- **Spring AI 内建重试**：你的 MultiModelConfig 里已经传了 `RetryTemplate.defaultInstance()`；而且你 Day59 的报错栈里就见过 `SpringAiRetryAutoConfiguration` 和 `NonTransientAiException` —— 框架对瞬时错误自动重试，非瞬时错误直接抛

---

## 1. 核心知识点（40 分钟）

### 1.1 先搞清楚：Spring AI 已经替你做了什么（内建重试）

Spring AI 1.0.3 的 OpenAI 模块内建 `RetryTemplate`，行为：

| 错误类型 | 框架行为 |
|---|---|
| **瞬时错误**（429 限流 / 5xx / 超时） | 自动重试（默认若干次 + 退避） |
| **非瞬时错误**（400 参数错 / 401 鉴权失败 → `NonTransientAiException`） | **不重试，直接抛**（重试也没用） |

你 Day59 的报错栈（`SpringAiRetryAutoConfiguration$2.handleError` → `NonTransientAiException: HTTP 400`）就是这套机制的实证。

**结论 1：瞬时重试已经交给框架了，你的代码不要重复做。**

### 1.2 今天最大的坑：重试叠加（必考）

```
Spring AI 内建重试 3 次 × 你外层再加 Resilience4j @Retry 3 次
   = 一次用户请求最多 9 次真实 LLM 调用
        ↓ 故障时
重试风暴：所有请求都在重试，把还没死透的下游彻底打死
```

**结论 2（必背）：外层绝不加 `@Retry`。** 分层职责：

| 层 | 手段 | 谁负责 |
|---|---|---|
| L1 瞬时重试 | Spring AI 内建 RetryTemplate | 框架（已有） |
| L2 熔断 | Resilience4j CircuitBreaker | 你（今天加） |
| L3 多模型路由 | 主挂自动切备 | 你（今天加） |
| L4 降级 | 缓存 → 兜底话术 | 你（今天加） |
| L5 限流 | RateLimiter 控制进入速率 | 你（今天加） |

### 1.3 熔断三态机

```
CLOSED（正常）──失败率 ≥ 阈值（滑动窗口统计）──→ OPEN（熔断：请求毫秒级快速失败）
    ↑                                               │
    │ 探测请求成功率达标                              │ 等待 wait-duration（如 10s）
    └──────────── HALF_OPEN（放行少量探测请求）←──────┘
```

**对 LLM 场景的特殊价值：OPEN 状态下请求"快速失败"（毫秒级）直接走降级链，而不是挂着等 30 秒超时** —— 用户体感天差地别。

关键参数：
- `slidingWindowSize`：统计窗口（最近 N 次调用）
- `minimumNumberOfCalls`：至少几次才开始判定（太小会被一次抖动误熔断）
- `failureRateThreshold`：失败率阈值（如 50%）
- `waitDurationInOpenState`：OPEN 持续多久进 HALF_OPEN
- `permittedNumberOfCallsInHalfOpenState`：半开放行几个探测请求

### 1.4 多模型自动故障切换（今天的主菜）

Day13 你是"手动双模型"（两个 Bean，代码里手动选）。今天升级成自动链：

```
用户请求 → @CircuitBreaker 主模型(qwen)
              ├─ 成功 → 返回
              └─ 异常（含熔断 OPEN 快速失败）→ fallbackMethod 自动切换
                     ↓
              备用模型(deepseek)
              ├─ 成功 → 返回（标注"[备用模型]"，让用户/调用方知道质量层级）
              └─ 也失败 → 查本地缓存 → 兜底话术（"服务繁忙，已记录您的问题"）
```

**Resilience4j 的 fallback 规则**：`fallbackMethod` 指向的方法必须**同类、同签名，末尾多一个 `Throwable` 参数**：

```java
@CircuitBreaker(name = "llm", fallbackMethod = "fallbackToBackup")
public String chat(String question, boolean chaos) { ... }

// 签名 = 原方法参数 + Throwable 结尾
public String fallbackToBackup(String question, boolean chaos, Throwable t) { ... }
```

**设计原则（Day62 的降级思想场景化）：降级不是报错，是每一级都尽量给用户有用的东西，并标注服务等级。**

### 1.5 限流与排队

为什么 LLM 网关必须限流：① key 配额有限（限流=自我保护）② 故障时防止重试/降级流量雪崩 ③ 交互场景快速拒绝优于排队挂死。

Resilience4j `RateLimiter` 三参数：`limitForPeriod`（周期内允许数）/ `limitRefreshPeriod`（周期）/ `timeoutDuration`（拿不到许可等多久）。

**取舍**：交互场景短超时快速失败 + 友好提示；批处理场景排队等待。你的助手是交互场景 → 短超时。

---

## 2. 实操作业（80 分钟）

> 目标：主模型故障自动切备用 + 熔断三态 + 限流，并用 chaos 开关验证全链路。

### 步骤 1：加依赖（pom.xml）

```xml
<!-- Resilience4j：熔断/限流（Boot 3.x 用 spring-boot3 后缀；版本解析失败就查中央仓库最新 2.x） -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

> 注解生效依赖 AOP —— 你 Day43 已加 `spring-boot-starter-aop`，不用重复加。

### 步骤 2：配置（application.yaml 顶层追加，别放进 app: 里）

```yaml
# 顶层配置（与 spring: 平级）
resilience4j:
  circuitbreaker:
    instances:
      llm:                                    # 熔断实例名，与注解 name 对应
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10               # 统计最近 10 次调用
        minimum-number-of-calls: 5            # 至少 5 次才判定（防单次抖动误熔断）
        failure-rate-threshold: 50            # 失败率 ≥50% → OPEN
        wait-duration-in-open-state: 10s      # OPEN 持续 10s → HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 3
  ratelimiter:
    instances:
      llm:
        limit-for-period: 20                  # 每周期最多 20 次
        limit-refresh-period: 1s              # 周期 1 秒
        timeout-duration: 500ms               # 拿不到许可快速失败（交互场景）
```

### 步骤 3：稳定性服务（新建 `service/ResilientChatService.java`）

```java
package com.example.springai.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 稳定性服务（Day71）：熔断 + 多模型自动故障切换 + 降级链 + 限流。
 * <p>
 * 降级链（每级标注服务等级）：主模型(qwen) → 备用模型(deepseek) → 本地缓存 → 兜底话术。
 * ⚠️ 外层不加 @Retry（Day71 最大坑：内建重试 × 外层重试 = 9 次调用风暴）。
 */
@Slf4j
@Service
public class ResilientChatService {

    private final ChatClient primary;   // qwenChatClient（Day13 已有）
    private final ChatClient backup;    // deepseekChatClient（Day13 已有）

    /** 降级用：近期问答缓存（演示级；Day72 升级成正式缓存策略） */
    private final Map<String, String> answerCache = new ConcurrentHashMap<>();

    public ResilientChatService(@Qualifier("qwenChatClient") ChatClient primary,
                                @Qualifier("deepseekChatClient") ChatClient backup) {
        this.primary = primary;
        this.backup = backup;
    }

    /**
     * 熔断保护的主路径。chaos=true 模拟主模型故障（演示/演练用）。
     * 失败时 fallbackMethod 自动接手，请求不 500。
     */
    @CircuitBreaker(name = "llm", fallbackMethod = "fallbackToBackup")
    @RateLimiter(name = "llm")
    public String chat(String question, boolean chaos) {
        if (chaos) {
            throw new IllegalStateException("模拟主模型故障");   // 计入熔断统计
        }
        String answer = primary.prompt().user(question).call().content();
        answerCache.put(question, answer);   // 成功答案进缓存，供降级用
        return answer;
    }

    /** 降级①：主模型失败/熔断 → 切备用模型（签名 = 原方法 + Throwable） */
    public String fallbackToBackup(String question, boolean chaos, Throwable t) {
        log.warn("[resilience] 主模型不可用({})，切换备用模型", t.getMessage());
        try {
            String answer = backup.prompt().user(question).call().content();
            return "[备用模型] " + answer;   // 标注服务等级
        } catch (Exception e) {
            // 降级②：备用也挂 → 查缓存 → 兜底话术（统一出口）
            String cached = answerCache.get(question);
            if (cached != null) {
                return "[缓存答案] " + cached;
            }
            log.error("[resilience] 备用模型也不可用({})，走兜底话术", e.getMessage());
            return "[服务降级] AI 服务繁忙，您的问题已记录，恢复后将优先处理，请稍后重试。";
        }
    }
}
```

### 步骤 4：演示控制器（新建 `controller/ResilienceDemoController.java`）

```java
package com.example.springai.controller;

import com.example.springai.service.ResilientChatService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 稳定性演示（Day71）：chaos 开关验证熔断三态机 + 降级链。
 */
@RestController
@RequestMapping("/api/resilience")
@RequiredArgsConstructor
public class ResilienceDemoController {

    private final ResilientChatService chatService;
    private final CircuitBreakerRegistry cbRegistry;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestParam String question,
            @RequestParam(defaultValue = "false") boolean chaos) {
        long start = System.currentTimeMillis();
        String answer = chatService.chat(question, chaos);
        return Map.of(
                "answer", answer,
                "costMs", System.currentTimeMillis() - start   // 熔断 OPEN 时应毫秒级
        );
    }

    /** 观察熔断器状态：CLOSED/OPEN/HALF_OPEN + 失败率（Resilience4j 标准公开 API） */
    @GetMapping("/cb-status")
    public Map<String, Object> cbStatus() {
        CircuitBreaker cb = cbRegistry.circuitBreaker("llm");
        CircuitBreaker.Metrics m = cb.getMetrics();
        return Map.of(
                "state", cb.getState().name(),
                "failureRate", String.format("%.1f%%", m.getFailureRate()),
                "bufferedCalls", m.getNumberOfBufferedCalls(),
                "failedCalls", m.getNumberOfFailedCalls()
        );
    }
}
```

### 步骤 5：验证（熔断三态机全程）

```bash
# ① 正常：主模型应答
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=你好"
# 期望：正常回答，costMs 为正常 LLM 耗时

# ② 单次故障：不 500，自动切备用
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=你好" --data-urlencode "chaos=true"
# 期望：answer 以 [备用模型] 开头 —— 降级链第一级生效

# ③ 连续故障 5 次 → 熔断器 OPEN
for i in 1 2 3 4 5; do curl -s -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=hi" --data-urlencode "chaos=true" > /dev/null; done
curl "http://localhost:8080/api/resilience/cb-status"
# 期望：state=OPEN, failureRate=100%

# ④ OPEN 状态下发正常请求（chaos=false）：毫秒级快速失败直接走备用（不等主模型超时）
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=今天天气"
# 期望：仍是 [备用模型]，costMs 非常小 —— 这就是熔断"快速失败"的价值

# ⑤ 等 10 秒（OPEN→HALF_OPEN）→ 连发 3 次正常请求 → 恢复 CLOSED
sleep 10
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=1加1等于几"
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=2加2等于几"
curl -G "http://localhost:8080/api/resilience/chat" --data-urlencode "question=3加3等于几"
curl "http://localhost:8080/api/resilience/cb-status"
# 期望：state 恢复 CLOSED —— 三态机闭环
```

---

## 3. 自检标准（不通过不许进 Day72）

- [ ] 场景②：单次故障自动切备用，请求**不 500**，answer 标注"[备用模型]"；
- [ ] 场景③：连续故障触发 OPEN，`/cb-status` 可见 state=OPEN；
- [ ] 场景④：OPEN 状态下正常请求**毫秒级快速失败**走备用（costMs 对比正常调用差一个数量级）；
- [ ] 场景⑤：HALF_OPEN 探测成功后恢复 CLOSED —— 三态机完整走一遍；
- [ ] 能口头讲清：为什么外层不加 Retry（重试叠加 = 9 次调用风暴）；降级链的优先级设计；限流的三个参数。

---

## 4. 关键踩坑清单（必背）

1. **重试叠加** → 内建 3 次 × 外层 3 次 = 9 次调用，故障时重试风暴打死下游。外层永不加 Retry。
2. **降级没有标注** → 用户拿着备用模型的答案不知道质量不同。每级降级都标注（[备用模型]/[缓存答案]/[服务降级]）。
3. **熔断阈值太敏感** → minimum-number-of-calls 太小（1-2 次）会被一次抖动误熔断。5-10 次起步。
4. **OPEN 等待时间太短** → LLM 网关故障通常持续分钟级，10s 就去探测可能反复撞墙。生产可调 30-60s。
5. **chaos 注入点放错** → 熔断统计的是**被注解方法**的调用，chaos 必须在方法体内抛，在调用方抛不计入统计。
6. **限流超时设太长** → 交互场景 timeout-duration=500ms 快速失败 + 友好提示，别让用户在限流上排队 30 秒。
7. **两个 ChatClient 的 Bean 名和注释对不上** → 你 MultiModelConfig 里注释写"豆包"但 Bean 叫 deepseekChatClient —— 顺手改掉，答辩时这种不一致很扎眼。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：把 Java 微服务的稳定性治理全套搬到 LLM 调用上 —— 五层体系（内建重试/熔断/多模型路由/降级链/限流）+ 熔断三态机全流程验证。这是你两个技术栈（Java 微服务 × AI 应用）的第一次正式合体，也是第三阶段的主旋律。

**明日（Day72）**：成本优化 —— Token 消耗控制、缓存策略、**模型分层路由**（简单问题用小模型）。你会发现：今天建的"多模型路由"基础设施，明天直接复用 —— 从"故障时切备用"扩展成"按问题难度路由，简单问题走便宜模型"。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
