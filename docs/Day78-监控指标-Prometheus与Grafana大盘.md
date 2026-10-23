# Day 78：AI 应用监控 —— 指标体系 + Prometheus + Grafana 大盘

> 所属阶段：第三阶段 · 第 12 周「可观测性 + 安全合规」（Day78-84）
> 今日主题：把散落在 stats 端点里的指标标准化成"可被抓取的端点 + 可视化面板 + 告警"
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Micrometer + Prometheus + Grafana
> 前置：actuator 已装（Day75）—— 监控的地基昨天就打好了

---

## 0. 今天要解决的问题

你的指标现在**有数据、没出口**：

```
数据在哪？  Day72 计数器（cacheHits/模型路由/tokensSaved）✅
           Day76 A/B 计数（v1/v2 分流）✅
           Day74 压测口径（P95/成功率）✅
出口在哪？  只有人肉 curl /api/cost/stats ❌
```

**监控 = 三件事：可抓取的指标端点（Prometheus 格式）+ 可视化大盘（Grafana）+ 告警（出事叫人）。** 今天做完前两件，告警建立概念。

---

## 1. 核心知识点（40 分钟）

### 1.1 指标体系：两层八项（每项都标注你哪天的代码）

**技术指标（系统健不健康）：**

| 指标 | 含义 | 你的来源 |
|---|---|---|
| 调用量（QPS） | 每秒请求数 | 网关层计数（Day73） |
| 响应耗时 | avg/P50/P95/P99 | Day74 的口径 → 今天用 Timer 实现 |
| 成功率 / 错误率 | 2xx 占比 / 分错误类型 | 网关层计数 |
| **Token 用量** | 每次/每天消耗、tokensSaved | Day72 的 usage 计量 ✅ 直接接入 |

**业务指标（AI 答得好不好）：**

| 指标 | 含义 | 你的来源 |
|---|---|---|
| 缓存命中率 | 命中/总请求 | Day72 计数器 ✅ |
| 模型路由分布 | 大小模型占比 | Day72 计数器 ✅（成本观察） |
| 幻觉率 / 准确率 | 评测集回归（离线） | Day47 ChunkTest + RagGenerationEval |
| A/B 版本分布 | v1/v2 分流占比 | Day76 计数器 ✅ |

**关键认知：技术指标看"系统活没活"，业务指标看"AI 靠不靠谱"——两层数据源不同（前者实时埋点，后者离线评测+采样），别混在一个大盘里。**

### 1.2 Micrometer —— Spring Boot 的指标门面

```
你的代码 → Micrometer API（门面）→ 各后端（Prometheus / Datadog / …）
```

- Actuator（Day75 已装）内置 Micrometer，**只需加一个 registry 依赖**，自动暴露 `/actuator/prometheus` 端点
- 指标三类型（选错类型 = 数据没用）：

| 类型 | 语义 | 今天的用途 |
|---|---|---|
| **Counter** | 只增不减的累计值 | 调用量、cacheHits、tokensSaved（可 increment(N)） |
| **Gauge** | 瞬时值（可升可降） | cacheSize、当前灰度比例 |
| **Timer** | 耗时分布（自动出 count/sum/max） | LLM 调用耗时 —— **必须开 percentileHistogram 才有 P95** |

### 1.3 Prometheus 拉模型 + Grafana

```
你的应用 /actuator/prometheus（暴露文本格式指标）
     ↑ Prometheus 定时来拉（pull，15s 一次）
     ↑ Grafana 查 Prometheus 做大盘（PromQL 查询）
```

- **拉模型的好处**：应用挂了 Prometheus 能发现（拉不到 = 目标死了），且应用无需知道监控系统存在
- **Grafana 大盘结构**：Row（行）→ Panel（面板）→ 查询（PromQL）
- **告警**：Alertmanager 挂在 Prometheus 上 —— **大盘是给人看的，告警是叫人的**；只做大盘不做告警 = 出事了还得自己盯着盘发现

