# Day 62：Agent 异常熔断与人工介入 —— 从"步数上限"到"防护矩阵"

> 所属阶段：第二阶段 · 第 9 周「Agent 基础原理与落地」（Day57-63）
> 今日主题：循环调用/跑偏/幻觉三大坑的检测手段；熔断后产出"人工介入单"而非抛异常
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day58 SimpleReActAgent（已有 MAX_STEPS=8）、Day61 交接校验

---

## 0. 现状盘点：你已有的防护 vs 还缺的

```
✅ 已有（Day58）：MAX_STEPS 步数熔断 —— 只防"步数"这一个维度
✅ 已有（Day61）：交接校验 —— 只防"产出不合格"这一个环节
❌ 缺：同一调用反复重试的死循环（步数没超但一直在原地打转）
❌ 缺：耗时/预算失控（步数不多但每步都慢）
❌ 缺：工具异常的兜底（手写循环里抛出来 = 直接 500）
❌ 缺：熔断之后的"交接班"信息（现在只返回一句"需人工介入"，人接手了也不知道发生了什么）
```

**今天的核心思想：熔断不是报错，是交接班。** 熔断那一刻必须产出一份「人工介入单」—— 发生了什么、走到哪、卡在哪、建议怎么办。让人能 30 秒内接手。

---

## 1. 核心知识点（40 分钟）

### 1.1 三大坑与检测手段

| 坑 | 症状 | 检测手段 | 谁来检测 |
|---|---|---|---|
| **死循环** | 同一工具+相同参数反复调用；步数狂奔 | ① 重复调用检测（连续 N 次相同调用键）② 步数上限 ③ 耗时预算 | ✅ 代码硬检测（今天做） |
| **任务跑偏** | 步骤做完了但和目标无关 | LLM-as-Judge 复核 / 关键节点人工确认 | 半自动（讲思路，综合项目再实现） |
| **幻觉严重** | 结论里出现工具没返回过的"事实" | 强制溯源（结论必须引用哪步观察）+ 抽样人工审 | 半自动 |

**优先级判断：代码硬检测（今天的三层）必须先上，因为它们零成本、确定性强；跑偏/幻觉的智能检测是进阶项。**

### 1.2 防护矩阵（今天的完整清单）

```
第 1 层  步数熔断        MAX_STEPS=8                    （Day58 已有）
第 2 层  重复调用熔断    同一调用键连续 ≥3 次 → 熔断      （今天新增）
第 3 层  耗时预算熔断    总耗时 > 120s → 熔断            （今天新增）
第 4 层  工具异常兜底    executeToolCalls 抛异常 → 熔断   （今天新增，见 1.3）
第 5 层  交接质量校验    Day61 的"功能点清单"校验         （已有）
        ↓ 全部汇入
人工介入单（结构化，不是异常栈）
```

### 1.3 一个重要发现（今天查字节码确认的）

Day58 我们手写循环用的是 `ToolCallingManager.builder().build()` —— **手动构建的 manager 不走 Spring 容器里那个 `alwaysThrow(false)` 的降级 Bean**（那个 Bean 只服务于 ChatClient 内置循环）。Builder 上有独立的 `toolExecutionExceptionProcessor(...)`，不设就用它自己的默认值。

**工程结论：手写循环里，工具异常要么被 manager 默认处理器转成错误文本，要么直接抛出来 —— 两种都必须接住。** 所以第 4 层防护就是在循环里 `try/catch executeToolCalls`，异常转成人工介入单。（想统一行为也可以在 Builder 里显式 `.toolExecutionExceptionProcessor(...)`，今天用 try/catch 最直白。）

### 1.4 生产级 Agent 设计原则（第 9 周的收束）

1. **最小工具面**：每个 Agent 只挂它需要的工具（Day51/55/61 一以贯之）
2. **不可逆操作加人闸**：写操作白名单 + 草稿确认（Day51/54）
3. **全链路留痕**：每个工具调用、每次熔断都有日志可回放
4. **成本有硬上限**：步数、耗时、token 三重预算 —— 防护不是可选项，是默认项
5. **熔断即交接班**：结构化介入单，让人能接手
6. **测试集回归**：改任何 prompt/工具后跑固定任务集（Day60 的最小评估）

---

## 2. 实操作业（80 分钟）

> 目标：把 SimpleReActAgent 升级成"防护矩阵"版，三种熔断都能触发，熔断产出人工介入单。

### 步骤 1：AgentResult 升级（改 `agent/AgentResult.java`）

```java
package com.example.springai.agent;

/**
 * Agent 执行结果（Day62 升级）：terminateReason 标明终止原因，
 * completed=false 时 answer 是【人工介入单】而不是异常信息。
 */
public record AgentResult(boolean completed, int stepsUsed, String answer, String terminateReason) {

    public static final String SUCCESS = "SUCCESS";
    public static final String MAX_STEPS_HIT = "MAX_STEPS";
    public static final String REPEAT_LOOP = "REPEAT_LOOP";
    public static final String TIME_BUDGET = "TIME_BUDGET";
    public static final String TOOL_ERROR = "TOOL_ERROR";

    public static AgentResult success(String answer, int steps) {
        return new AgentResult(true, steps, answer, SUCCESS);
    }

    public static AgentResult exhausted(int maxSteps) {
        return humanIntervention(MAX_STEPS_HIT, maxSteps,
                "任务在 " + maxSteps + " 步内未能完成");
    }

    /** 统一的人工介入出口：answer 即介入单 */
    public static AgentResult humanIntervention(String reason, int steps, String detail) {
        return new AgentResult(false, steps, detail, reason);
    }
}
```

