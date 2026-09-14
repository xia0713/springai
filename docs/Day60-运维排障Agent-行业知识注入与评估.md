# Day 60：典型 Agent 场景 —— 运维排障助手（行业知识注入 + 效果评估）

> 所属阶段：第二阶段 · 第 9 周「Agent 基础原理与落地」（Day57-63）
> 今日主题：给 Day58 的 ReAct 引擎换一套"运维工具包" —— 排障 Agent；行业知识怎么注入；Agent 好不好怎么量化
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day58 SimpleReActAgent 已跑通（今天直接复用，不写新循环）

---

## 0. 今天要解决的问题

你的 Agent 已经"会干活"了，但还不"懂行"。把同一套引擎放到运维场景：

```
用户（贴一段报错日志）：
ERROR [http-nio-8080-exec-3] OrderService - 调用支付服务失败:
  feign.FeignException$ServiceUnavailable: [503] during POST to /pay

期望 Agent 像一个老运维一样分步排查：
① 识别关键信号：503 = 服务暂不可用
② 查系统指标：order-service CPU 92%、线程池满
③ 查依赖健康：pay-service 响应超时
④ 结论：下游过载引发雪崩 → 建议：对 pay-service 限流 + order-service 侧加熔断
```

这就是**行业 Agent**：引擎不变，换工具 + 注入行业知识。今天三件事：

1. 给排障场景配一套"运维工具"（错误码手册 / 系统指标 / 依赖健康）
2. 理解行业知识注入的 3 种方式与取舍
3. 学会量化"Agent 好不好用"（Day58 的 `AgentResult` 已经给了你统计口径）

---

## 1. 核心知识点（40 分钟）

### 1.1 三类典型 Agent 场景的工具面设计

| 场景 | 核心工具面 | 设计要点 |
|---|---|---|
| **运维排障**（今天做） | 错误码手册、指标查询、依赖健康、日志检索 | 工具返回**结构化事实**，结论让模型推 |
| **数据分析助手** | NL2SQL（Day52）、指标口径表、报表生成 | 口径必须注入（"销售额=不含取消订单"），不然模型自由发挥 |
| **智能客服** | 知识库检索（Day56）、订单查询、工单创建 | 写操作（建工单）走白名单 + 人工确认（Day55 红线） |

**共同设计原则：工具只负责"取事实"，推理和结论交给模型。** 工具返回"CPU 92%"而不是"系统过载" —— 判断是模型的活，工具别越权（不然出错没法归因）。

### 1.2 行业知识注入的 3 种方式

| 方式 | 做法 | 适用 | 取舍 |
|---|---|---|---|
| **① 结构化工具**（今天主力） | 错误码手册做成 `lookupErrorCode` 工具 | 知识**稳定、结构化**（错误码表、指标口径） | ✅ 确定性最强、可测试；❌ 维护要改代码 |
| **② System Prompt 注入** | 排查 SOP/优先级写进 system prompt | 知识**量小、常变**（排查思路、报告格式） | ✅ 改起来快；❌ 占 token、多了会被稀释 |
| **③ RAG 知识库**（你已会） | 运维文档入库，`searchKnowledge` 检索 | 知识**量大、非结构化**（历史故障案例、架构文档） | ✅ 量大管饱；❌ 检索质量依赖分块（Day34 的坑会重现） |

**选型口诀：稳定的做成工具，易变的写进 prompt，海量的走 RAG。** 生产级排障 Agent 三者都用：错误码工具 + SOP 提示词 + 历史故障库检索。

### 1.3 效果评估：怎么判断 Agent 好不好用

Agent 评估比 RAG 评估更难（路径不固定），但核心指标是清晰的：

| 指标 | 定义 | 你的现状 |
|---|---|---|
| **任务完成率** | 测试任务集里 completed=true 的比例 | ✅ `AgentResult.completed` 已有 |
| **步数效率** | 实际步数 vs 最优步数（浪费 = token） | ✅ `AgentResult.stepsUsed` 已有 |
| **工具调用正确率** | 该调的工具调了吗？参数对吗？ | ⚠️ 靠日志人工看 |
| **答案质量** | 排查思路是否完整（定位→根因→建议） | ⚠️ 人工/规则判 |
| **稳定性** | 同一任务跑 N 次，行为是否一致 | 新指标（跑 3 遍对比） |

**评估方法（继承 Day47 的思想）**：建一个小测试集（5-10 个典型报错日志 + 期望要点），每次改 Agent（换工具/改 prompt）后跑一遍，对比完成率和步数。**没有测试集的 Agent 调优 = 瞎调。**

