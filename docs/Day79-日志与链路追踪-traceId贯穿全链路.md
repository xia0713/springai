# Day 79：日志与链路追踪 —— traceId 贯穿 AI 调用全链路

> 所属阶段：第三阶段 · 第 12 周「可观测性 + 安全合规」（Day78-84）
> 今日主题：监控告诉你"出了问题"，链路追踪告诉你"**哪一步**出的问题" —— traceId 贯穿 + 全链路审计日志
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Micrometer Tracing + Java 21
> 前置：Day78 监控（指标端点）已通；你有 7 类结构化日志点（[ai-gateway]/[cost]/[step]/[memory]/[ops]/[agent]/[prompt-gray]）

---

## 0. 先纠偏：学习计划里的 Sleuth 已经退役了（淘汰更替活案例）

学习计划原文写的是"Sleuth + Zipkin"—— **那是 Spring Boot 2 时代的栈**：

```
❌ 旧栈（Boot 2）：Spring Cloud Sleuth（网课和旧教程的主流）
✅ 新栈（Boot 3.4 = 你的版本）：Micrometer Tracing（io.micrometer.tracing）+ Zipkin bridge
   —— Sleuth 的官方继任者，API 全部换了包名，旧教程照抄必翻车
```

这正是你选这门课"基于最新"的原因之一：**照旧教程抄，依赖加不上、类名全对不上。**

---

## 1. 核心知识点（40 分钟）

### 1.1 核心概念：traceId / spanId（一张图）

```
一次用户请求 = 一个 trace（全程唯一 traceId）
    └── span: 网关入口          [spanId=a1]  ← 自动埋点
          └── span: 缓存查询     [spanId=b1]
          └── span: LLM 调用     [spanId=b2]
                └── span: 上游 HTTP [spanId=c1]
```

- **traceId**：整条链路的身份证 —— 全链路所有日志都带它，一个 ID 串起一次请求的每一步
- **spanId**：每个环节的编号 + 父子关系 —— 能看出"慢的是哪一个环节"
- **谁埋的**：Micrometer Tracing 对 Spring MVC / RestClient / JDBC 等**自动埋点**，你不用手写 span

### 1.2 日志关联 —— traceId 自动进日志（MDC 机制）

Tracing 把 traceId/spanId 放进 MDC（日志上下文），**日志格式配一下，每行日志自动带**：

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

效果（你的多路召回链路，同一 traceId 贯穿）：

```
 INFO [SpringAI,6f2a8b...,a1] [ai-gateway] key=***-456, q=..., route=BIG, tokens=812
 INFO [SpringAI,6f2a8b...,b1] [cost] 缓存未命中，走大模型
 INFO [SpringAI,6f2a8b...,b2] [step] querySalesStats(2026-08) → ...
```

**排查方式从此改变：拿一个 traceId → `grep 6f2a8b app.log` → 一次请求的全部环节按序还原。** 不再靠时间戳人肉对齐。

### 1.3 采样率 —— Demo 全采，生产抠门

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # Demo 全采样；生产 0.1（10%）—— 100% 采样的存储与开销是灾难
```

### 1.4 AI 全链路审计日志清单（Day82 审计的地基）

你已有的 7 类日志点盘点 + 缺什么：

| 日志点 | 已有内容 | 缺 |
|---|---|---|
| `[ai-gateway]`（Day73） | key 后4位/问题/route/tokens/costMs | 问题原文全量（审计要求）—— 注意脱敏（明天 Day80） |
| `[cost]`（Day72） | 缓存命中/节省 tokens | ✅ 基本齐 |
| `[step]`（Day54） | 工具调用步骤 | traceId 由框架自动补（今天之后） |
| `[memory]`（Day59） | remember/recall | 用户维度 ✅ |
| `[agent]`（Day58/62） | 每步 Action/熔断 | 介入单已含轨迹 ✅ |
| `[prompt-gray]`（Day76） | 比例变更 | **缺：每次调用用了哪个 promptVersion** —— 事故回溯靠它 |

**审计五要素（生产规范）：traceId / 用户 / promptVersion / 模型(route) / 结果摘要 —— 今天之后前四项自动/半自动齐了。**

### 1.5 排查方法论（Day46 的追踪版）

```
监控告警（成功率降 / P95 涨 —— Day78 大盘）
   ↓ 拿到慢/错请求的 traceId
grep traceId → 还原全链路 span
   ↓ 看哪一段的 span 耗时异常