### 步骤 2：SimpleReActAgent 升级（改 `agent/SimpleReActAgent.java`）

在 `run()` 里加三层防护 + 介入单构建（保留原有全部逻辑）：

```java
    // 新增常量
    private static final int REPEAT_CALL_LIMIT = 3;     // 同一调用键连续重复上限
    private static final long TIME_BUDGET_MS = 120_000; // 总耗时预算 120s

    public AgentResult run(String task) {
        long start = System.currentTimeMillis();
        List<Message> conversation = new ArrayList<>(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(task)));

        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .temperature(0.2)
                .build();

        Prompt prompt = new Prompt(conversation, options);
        ChatResponse response = chatModel.call(prompt);

        List<String> trace = new ArrayList<>();     // 执行轨迹（介入单用）
        String lastCallKey = null;                  // 上一轮调用键
        int repeatCount = 0;

        for (int step = 1; step <= MAX_STEPS; step++) {
            // ===== 防护3：耗时预算 =====
            if (System.currentTimeMillis() - start > TIME_BUDGET_MS) {
                log.warn("[Agent] 耗时超预算熔断");
                return AgentResult.humanIntervention(AgentResult.TIME_BUDGET, step - 1,
                        buildInterventionCard(trace, "TIME_BUDGET",
                                "总耗时超过 " + TIME_BUDGET_MS / 1000 + "s 预算"));
            }

            // 终止条件：模型不再发起工具调用
            if (!response.hasToolCalls()) {
                log.info("[Agent] 任务完成，共 {} 步", step - 1);
                return AgentResult.success(response.getResult().getOutput().getText(), step - 1);
            }

            // 本轮调用键（工具名+参数；多工具排序后拼接，保证幂等比较）
            String callKey = response.getResult().getOutput().getToolCalls().stream()
                    .map(tc -> tc.name() + "(" + tc.arguments() + ")")
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(" | "));

            // ===== 防护2：重复调用检测 =====
            if (callKey.equals(lastCallKey)) {
                repeatCount++;
            } else {
                repeatCount = 1;
                lastCallKey = callKey;
            }
            trace.add("step" + step + ": " + callKey);

            final int stepNo = step;
            response.getResult().getOutput().getToolCalls()
                    .forEach(tc -> log.info("[Agent step {}] Action: {}({})", stepNo, tc.name(), tc.arguments()));

            if (repeatCount >= REPEAT_CALL_LIMIT) {
                log.warn("[Agent] 死循环熔断：连续 {} 次相同调用", repeatCount);
                return AgentResult.humanIntervention(AgentResult.REPEAT_LOOP, step - 1,
                        buildInterventionCard(trace, "REPEAT_LOOP",
                                "连续 " + repeatCount + " 次重复相同工具调用，模型卡死在该动作上"));
            }

            // ===== 防护4：工具异常兜底（手写循环不走容器降级 Bean，必须自己接）=====
            try {
                var toolResult = toolCallingManager.executeToolCalls(prompt, response);
                prompt = new Prompt(toolResult.conversationHistory(), options);
                response = chatModel.call(prompt);
            } catch (Exception e) {
                log.error("[Agent] 工具执行异常熔断", e);
                return AgentResult.humanIntervention(AgentResult.TOOL_ERROR, step - 1,
                        buildInterventionCard(trace, "TOOL_ERROR",
                                "工具执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        // ===== 防护1：步数熔断（Day58 已有，现在也走介入单）=====
        log.warn("[Agent] 达到最大步数 {}，熔断", MAX_STEPS);
        return AgentResult.exhausted(MAX_STEPS);
    }

    /** 人工介入单：熔断即交接班 —— 让人 30 秒内能接手 */
    private String buildInterventionCard(List<String> trace, String reason, String detail) {
        return """
                【人工介入单】
                终止原因: %s
                卡点说明: %s
                已执行轨迹:
                %s
                处置建议: 人工检查工具可用性与参数，或改写任务描述后重试
                """.formatted(reason, detail,
                trace.isEmpty() ? "  （尚无工具调用）" : "  " + String.join("\n  ", trace));
    }
```

### 步骤 3：熔断演示工具（新建 `tool/LoopDemoTool.java`）

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 熔断演示工具（Day62）：两个故意"坏掉"的工具，验证防护矩阵。
 * 生产代码里绝不放这种工具。
 */
public class LoopDemoTool {

