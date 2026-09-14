# Day 58：手写 ReAct Agent —— 思考→行动→观察循环（Spring AI 底层驱动）

> 所属阶段：第二阶段 · 第 9 周「Agent 基础原理与落地」（Day57-63）
> 今日主题：把 Spring AI 藏在背后的工具循环"翻出来自己开" —— 显式 ReAct 循环 + 步数熔断
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day57 Agent 认知日；第 8 周工具调用全部跑通

---

## 0. 今天到底在做什么（先想清楚）

**一个诚实的真相：你其实已经用过 Agent 循环了。**

Day54 报表流程里，模型"查→写→存→发"自主串工具 —— 那就是循环。但它发生在 **Spring AI 内部**（`ChatClient` 帮你把工具调用执行掉、结果塞回去、再问模型），你看不见循环、控不了步数。

今天把内部循环**翻出来自己开**：

| | ChatClient 内置循环（之前） | 手写循环（今天） |
|---|---|---|
| 循环控制权 | Spring AI 内部 | **你的代码** |
| 步数上限 | 不可控（靠模型自觉） | **MAX_STEPS 熔断** |
| 每步可见性 | 只看到最终结果 | **每一步 Action/Observation 打日志** |
| 价值 | 快速开发 | 理解机理 + 生产级管控 |

这就是 Day57 说的"可靠性数学"的工程对策落地：**步数熔断 = 乘法衰减的止损失。**

---

## 1. ReAct 循环的本质（40 分钟）

### 1.1 循环长什么样

```
Thought（思考）: 我需要先查 8 月的销售数据
Action（行动）: 调用 querySalesStats("2026-08")
Observation（观察）: 8月销售统计：订单 6 笔，总销售额 4355 元...
Thought: 平均每单 = 4355/6，我用 calculate 算一下
Action: 调用 calculate(4355, "/", 6)
Observation: 4355.0 / 6 = 725.83...
Thought: 数据齐了，通知张三
Action: 调用 sendNotification("张三", "8月平均每单725.83元")
Observation: 已成功发送通知给 张三
Thought: 任务完成 → Final Answer: 已完成查询和通知
```

**每一轮 = 模型决策（Thought+Action）→ 你的代码执行 → 结果回喂（Observation）→ 模型再决策。** 直到模型不再发起工具调用（它认为任务完成）或步数熔断。

### 1.2 ReAct 和"一段式"调用质的区别

Day54 里模型一次收到任务、一口气串完 —— 看起来一样，但有一个决定性差异：**ReAct 的下一步取决于上一步的观察结果**。

```
条件任务：「查 ORD-142 到哪了，还没签收就通知张三预计到达时间；签收了就不用通知」
   ↓
模型必须先看 Observation：
   ├─ 观察到"已签收" → 不调通知工具，直接回答
   └─ 观察到"已发货" → 才调 sendNotification
```

**分支逻辑不是你写的，是模型看观察结果现场决定的** —— 这就是 Agent 相对 workflow 的本质差异（Day57 的"步骤谁决定"）。

### 1.3 手写循环的三个关键 API（已查 jar 验证，1.0.3 真实签名）

```java
// ① 关掉 Spring AI 的内置工具执行 —— 控制权回到你手里（不加这个 = 循环白写）
ToolCallingChatOptions.builder()
        .toolCallbacks(toolCallbacks)
        .internalToolExecutionEnabled(false)   // ← 关键开关
        .temperature(0.2)
        .build();

// ② 自己执行模型请求的工具调用
ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, chatResponse);

// ③ 拿到"已追加工具结果"的完整对话历史，重新发起下一轮
List<Message> history = result.conversationHistory();
prompt = new Prompt(history, options);
chatResponse = chatModel.call(prompt);

// ④ 终止判断：模型不再发起工具调用
chatResponse.hasToolCalls()   // false = 模型认为任务完成
```

---

## 2. 实操作业（80 分钟）