├─ 召回/检索段慢 → 检索环节（Day46 决策树：数据/权限/改写/阈值/分块）
├─ LLM 调用段慢/错 → 生成环节（上下文/温度/模型）
└─ 每段都正常但整体差 → 采样看内容质量（幻觉/答非所问 → 生成 prompt 问题）
```

**监控（昨天）+ 链路（今天）+ 排查方法论（Day46）= 完整的问题定位闭环。**

---

## 2. 实操作业（80 分钟）

> 目标：traceId 贯穿你的全部日志点，一次调用 `grep` 还原全链路。

### 步骤 1：加依赖（pom.xml，版本由 Boot BOM 管理）

```xml
<!-- Micrometer Tracing（Day79）：Boot 3.x 的链路追踪正规军（Sleuth 已退役） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<!-- Zipkin 上报（可选：本地不起 Zipkin 也不影响 traceId 进日志） -->
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

### 步骤 2：配置（application.yaml 追加）

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # Demo 全采样；生产 0.1
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

> 注意：你的 yaml 已有 `logging.level` 段，`pattern` 和 `level` 是并列子键，合并到同一个 logging: 下。

### 步骤 3：验证（traceId 贯穿全链路）

```bash
# 重启后调一次网关问答
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" \
  --data-urlencode "question=分析一下退货政策的核心要点"

# 看服务日志：同一个 traceId 出现在多行不同环节
# INFO [SpringAI,6f2a8b...,xx] [ai-gateway] ...
# INFO [SpringAI,6f2a8b...,xx] [cost] ...

# 用 traceId 还原链路（6f2a8b 换成你日志里的实际值）
grep "6f2a8b" app.log
```

**自检铁证：一次调用产生 ≥2 行不同环节的日志，且 traceId 相同 —— "通过链路追踪快速定位是哪个环节出问题"的地基达成。**

### 步骤 4（可选）：Zipkin 可视化

```bash
# 有 docker 时：
docker run -d -p 9411:9411 openzipkin/zipkin
# 无 docker（你的环境）：跳过 —— traceId 进日志已达成自检，Zipkin 只是可视化增强
```

Zipkin 默认上报 `localhost:9411`，本地没起它时上报失败静默丢弃（不影响日志），确认启动日志无异常即可。

### 步骤 5：审计清单落地（为 Day80/82 埋点）

把 1.4 的审计五要素过一遍你的日志点，缺的记入清单：
- [ ] `[prompt-gray]` 补 promptVersion 记录
- [ ] `[ai-gateway]` 问题原文考虑脱敏（明天 Day80 的敏感信息脱敏正好接上）
- [ ] 所有日志确认 traceId 自动携带（今天完成）

---

## 3. 自检标准（不通过不许进 Day80）

- [ ] 一次网关调用产生 ≥2 行日志且 traceId 相同（grep 可还原）；
- [ ] 日志格式里 traceId/spanId 自动出现（MDC 生效，没有手写）；
- [ ] 能口头讲清 traceId vs spanId 的区别；采样率 Demo 1.0 / 生产 0.1 的理由；
- [ ] 能讲清为什么 Sleuth 换成了 Micrometer Tracing（Boot 3 时代栈更替）；
- [ ] 审计五要素清单过完，缺口已记录。

---

## 4. 关键踩坑清单（必背）

1. **照旧教程引入 Sleuth** → Boot 3 下依赖冲突/类名全对不上。Boot 3 用 Micrometer Tracing —— 旧教程的保鲜期只有版本号那么长。
2. **采样率 1.0 上生产** → 存储与开销爆炸。全采样只属于 Demo 和压测。
3. **异步线程池 traceId 丢失** → ThreadLocal 不会自动跨线程；你 Day43 的 `documentTaskExecutor`、Day54 的 `CompletableFuture.supplyAsync` 里打的日志可能没 traceId —— 需要上下文传播装饰器（`@Async` 用 TaskDecorator 包装；落地时按惯例 javap 验证包名）。**排查"链路断在异步边界"是最高频的追踪问题。**
4. **日志打敏感全文** → 问题原文/答案全文进日志 = 数据泄露面扩大。脱敏（明天 Day80）+ 摘要化。
5. **以为必须起 Zipkin 才有 traceId** → traceId 进日志只需 Micrometer Tracing + 日志格式；Zipkin 只是可视化。
6. **异常日志没带上下文** → `log.error("失败", e)` 的 e 里可能吞了 traceId 之外的现场（参数）—— 审计五要素补齐。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：链路追踪接入（Micrometer Tracing）+ traceId 贯穿全部日志点 + 审计五要素清单。**监控（昨天）、链路（今天）、排查方法论（Day46）三者闭环：告警发现 → traceId 定位环节 → 决策树定位根因。**

**明日（Day80）**：数据安全 —— 敏感数据脱敏、输入输出内容安全审核、数据不出域。作业：实现输入输出的敏感数据脱敏 + 内容安全审核。今天的"日志别打敏感全文"正好是它的引子。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Micrometer Tracing / Java 21*