---

## 2. 实操作业（80 分钟）

> 目标：用 Day58 的 `SimpleReActAgent` 引擎 + 3 个运维工具，做排障助手。输入报错日志，输出分步排查结论。

### 步骤 1：三个运维工具（新建 `tool/OpsTools.java`）

```java
package com.example.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 运维排障工具集（Day60）—— 行业知识注入方式①：结构化工具。
 * 设计原则：工具只返回【事实】，结论让模型推（出错可归因）。
 * 数据是 mock 的，生产对接监控系统（Prometheus/SkyWalking）。
 */
@Slf4j
public class OpsTools {

    /** 行业知识：错误码手册（生产应来自 RAG 知识库或配置中心） */
    private static final Map<String, String> ERROR_BOOK = Map.of(
            "503", "服务暂不可用。常见原因：下游服务过载、正在发布维护、连接池耗尽。建议排查顺序：①看本服务与目标服务的 CPU/内存/线程池 ②查依赖服务健康状态 ③查网关/负载均衡日志",
            "502", "网关错误，上游无有效响应。常见原因：下游服务崩溃或重启中。建议：①查下游进程存活 ②查下游启动日志 ③查健康检查配置",
            "504", "网关超时。常见原因：下游处理时间超过网关超时阈值。建议：①查下游接口耗时（慢 SQL/外部调用）②核对网关超时配置",
            "429", "请求过于频繁，触发限流。建议：①确认限流阈值是否合理 ②检查是否有突发流量或重试风暴",
            "401", "未授权。建议：①检查 token 是否过期 ②检查鉴权配置与密钥"
    );

    @Tool(description = "查询 HTTP/业务错误码的运维手册（含义+排查步骤）。拿到报错日志中的错误码后先用这个")
    public String lookupErrorCode(
            @ToolParam(description = "错误码，如 503、504、429") String errorCode) {
        log.info("[ops] lookupErrorCode({})", errorCode);
        String info = ERROR_BOOK.get(errorCode.trim());
        return info != null ? info : "错误码手册中没有 " + errorCode + "，请根据日志上下文自行分析";
    }

    @Tool(description = "查询指定服务的系统指标（CPU/内存/线程池/GC）。排查性能与过载问题时使用")
    public String getSystemMetrics(
            @ToolParam(description = "服务名，如 order-service、pay-service") String serviceName) {
        log.info("[ops] getSystemMetrics({})", serviceName);
        // mock：生产对接 Prometheus
        return switch (serviceName.toLowerCase()) {
            case "order-service" -> "order-service 指标：CPU 92%（高位），内存 78%，线程池 200/200（已满），FULL GC 近1小时 12 次（频繁）";
            case "pay-service" -> "pay-service 指标：CPU 97%（过载），内存 85%，线程池 500/500（已满），平均响应时间 4800ms（严重变慢）";
            default -> serviceName + " 指标：CPU 35%，内存 52%，线程池 40/200，平均响应时间 120ms（正常）";
        };
    }

    @Tool(description = "检查指定服务的下游依赖健康状态（数据库/缓存/下游服务）。怀疑依赖故障时使用")
    public String checkDependency(
            @ToolParam(description = "服务名") String serviceName) {
        log.info("[ops] checkDependency({})", serviceName);
        return switch (serviceName.toLowerCase()) {
            case "order-service" -> "order-service 依赖健康：数据库（正常，连接池 20/50），缓存 Redis（正常），下游 pay-service（异常：最近5分钟超时率 45%）";
            case "pay-service" -> "pay-service 依赖健康：数据库（正常），第三方支付通道（异常：响应超时率 60%，通道疑似限流本方）";
            default -> serviceName + " 依赖健康：全部正常";
        };
    }
}
```

### 步骤 2：排障 Agent 控制器（新建 `controller/OpsAgentController.java`）

```java
package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.OpsTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运维排障 Agent（Day60）：SimpleReActAgent 引擎 + 运维工具集。
 * 行业知识注入：错误码手册=工具（稳定知识），排查思路=system prompt（易变知识）。
 */
@RestController
@RequestMapping("/api/ops-agent")
public class OpsAgentController {

    private final SimpleReActAgent agent;

    /** 行业知识注入方式②：排查 SOP 写进 system prompt（注意 SimpleReActAgent 的 SYSTEM_PROMPT 是排障专版） */
    public OpsAgentController(ChatModel chatModel) {
        this.agent = new SimpleReActAgent(chatModel, new OpsTools());
    }

    @GetMapping("/troubleshoot")
    public Map<String, Object> troubleshoot(@RequestParam String log) {
        var result = agent.run("以下是系统报错日志，请分步排查并给出结论和处置建议：\n" + log);
        return Map.of(
                "completed", result.completed(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }
}
```

