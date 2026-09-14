# Day 61：多 Agent 协作 —— 角色分工、消息传递、诚实谈局限

> 所属阶段：第二阶段 · 第 9 周「Agent 基础原理与落地」（Day57-63）
> 今日主题：两个角色 Agent（需求分析师→开发工程师）流水线协作；拆分 Agent 的真价值 vs 伪价值
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day58 ReAct 引擎 + Day60 的 system prompt 可定制重载（今天的落点）

---

## 0. 今天要解决的问题

一个 Agent 干所有角色的活，会遇到：

```
一个 Agent = 分析 + 开发 + 测试 + 排障
   ↓ 后果
① system prompt 又长又杂 → 每个角色都被稀释（Day51 的 description 稀释问题，角色级重演）
② 工具面大杂烩 → 分析师拿着发通知的工具，误触发风险高（Day55 规范被破坏）
③ 输出风格互相打架 → 前一步的分析格式后一步根本用不了
```

今天把"角色"拆开：**需求分析师 Agent → 开发工程师 Agent**，流水线交接。学完你要能回答那个更重要的问题：**什么时候真的需要多 Agent？**

---

## 1. 核心知识点（40 分钟）

### 1.1 多 Agent 协作的三种模式

| 模式 | 怎么运作 | 成本/可控性 | 今天的评价 |
|---|---|---|---|
| **① 流水线（Handoff）** | A 的输出 → B 的输入，代码写死顺序 | ✅ 可控可测可审计 | ✅ **今天用**，90% 的"多 Agent 需求"到这就够了 |
| **② 调度器（Orchestrator）** | 一个协调 Agent 动态决定调谁、调几次 | 中；动态但不可预测 | 步骤真不可预知时才用（Day57 判据） |
| **③ 群聊（Group Chat）** | 多个 Agent 互相讨论多轮达成共识 | ❌ 成本爆炸、发散难收敛 | Demo 炫酷，生产慎用（AutoGen/CrewAI 的卖点，也是坑） |

**和 Day57 的 Workflow vs Agent 一脉相承：角色顺序能写死的用流水线（你是 Java 工程师，写编排是主场）；顺序真动态的才上调度器。**

### 1.2 拆分 Agent 的真价值 vs 伪价值（今天的判断题）

| ✅ 真价值 | ❌ 伪价值 |
|---|---|
| **prompt 聚焦**：每个角色只装自己的 SOP，不被稀释 | "像人类团队一样协作"（表演，模型不需要拟人化） |
| **工具面收窄**：分析师不该有发通知的工具（最小权限，Day55） | "多个 Agent 更聪明"（同一批模型，数量≠智能） |
| **独立评估/替换/复用**：工程师 Agent 可以单独换版本 | "显得架构高级"（面试讲不出协作收益就是炫技） |
| **上下文隔离**：分析师的中间草稿不污染开发环节 | |

**一句话判据：拆出去的每个角色，能不能独立回答"它比混在一个 prompt 里好在哪"。答不出 = 别拆。**

### 1.3 Agent 间交接：结构化是生命线

流水线最大的坑：**A 的输出是散文，B 拿到没法用**。

```
❌ 交接："我觉得这个需求大概要做登录，然后可能还要个列表……"  → B 无法执行
✅ 交接："## 功能点清单
         F1: 用户登录 — 支持账号密码，失败3次锁定 — 验收：登录成功跳首页
         F2: 订单列表 — 分页展示 — 验收：每页10条可翻页"
```

**两个强制手段：**
1. **输出格式写进角色的 system prompt**（分析师必须按"功能点清单"模板输出）
2. **交接前代码校验**（产出不含"功能点清单"章节 → 视为不合格，重试或终止，绝不带着散文往下走）

这就是 Day54"中间结果引用传递"的角色级复用：**交接的不是聊天记录，是结构化工作产物。**

### 1.4 诚实的局限性（面试必谈）