    /** 永远返回"请重试" → 诱导模型反复相同调用 → 触发 REPEAT_LOOP 熔断 */
    @Tool(description = "查询实时故障工单的最新进展。查询失败时必须重新查询以确认")
    public String queryTicket(
            @ToolParam(description = "工单号") String ticketId) {
        return "工单系统繁忙，查询失败，请再次尝试查询以确认工单状态";
    }

    /** 直接抛异常 → 触发 TOOL_ERROR 熔断（手写循环的异常兜底路径） */
    @Tool(description = "执行一次系统故障演练（测试专用）")
    public String crashTest() {
        throw new IllegalStateException("模拟下游服务宕机");
    }
}
```

### 步骤 4：演示入口（改 `controller/ReActAgentController.java`）

加一个挂了坏工具的演示 Agent（**不要**并进正常 agent）：

```java
    private final SimpleReActAgent loopDemoAgent;

    public ReActAgentController(ChatModel chatModel, JdbcTemplate jdbc) {
        this.agent = new SimpleReActAgent(chatModel,
                new ReportFlowTools(jdbc), new OrderTool(),
                new CalculatorTool(), new NotificationTool());
        // Day62 演示专用：挂两个坏工具，验证熔断
        this.loopDemoAgent = new SimpleReActAgent(chatModel, new LoopDemoTool());
    }

    /** 死循环/异常熔断演示 */
    @GetMapping("/loop-demo")
    public Map<String, Object> loopDemo(@RequestParam String task) {
        var result = loopDemoAgent.run(task);
        return Map.of(
                "completed", result.completed(),
                "terminateReason", result.terminateReason(),
                "interventionCard", result.answer()
        );
    }
```

### 步骤 5：验证（4 个场景）

```bash
# ① 正常任务不受影响（防护是护栏不是路障）
curl -G "http://localhost:8080/api/react-agent/run" \
  --data-urlencode "task=查一下ORD-142到哪了"
# 期望：completed=true, terminateReason=SUCCESS

# ② 重复调用熔断（坏工具永远说"请重试"）
curl -G "http://localhost:8080/api/react-agent/loop-demo" \
  --data-urlencode "task=查一下工单 T-100 的最新进展"
# 期望：completed=false, terminateReason=REPEAT_LOOP，介入单里能看到连续 3 次相同调用
#       （若模型重试 2 次后放弃自己回答，也是合格的诚实行为 —— 熔断是兜底不是主流程）

# ③ 工具异常熔断（直接抛异常）
curl -G "http://localhost:8080/api/react-agent/loop-demo" \
  --data-urlencode "task=执行一次系统故障演练"
# 期望：completed=false, terminateReason=TOOL_ERROR，请求【不报 500】，介入单含异常信息

# ④ 步数熔断回归验证：把 MAX_STEPS 临时改成 2，跑场景①
# 期望：terminateReason=MAX_STEPS，介入单含 2 步轨迹（改回 8）
```

---

## 3. 自检标准（不通过不许进 Day63）

- [ ] 场景①正常任务不受防护影响（护栏不是路障）；
- [ ] ②③至少一个熔断真实触发，返回**结构化介入单**（含终止原因/卡点/轨迹/建议），请求不 500；
- [ ] ④步数熔断也走介入单（不再是 Day58 的一句干话）；
- [ ] 能口头讲清防护矩阵 5 层各自防什么；以及"熔断即交接班"为什么比"熔断即报错"好；
- [ ] 能讲清为什么手写循环里工具异常必须自己兜（手动 manager 不走容器降级 Bean）。

---

## 4. 关键踩坑清单（必背）

1. **以为容器里的降级 Bean 全局生效** → 只作用于 ChatClient 内置循环；手写循环的 manager 是独立实例，异常必须自己 try/catch。
2. **熔断返回一句干话** → 人接手时两眼一抹黑。必须带轨迹、卡点、建议（交接班标准）。
3. **重复调用检测键不稳定** → 参数 JSON 键序可能不同导致误判漏判；比较前统一规范化（今天用 name+arguments 拼接，足够 Demo；生产可规范化 JSON 再比）。
4. **防护参数写死不可调** → MAX_STEPS/预算应可配置（综合项目挪到 application.yaml）。
5. **防护是路障** → 正常任务被误熔断 = 最糟糕的体验。每次加防护，先用正常任务集回归（Day60 的测试集）。
6. **跑偏/幻觉指望代码全解决** → 代码只能硬防死循环和异常；跑偏要 LLM-Judge、幻觉要溯源约束 —— 分层治理，别指望一层防护包治百病。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：从"步数熔断"到"防护矩阵"的升级 —— 重复调用/耗时预算/工具异常三层新防护 + 统一的人工介入单。最大的认知转变：**熔断不是失败，是把失控的任务体面地交还给人类。** 至此你的 Agent 有了生产级的安全网。

**明日（Day63，第 9 周收官）**：把 Agent 能力整合进知识库项目 —— 智能助手雏形完成版（知识检索 + 数据查询 + 工具执行 + 记忆 + 熔断，全部合体），并做第 9 周复盘。之后进入第 10 周（AI 落地场景方案认知）。

---

*文档生成日期：2026-09-08 · 技术版本：Spring AI 1.0.3 / Java 21*