> 目标：手写 `SimpleReActAgent`（显式循环 + 步数熔断 + 每步日志），复用第 8 周的工具，跑通 3 步以上和条件分支任务。

### 步骤 1：Agent 结果模型（新建 `agent/AgentResult.java`）

```java
package com.example.springai.agent;

/** Agent 执行结果：completed=false 表示步数耗尽被熔断 */
public record AgentResult(boolean completed, int stepsUsed, String answer) {

    public static AgentResult success(String answer, int steps) {
        return new AgentResult(true, steps, answer);
    }

    public static AgentResult exhausted(int maxSteps) {
        return new AgentResult(false, maxSteps,
                "任务在 " + maxSteps + " 步内未能完成，已熔断，需人工介入");
    }
}
```

### 步骤 2：ReAct Agent 核心（新建 `agent/SimpleReActAgent.java`）

```java
package com.example.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 手写 ReAct Agent（Day58）：显式驱动 思考→行动→观察 循环。
 * <p>
 * 核心三点：
 * ① internalToolExecutionEnabled(false) —— 关掉 Spring AI 内置循环，控制权回到代码
 * ② 循环内自己执行工具、重建 Prompt、再次调用
 * ③ MAX_STEPS 熔断 —— Day57 可靠性数学的工程对策
 */
@Slf4j
public class SimpleReActAgent {

    private static final int MAX_STEPS = 8;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;

    private static final String SYSTEM_PROMPT = """
            你是一个能干活的任务代理。面对复杂任务：
            1. 先想清楚需要哪几步（可以先用一两句话说明思路）
            2. 逐步调用工具完成，每一步根据上一步结果决定下一步
            3. 信息足够后，直接给出最终答案（不要再调用工具）
            4. 任务无法完成时，诚实说明原因，不要编造
            """;

    public SimpleReActAgent(ChatModel chatModel, Object... toolObjects) {
        this.chatModel = chatModel;
        this.toolCallingManager = ToolCallingManager.builder().build();
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build();
        this.toolCallbacks = Arrays.asList(provider.getToolCallbacks());
    }

    public AgentResult run(String task) {
        List<Message> conversation = new ArrayList<>(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(task)));

        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)   // ① 关键：自己开循环
                .temperature(0.2)
                .build();

        Prompt prompt = new Prompt(conversation, options);
        ChatResponse response = chatModel.call(prompt);

        for (int step = 1; step <= MAX_STEPS; step++) {
            // 终止条件：模型不再发起工具调用 = 它认为任务完成
            if (!response.hasToolCalls()) {
                String answer = response.getResult().getOutput().getText();
                log.info("[Agent] 任务完成，共 {} 步", step - 1);
                return AgentResult.success(answer, step - 1);
            }

            // Action：执行模型请求的工具调用（Observation 已由 manager 追加进历史）
            response.getResult().getOutput().getToolCalls()
                    .forEach(tc -> log.info("[Agent step {}] Action: {}({})", step, tc.name(), tc.arguments()));
            var toolResult = toolCallingManager.executeToolCalls(prompt, response);

            // 带着新历史进入下一轮思考
            prompt = new Prompt(toolResult.conversationHistory(), options);
            response = chatModel.call(prompt);
        }

        log.warn("[Agent] 达到最大步数 {}，熔断", MAX_STEPS);
        return AgentResult.exhausted(MAX_STEPS);
    }
}
```

### 步骤 3：控制器（新建 `controller/ReActAgentController.java`）

```java
package com.example.springai.controller;

import com.example.springai.agent.SimpleReActAgent;
import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.NotificationTool;
import com.example.springai.tool.OrderTool;
import com.example.springai.tool.ReportFlowTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/react-agent")
public class ReActAgentController {

    private final SimpleReActAgent agent;

    public ReActAgentController(ChatModel chatModel, JdbcTemplate jdbc) {
        // 复用第 8 周写好的工具 —— Agent 的地基是工具，今天只是换了"驾驶员"
        this.agent = new SimpleReActAgent(chatModel,
                new ReportFlowTools(jdbc), new OrderTool(),
                new CalculatorTool(), new NotificationTool());
    }

    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam String task) {
        var result = agent.run(task);
        return Map.of(
                "completed", result.completed(),
                "stepsUsed", result.stepsUsed(),
                "answer", result.answer()
        );
    }
}
```