1. **可靠性相乘**：两个各 85% 可靠的 Agent 串起来 ≈ 72%。多 Agent 不是提升可靠性，是放大衰减 —— 每个交接点都是新的失真点。
2. **成本线性叠加**：N 个角色 = 至少 N 次 LLM 调用，还没算每个角色内部的工具循环。
3. **调试难度陡增**：问题出在 A 还是 B？需要全链路留痕（每个 Agent 的输入输出都打日志，Day54 的 `[step]` 习惯）。
4. **大部分需求不需要**：先问"一个 Agent + 更好的工具和 SOP 行不行"，答案通常是行。

---

## 2. 实操作业（80 分钟）

> 目标：两个角色 Agent 流水线 —— 输入一句需求，分析师出《功能点清单》，工程师基于它出《技术方案》。

### 步骤 1：角色 Agent 配置（新建 `controller/MultiAgentController.java`）

两个角色都复用 `SimpleReActAgent`（Day60 的 prompt 重载），不挂工具（纯角色推理，成本最低；要挂也只挂该角色的工具 —— 工具面收窄原则）：

```java
package com.example.springai.controller;

import com.example.springai.agent.AgentResult;
import com.example.springai.agent.SimpleReActAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 Agent 协作（Day61）：需求分析师 → 开发工程师 流水线。
 * 复用 SimpleReActAgent 引擎，角色差异只在 system prompt（Day60 的重载落点）。
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    /** 角色 1：需求分析师 —— 输出格式是结构化交接的生命线 */
    private static final String ANALYST_PROMPT = """
            你是需求分析师。把用户需求拆解为功能点清单，严格按以下格式输出：
            ## 功能点清单
            F1: <功能描述> — 验收标准：<可验证的标准>
            F2: ...
            要求：3-5 个功能点，不写代码，不写技术选型，只写"做什么"和"怎么算做好"。
            """;

    /** 角色 2：开发工程师 —— 只基于分析师的结构化产出工作 */
    private static final String DEVELOPER_PROMPT = """
            你是资深开发工程师。基于给你的《功能点清单》输出技术方案，严格按以下格式：
            ## 技术方案
            ### F1 <功能点>
            - 实现思路：...
            - 涉及模块/表：...
            ### F2 ...
            ## 风险与工作量
            - 风险：...
            - 总工作量估算：...人天
            """;

    private final SimpleReActAgent analyst;
    private final SimpleReActAgent developer;

    public MultiAgentController(ChatModel chatModel) {
        // 角色差异只在 system prompt；分析师不挂工具（工具面收窄原则）
        this.analyst = new SimpleReActAgent(chatModel, ANALYST_PROMPT);
        this.developer = new SimpleReActAgent(chatModel, DEVELOPER_PROMPT);
    }

    @GetMapping("/develop")
    public Map<String, Object> develop(@RequestParam String requirement) {
        long start = System.currentTimeMillis();

        // ===== Agent 1：需求分析师 =====
        AgentResult analysis = analyst.run("分析以下需求并输出功能点清单：\n" + requirement);
        log.info("[multi-agent] 分析师完成，steps={}，产出长度={}", analysis.stepsUsed(),
                analysis.answer() == null ? 0 : analysis.answer().length());

        // ===== 交接校验（结构化是生命线）：不含"功能点清单"章节 = 不合格，终止而非带病传递 =====
        if (!analysis.completed() || analysis.answer() == null
                || !analysis.answer().contains("功能点清单")) {
            return Map.of(
                    "status", "ANALYST_FAILED",
                    "reason", "分析师产出不合格（缺功能点清单章节或被熔断），流程终止，需人工介入",
                    "rawOutput", String.valueOf(analysis.answer()));
        }

        // ===== 消息传递：交接的是结构化产物，不是散文 =====
        // ===== Agent 2：开发工程师 =====
        AgentResult dev = developer.run("基于以下需求分析输出技术方案：\n" + analysis.answer());
        log.info("[multi-agent] 工程师完成，steps={}，总耗时={}ms", dev.stepsUsed(),
                System.currentTimeMillis() - start);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", dev.completed() ? "SUCCESS" : "DEVELOPER_EXHAUSTED");
        result.put("requirementAnalysis", analysis.answer());
        result.put("techDesign", dev.answer());
        result.put("analystSteps", analysis.stepsUsed());
        result.put("developerSteps", dev.stepsUsed());
        return result;
    }
}
```