### 1.4 高基数陷阱（监控第一坑）

标签（tag）的取值必须是**有限枚举**：`route=SMALL/BIG/CACHE`、`model=small/big`、`promptVersion=v1/v2` ✅
`question=用户输入`、`userId=具体人` ❌ —— 每个唯一值都是一个时间序列，无限标签会把 Prometheus 内存打爆。

**规则：业务明细（谁问了什么）走日志/审计（Day79），监控只留有限维度的聚合值。**

---

## 2. 实操作业（80 分钟）

> 目标：自定义指标接入 Micrometer → /actuator/prometheus 可见 →（有 docker 时）Prometheus + Grafana 大盘。

### 步骤 1：加依赖（pom.xml）

```xml
<!-- Micrometer Prometheus registry（Day78）：版本由 Boot BOM 管理，不用写 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 步骤 2：暴露端点（application.yaml 的 management 段调整）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus    # 加 prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

### 步骤 3：改造 `CostOptimizedChatService`（AtomicLong → Micrometer）

```java
// ===== 字段与构造器：计数器交给 MeterRegistry 托管 =====
private final Counter cacheHitCounter;
private final Counter smallModelCounter;
private final Counter bigModelCounter;
private final Counter tokensSavedCounter;
private final Timer smallModelTimer;
private final Timer bigModelTimer;

public CostOptimizedChatService(@Qualifier("qwenChatClient") ChatClient bigModel,
                                @Qualifier("deepseekChatClient") ChatClient smallModel,
                                MeterRegistry registry) {          // ← 注入（Actuator 已提供 Bean）
    this.bigModel = bigModel;
    this.smallModel = smallModel;

    // Counter：只增累计；tokensSaved 用 increment(N) 记数量级
    this.cacheHitCounter = Counter.builder("ai.chat.cache.hits")
            .description("缓存命中次数").register(registry);
    this.smallModelCounter = Counter.builder("ai.chat.model.calls")
            .tag("model", "small").register(registry);
    this.bigModelCounter = Counter.builder("ai.chat.model.calls")
            .tag("model", "big").register(registry);
    this.tokensSavedCounter = Counter.builder("ai.chat.tokens.saved")
            .description("缓存命中累计节省的 tokens").register(registry);

    // Timer：开 percentileHistogram 才能在 Prometheus 算 P95（1.2 节的坑）
    this.smallModelTimer = Timer.builder("ai.chat.llm.duration")
            .tag("model", "small").publishPercentileHistogram().register(registry);
    this.bigModelTimer = Timer.builder("ai.chat.llm.duration")
            .tag("model", "big").publishPercentileHistogram().register(registry);
}

// ===== 调用点改造（原 AtomicLong 逻辑替换）=====
// 缓存命中处：
cacheHitCounter.increment();
tokensSavedCounter.increment(hit.totalTokens());

// 小模型路径：
smallModelCounter.increment();
return smallModelTimer.record(() -> { ... 原有调用逻辑 ... });

// 大模型路径同理（bigModelCounter / bigModelTimer）。
// stats() 端点保留，内部读 counter.count() —— 人看 JSON、Prometheus 看端点，两不误。
```

> 标签只用有限枚举（model=small/big）—— 高基数红线见 1.4。

### 步骤 4：验证指标端点

```bash
# 调几次 /api/cost/chat（一次 MISS + 几次 HIT）后：
curl -s "http://localhost:8080/actuator/prometheus" | grep "^ai_chat"
# 期望看到：
# ai_chat_cache_hits_total 2.0
# ai_chat_model_calls_total{model="small"} 1.0
# ai_chat_tokens_saved_total 81.0
# ai_chat_llm_duration_seconds_count{model="small"} 1.0
# ai_chat_llm_duration_seconds_bucket{model="small",le="+Inf",...}  ← histogram 桶
```

**自检铁证：grep 到 `ai_chat_` 开头的自定义指标 = "能实时看到调用量、Token 消耗"达成。**

### 步骤 5：Prometheus + Grafana（docker-compose 备好，有 docker 时一键起）

新建 `monitoring/docker-compose.yml`：

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    ports: ["9090:9090"]
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
  grafana:
    image: grafana/grafana:latest
    ports: ["3000:3000"]
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin   # 演示用，生产改掉
```