### 步骤 4：验证（3 个任务，覆盖核心能力）

```bash
# ① 3 步以上任务：查数据 → 计算 → 通知（必须依次走 3 个工具）
curl "http://localhost:8080/api/react-agent/run?task=查一下8月的销售情况，算一下平均每单金额，然后把结果通知张三"
# 期望：completed=true, stepsUsed≥3；日志依次出现 querySalesStats → calculate → sendNotification

# ② 条件分支任务（ReAct 的灵魂：下一步取决于观察结果）
curl "http://localhost:8080/api/react-agent/run?task=查ORD-142到哪了，如果还没签收就通知张三预计到达时间，已经签收就不用通知"
# 期望：模型根据订单状态决定"通知"还是"不通知" —— 换成 ORD-145（已签收）再跑一次对比

# ③ 越界任务：工具解决不了，应诚实说明
curl "http://localhost:8080/api/react-agent/run?task=帮我预测下个月的股价"
# 期望：completed=true 但答案是"做不到"类诚实回复，不编造
```

---

## 3. 自检标准（不通过不许进 Day59）

- [ ] 任务①：日志里**依次**看到 3 个 Action，`stepsUsed ≥ 3`；
- [ ] 任务②：**改订单号跑两次，Agent 做了不同的事**（未签收→通知；已签收→不通知）—— 这是 ReAct 分支能力的铁证；
- [ ] 任务③：诚实说做不到，不编造；
- [ ] 人为把 `MAX_STEPS` 改成 1 再跑任务①，应触发熔断返回"需人工介入"（验证熔断真的生效，然后改回 8）；
- [ ] 能口头讲清：为什么必须 `internalToolExecutionEnabled(false)`；循环的三要素（执行工具/重建 Prompt/终止判断）。

---

## 4. 关键踩坑清单（必背）

1. **忘设 `internalToolExecutionEnabled(false)`** → Spring AI 内部把工具执行掉，你的循环永远只看到最终答案，熔断形同虚设。**这是今天第一大坑。**
2. **复用旧 Prompt 对象** → 每轮必须用 `conversationHistory()` **重建** Prompt，消息不可变。
3. **没有步数熔断** → 模型死循环时 token 无限烧。MAX_STEPS 是 Day57 数学（95%¹⁰≈60%）的直接对策。
4. **熔断后抛异常** → 应返回结构化的"未完成+人工介入"，不是 500。
5. **以为 Thought 必须可见** → Function Calling 模式下"思考"隐含在工具调用决策里；system prompt 里让它"先说思路"可增强可观察性，但别强求格式化解析文本（那是文本协议 ReAct 的做法，更脆弱）。
6. **工具挂太多** → Agent 场景更要按任务域收窄工具集（Day55 规范），模型选择面越小越稳。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：把 Agent 循环从"黑盒"变成"你写的代码" —— 显式 ReAct（思考→行动→观察）、步数熔断、条件分支自主决策。核心循环不到百行，但控制权（步数/可见性/终止）全在你手里 —— 这就是生产级 Agent 和玩具的分界线。

**明日（Day59）**：Agent 记忆体系 —— 短期记忆（对话内上下文）你已经有了；今天补**长期记忆**：用户偏好、历史结论跨会话记住，存向量库按需检索。你会发现：长期记忆 = 向量库（第 5 周）+ 会话摘要，你已握着 80% 的积木。

---

*文档生成日期：2026-09-08 · 技术版本：Spring AI 1.0.3 / Java 21*