> ⚠️ 注意：`SimpleReActAgent` 的 `SYSTEM_PROMPT` 是通用版。为了给排障 Agent 定制 SOP（先定位错误码→查指标→查依赖→给结论），把 `SYSTEM_PROMPT` 改成**可定制**：给构造器加一个重载，接收自定义 system prompt（保留原无参逻辑做默认）。这一步是今天"注入行业知识"的落点，别跳过。

### 步骤 3：验证（3 个日志场景）

```bash
# ① 503 场景（完整链路：查手册→查指标→查依赖→结论）
curl "http://localhost:8080/api/ops-agent/troubleshoot?log=ERROR%20OrderService%20-%20%E8%B0%83%E7%94%A8%E6%94%AF%E4%BB%98%E6%9C%8D%E5%8A%A1%E5%A4%B1%E8%B4%A5:%20feign.FeignException%24503"

# ② 429 场景（限流，两步就该出结论）
curl "http://localhost:8080/api/ops-agent/troubleshoot?log=ERROR%20Gateway%20-%20HTTP%20429%20Too%20Many%20Requests%2C%20rate%20limit%20exceeded"

# ③ 无关日志（不硬凑，诚实说明需要更多信息）
curl "http://localhost:8080/api/ops-agent/troubleshoot?log=%E7%94%A8%E6%88%B7%E5%BC%A0%E4%B8%89%E7%99%BB%E5%BD%95%E6%88%90%E5%8A%9F"
```

**看日志的 `[ops]` 行和 `[Agent step]` 行**：场景①应依次看到 lookupErrorCode → getSystemMetrics(pay-service) → checkDependency；场景②步数应明显少于①（手册说清楚了两步就能定位）。

### 步骤 4（可选加分）：简单评估

把场景①跑 3 遍，记录每次 `stepsUsed` 和结论要点。3 次结论要点一致 → 稳定性 OK；步数 ≤5 → 效率可接受。这就是"测试集评估"的最小实践。

---

## 3. 自检标准（不通过不许进 Day61）

- [ ] 场景①：日志依次出现 3 类工具调用，最终答案包含「定位（错误码含义）→ 根因（哪个服务什么状态）→ 建议（限流/扩容/熔断）」三段；
- [ ] 场景②：步数少于场景①（说明模型在按 SOP 灵活走，不是死板全跑一遍）；
- [ ] 场景③：不硬凑工具，诚实说需要更多信息；
- [ ] `SYSTEM_PROMPT` 已可定制（排障 SOP 生效），能说清行业知识三种注入方式各自的适用；
- [ ] 场景①跑 3 遍，结论要点一致（稳定性有底）。

---

## 4. 关键踩坑清单（必背）

1. **工具越权下结论** → 工具返回"系统过载"这种判断，错了没法归因。工具只给事实（CPU 92%），推理归模型。
2. **行业知识全塞 system prompt** → 排障 SOP 三五行可以，错误码表几十条塞进去 = token 灾难 + 模型稀释。稳定的知识做成工具。
3. **工具返回太啰嗦** → 手册一条 200 字 × 多轮循环，上下文滚雪球。控制每条返回 ≤150 字。
4. **不给 Agent"不知道"的出口** → 陌生错误码必须让它说"手册没有，需人工分析"，禁止硬编。
5. **没有测试集就调优** → 改一版 prompt 步数从 3 变 7 都不知道。至少 3-5 个场景的固定测试集。
6. **复用 SimpleReActAgent 忘了 system prompt 可定制** → 排障 SOP 进不去，Agent 拿通用 prompt 瞎跑。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：第一个行业 Agent —— 引擎（Day58）+ 工具面（错误码/指标/依赖）+ 知识注入（工具/prompt 分层）+ 评估意识（完成率/步数/稳定性）。最大收获：**Agent 落地的难度不在循环（百行代码），在工具面设计和行业知识注入。**

**明日（Day61）**：多 Agent 协作 —— 不同角色 Agent 分工配合（需求分析师 + 开发工程师），调度器、消息传递、任务分发。你会看到：多 Agent 的成本和复杂度陡增，"什么时候真的需要多 Agent"是比"怎么写"更重要的问题。

---

*文档生成日期：2026-09-08 · 技术版本：Spring AI 1.0.3 / Java 21*