新建 `monitoring/prometheus.yml`：

```yaml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: ai-app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]   # 容器内访问宿主机应用
```

```bash
docker compose up -d
# Prometheus: http://localhost:9090（查 ai_chat_cache_hits_total 有数据即链路通）
# Grafana:    http://localhost:3000（admin/admin）→ 添加 Prometheus 数据源 → 建面板
```

### 步骤 6：Grafana 面板清单（PromQL 直接可抄）

| 面板 | 类型 | PromQL |
|---|---|---|
| QPS | 时序 | `sum(rate(ai_chat_model_calls_total[1m]))` |
| **成功率** | Stat | `sum(rate(ai_chat_success_total[1m])) / sum(rate(ai_chat_all_total[1m]))`（需在网关层加 success/total 两个 Counter） |
| **LLM 耗时 P95** | 时序 | `histogram_quantile(0.95, sum(rate(ai_chat_llm_duration_seconds_bucket[5m])) by (le, model))` |
| 缓存节省 tokens | Counter 面板 | `ai_chat_tokens_saved_total` |
| 路由分布 | 饼图 | `sum by (model) (rate(ai_chat_model_calls_total[5m]))` |

---

## 3. 自检标准（不通过不许进 Day79）

- [ ] `/actuator/prometheus` 能 grep 到 `ai_chat_` 自定义指标（调用量/Token/耗时三样齐全）；
- [ ] Timer 开了 percentileHistogram（否则 P95 查不出来 —— 1.2 的坑）；
- [ ] 标签全部有限枚举（没有 question/userId 这类高基数标签）；
- [ ] PromQL 能写出成功率/命中率的查询（面板清单里的语句能解释）；
- [ ] 能口头讲清：技术指标 vs 业务指标的数据源差异；拉模型的好处；为什么大盘之外还要告警。

---

## 4. 关键踩坑清单（必背）

1. **高基数标签** → question/userId 做标签 = 时间序列爆炸，Prometheus OOM。明细走日志（Day79），监控只留有限枚举。
2. **Timer 忘开 publishPercentileHistogram** → 只有 count/sum/max，PromQL 算不出 P95 —— 而你的口径恰恰是 P95。
3. **只做大盘不做告警** → 大盘要人盯着，告警才叫人。核心指标（成功率<99%、P95>2s）必须有告警规则（Alertmanager，进阶）。
4. **Counter 当 Gauge 用** → cacheSize 这种可升可降的值要用 Gauge 注册；Counter 只能加。
5. **指标命名不统一** → 团队统一前缀（本项目 `ai.chat.*`）+ 点分转下划线是 Prometheus 自动行为，查询时用下划线。
6. **监控忘了隔离环境** → 压测流量会污染大盘（Day74 压测时的尖峰和真实流量混在一起）。分环境部署监控或打 instance 标签。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：监控体系的第一块拼图 —— 自定义指标经 Micrometer 标准化暴露（Prometheus 格式），大盘与告警的完整方案备好（docker-compose 一键起）。**Day72 的计数器、Day74 的口径、Day76 的 A/B 计数全部获得了标准出口。**

**明日（Day79）**：日志与链路追踪 —— 把 AI 调用纳入分布式追踪（Micrometer Tracing：traceId 贯穿网关→模型→工具），全链路日志（请求 ID/用户/模型版本/提示词/返回结果），以及"效果不好时怎么查日志"的分析方法。监控告诉你"出了问题"，链路追踪告诉你"哪一步出的问题"。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Micrometer / Java 21*