> 需要 `import lombok.extern.slf4j.Slf4j;` 并在类上加 `@Slf4j`（日志是全链路留痕的最低要求）。

### 步骤 2：验证（3 个需求）

```bash
# ① 正常需求（完整两棒接力）
curl -G "http://localhost:8080/api/multi-agent/develop" \
  --data-urlencode "requirement=给公司内部做一个会议室预约系统，支持按天预约、避免冲突、取消预约"

# ② 模糊需求（分析师应该追问边界或给出假设，工程师方案不至于瞎编）
curl -G "http://localhost:8080/api/multi-agent/develop" \
  --data-urlencode "requirement=做个抽奖功能"

# ③ 观察交接质量：requirementAnalysis 必须是"F1/F2..."格式（不是散文），techDesign 的每个小节必须对应 F 编号
```

### 步骤 3（可选加分）：跑两遍比稳定性

同一需求跑 2 次，对比 `analystSteps`/`developerSteps` 和功能点数量。差异大 → 角色定义（prompt）还不够硬，回去加约束。

---

## 3. 自检标准（不通过不许进 Day62）

- [ ] 场景①：`requirementAnalysis` 是 **F1/F2 编号 + 验收标准**格式（不是散文）；
- [ ] `techDesign` 的每个小节标题**对应 F 编号** —— 交接真的被工程师"接住"了；
- [ ] 交接校验生效：能讲清为什么"产出不合格就终止"而不是"凑合往下传"（带病传递 = 后面全白干）；
- [ ] 能口头讲清：三种协作模式、拆 Agent 的真价值判据、可靠性相乘（85%×85%≈72%）；
- [ ] 能回答："你的场景为什么不用调度器/群聊？"（答：角色顺序固定 → 流水线最可控最便宜）。

---

## 4. 关键踩坑清单（必背）

1. **交接散文** → 工程师拿到一团文字没法结构化响应。输出模板写死在角色 prompt + 代码校验。
2. **带病传递** → 分析师被熔断/产出不合格还往下传，后面全白干。交接校验不过就终止，宁可人工介入。
3. **所有角色共享工具面** → 分析师拿着发通知的工具，误触发风险（Day55 规范）。每个角色只挂自己的工具。
4. **为拆而拆** → 拆完说不出每个角色的独立收益，就是表演。先问"一个 Agent + 好工具行不行"。
5. **没有全链路日志** → 出了问题不知道断在哪棒。每个 Agent 的输入长度/步数/耗时都打日志。
6. **期望多 Agent 提升可靠性** → 相反，交接点是新失真点。多 Agent 换来的是**可维护性和职责清晰**，不是准确率。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：第一个多 Agent 流水线 —— 角色拆分（真价值：prompt 聚焦 + 工具面收窄 + 独立评估）、结构化交接（模板 + 代码校验 + 带病传递熔断）。最重要的收获是那个判断框架：**90% 的"多 Agent 需求"用流水线就够，剩下的先怀疑是不是单 Agent 没调好。**

**明日（Day62）**：Agent 落地的常见坑 —— 循环调用、任务跑偏、幻觉严重，以及**干预与人工介入机制**。作业：给之前的 Agent 加上异常熔断，避免死循环和无限调用。Day58 你已经做了步数熔断，明天把它升级成完整的"异常熔断 + 人工介入"体系。

---

*文档生成日期：2026-09-08 · 技术版本：Spring AI 1.0.3 / Java 21*
