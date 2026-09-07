# Day 47：RAG 工程化 —— 效果评估（构建测试集，量化评估 RAG）

> 所属阶段：第二阶段 · 第 7 周「RAG 工程化适配」（Day43–49）
> 今日主题：把「感觉好了很多」变成「有数字证明好了很多」—— 建评测集，量化 Recall@N / 准确率 / 幻觉率
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day46 排查方法论；复用已有 `ChunkTest`（召回评估）+ `RagTestCorpus`（细粒度语料）

---

## 0. 今天要解决的问题

你做了一堆优化（分块、混合检索、重排、量化、改写、多路由……），老板/同事问：

```
「你说优化后效果好多了？」
「好多少？有数据吗？」
「Recall 从多少到多少？准确率呢？会不会瞎编？」
```

**答不上来 = 之前的优化说不清价值。** RAG 最怕「自我感觉良好」，但根本没法证明。Day47 就是补上这个空白——**用量化数字说话**。

而且你已经比大多数人走前一步了：`ChunkTest` 里已经有 **40 条评测用例 + 5 个版本 + Recall@5 + 按题型拆分 + 未命中日志**。这本身就是评估系统的雏形。今天把它升级成**完整、可持续**的 RAG 评测体系。

**核心心法：评估不是"为了交差"，而是你每做一个优化，都能用同一套数据证明它值不值。** 这是高级工程师和普通开发的分水岭。

---

## 1. 前置回顾 —— 你已经有半套评测了

先看清楚你已有的资产，别重复造轮子：

**`RagTestCorpus.java`（评测语料）**—— 已经设计得很好：
- 每个「事实」单独成一个 chunk（一个订单 / 一个错误码 / 一个规格 / 一条政策各一块）
- 每个 chunk 有 metadata.factId，作为 ground-truth 的唯一锚
- 有 `corpus` 标记做幂等（避免重复灌库）
- 铺了大量相似 distractor —— 用来挑战纯向量检索

**`ChunkTest.java`（召回评估）**—— 已经具备：

| 要素 | 现状 |
|---|---|
| 40 条用例 | ✅ 分语义(15)/精确(15)/边界(10) 三类 |
| 5 个版本 | ✅ V0 纯向量 → V4 多路召回 |
| 指标 | ✅ **Recall@5**（命中 top5 即算对） |
| 输出 | ✅ 总表 + 分题型 + 未命中明细 |

**它缺的（今天的重点）：**
- ❌ 只测了「检索召回」这一半。**没测「生成答案」** —— 答案准不准？幻觉率高不高？这是 RAG 的另一个半场。
- ❌ 召回指标只有 Recall@5，缺 @1 / @3（往往 @1 才是用户真正看到的）。
- ❌ 一次性的。没形成「可重复跑、可回归」的评估工程。

> 一个关键认知：**召回好 ≠ 答案好**。RAG = 检索 + 生成。`ChunkTest` 证明了检索环节（召回），但生成环节（答案质量、幻觉）是另一个独立要评估的事情。你今天补的就是这一块。

---

## 2. 核心知识点（40 分钟）

### 2.1 先懂 RAG 评估的「三个层次」，别只盯着一个指标

| 层次 | 指标 | 测什么 | 你现在的状态 |
|---|---|---|---|
| **检索层** | Recall@K / Precision@K | 「该召回的召回了吗？」 | ✅ ChunkTest 已覆盖 |
| **生成层** | 准确率 / 幻觉率 / 引用率 | 「答案对吗？编了吗？」 | ❌ 今天补 |
| **业务层** | 用户满意度 / 任务完成率 | 「真实用户用起来爽不爽？」 | 后面 Day78 监控再补 |

**黄金法则：评估先分层。** 你不能用「用户觉得好不好」去推导「检索哪错了」，也不能用「召回率」去证明「答案质量」。各算各的，再关联。

### 2.2 检索层指标精讲（Recall 家族）

你在 `ChunkTest` 用的 Recall@5，其实很关键。它的定义：

```
Recall@K = 标准答案的 chunk 是否出现在 召回结果 top-K 里 的题目占比
         = (对 N 道题，其中 ground-truth 被召回的比例)
```

- **Recall@1**：最严 —— 正确答案必须是**第 1 个**。**这才是用户真正"看到"的答案质量**（因为往往只按第 1 个块来回答）。优化目标是让 Recall@1 高。
- **Recall@3 / @5**：宽松些，看「前几名里有没有它」—— 反映召回能力，「能找到但不一定排第一」。
- **Precision@K**：召回结果里，有多少是真正相关的？（你语料里铺了 distractor，这个指标很有用。）

**实践建议**：`ChunkTest` 现在只有 @5。Day47 让你至少加上 **@1 和 @3** —— 因为 @1 才是用户真实体验的"那一条"，@5 掩盖了排序问题。

### 2.3 生成层指标精讲（准确率 / 幻觉率）—— 今天的难点

生成层的评估难在**「答案对错怎么判」**。两种主流方法：

**① 人工判据（Ground truth 比）** —— 最可靠
建一份「问题 → 标准答案要点」（golden set），让模型生成后，人工/规则看答案**是否覆盖了要点 + 是否编造**。
- **准确率** = 答案正确 / 总题数
- **幻觉率** = 答案**含糊了、补充了资料没有的东西** / 总题数

**② LLM-as-Judge（大模型当裁判）** —— 规模化
另一个大模型（如 GPT / Claude）去做裁判，给它「问题、资料、答案」让它打分（0/1 或 1-5）。省人工，但裁判模型本身可能不靠谱。

**Day47 用①（人工/规则判据）**，因为可控、确定性、不依赖额外模型。②你 Day71+ 讲到「评估系统」再上。

### 2.4 生成层怎么「判」对错 —— 关键：让它「可判」

**让模型输出结构化，判据才好写。** 这是评估生成质量的核心技巧：

让生成的答案**绑定来源**。RAG 提示词里要求：
```
必须回答：结论 + 【依据：来源[资料N]】
没有依据时，回答：【无法回答】
```
这样一来：
- 答案带「依据」→ 能验证它有没有真的照资料答（答案准不准、有没有编造）
- 答案说「无法回答」→ 这种是**安全拒答**，不算错

**判断幻觉的黄金标准**：答案里的关键信息，**能否在给定的【资料】里找到原文支持**。找到了 → 真实依据；找不到 → 幻觉。让模型输出依据引用，这一步就能自动/半自动判。

### 2.5 评估工程化的三个原则（让评测可持续，不是跑一次就扔）

1. **同一套语料、同一套用例，必须可重复跑**：你已经用 `corpus` 幂等标记实现了一半。目标：`mvn test` 就能跑，输出稳定。
2. **结果是「可对照的」**：任何优化，都跑同一份测试集，对比**优化前 vs 优化后**，看数字涨没涨。这才叫「用数据证明优化」。你 V0→V4 就是这么做的。
3. **评估本身的成本要控制**：RAG 评测最贵的是 LLM 生成（每道题要花钱）。Day47 先聚焦「召回评估」（不花钱，纯向量检索），生成评估用少量题做完。

---

## 3. 实操作业（80 分钟）

> 目标：① 给 `ChunkTest` 的检索评估**加上 @1 和 @3**；② 补一个小的「生成评估」，算准确率和幻觉率。为控制成本，生成评估用少量用例（8 道）。

### 步骤 1：给 `ChunkTest` 加 @1 / @3（改 `ChunkTest.java`）

你现在的评估是"命中 top5 就算对"（`RecallResult.overall`）。改成**同时统计 @1 / @3 / @5**。核心验证逻辑在 `evaluate` 里：不再用「anyMatch」，而是**看命中的位置**。

在 `evaluate` 方法里，把判断改成记录命中位置：

```java
// 改造 evaluate：返回 @1 / @3 / @5 三个命中数
private RecallResult evaluate(Version v, List<TestCase> cases) {
    int hit1 = 0, hit3 = 0, hit5 = 0, errors = 0;
    Map<TestType, int[]> per = new EnumMap<>(TestType.class); // [hit1, hit3, hit5, total]
    List<String> missed = new ArrayList<>();

    for (TestCase tc : cases) {
        int[] h = per.computeIfAbsent(tc.type(), k -> new int[4]);
        h[3]++;  // total
        try {
            List<Document> top = v.retriever().apply(tc.question());   // 拿到 top-N
            // 找到 ground-truth 在 top 里的位置
            int pos = -1;
            for (int i = 0; i < top.size(); i++) {
                if (tc.expectedFactId().equals(top.get(i).getMetadata().get("factId"))) {
                    pos = i;   // 0-based
                    break;
                }
            }
            if (pos >= 0) {
                hit5++;
                h[2]++;
                if (pos < 3) { hit3++; h[1]++; }
                if (pos < 1) { hit1++; h[0]++; }
            } else {
                missed.add(tc.question() + "  (期望 " + tc.expectedFactId() + ")");
            }
        } catch (Exception e) {
            errors++;
            missed.add(tc.question() + "  [异常] " + e.getClass().getSimpleName());
        }
    }

    // 按题型折算 @1/@3/@5 百分比，写进 RecallResult
    ...
}
```

> 这样你就能看到一个关键真相：**V4 的 Recall@5 可能是 90%，但 Recall@1 可能只有 50%** —— 说明「能找到、但排不到第一」，这往往是排序/重排的问题，而不是召回能力。这是很多 RAG 团队最容易忽视的。

### 步骤 2：补一个生成评估（新建 `test/.../evaluation/RagGenerationEval.java`）

用少量用例（8 道），测「答案准不准 + 幻觉高不高」。核心是**让模型输出结构化、可判**，然后规则判。

```java
package com.example.springai.evaluation;

import ...;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagGenerationEval {

    @Autowired ChatClient chatClient;      // 你的 RAG chatClient（带 QuestionAnswerAdvisor）
    @Autowired VectorStore vectorStore;
    @Autowired MultiRecallRagService multiRecall;

    // 8 道题：4 道有答案的，2 道跨文档，2 道「资料外」的（测拒答/幻觉）
    record GenCase(String question, String[] mustContain, boolean hasAnswer) {}
    static final List<GenCase> CASES = List.of(
        new GenCase("蓝牙耳机 X1 续航多少？", new String[]{"8", "小时"}, true),
        new GenCase("退货邮费谁出？", new String[]{"问题","商家"}, true),
        new GenCase("工作时间是几点到几点？", new String[]{"9:00","18:00"}, true),
        new GenCase("年假满一年有几天？", new String[]{"5"}, true),
        new GenCase("怎么申请加入金卡会员？", new String[]{"5000"}, true),
        // 资料外：看会不会让模型拒答（不编造）
        new GenCase("今天股票大盘多少点？", new String[]{}, false),
        new GenCase("怎么预约NBA球赛门票？", new String[]{}, false)
    );

    @Test
    void 生成评估() {
        int correct = 0, hallucinated = 0, rejected = 0, total = 0;
        for (GenCase c : CASES) {
            total++;
            String answer = ask(c.question());
            System.out.println("问题: " + c.question() + "\n答案: " + answer);
            if (c.hasAnswer()) {
                // 准不准：必须包含关键词（简化判据）
                boolean ok = java.util.Arrays.stream(c.mustContain())
                        .allMatch(answer::contains);
                if (ok) correct++; else System.out.println("  ❌ 答案不准，缺关键词");
            } else {
                // 资料外：应拒答。若它编了内容 → 幻觉
                boolean rejectedOk = answer.contains("无法回答") || answer.contains("未收录") || answer.contains("抱歉");
                if (rejectedOk) rejected++;
                else { hallucinated++; System.out.println("  ❌ 资料外问题未拒答，疑似幻觉: " + answer); }
            }
        }
        System.out.printf("生成评估: 共%d题 | 准确率=%.0f%% | 拒答率=%.0f%% | 幻觉率=%.0f%%%n",
                total, correct*100.0/total, rejected*100.0/total, hallucinated*100.0/total);
    }

    private String ask(String q) {
        return chatClient.prompt().user(q)
                .call().content();
    }
}
```

> 关键点：**资料外的问题（2 道）**——如果模型不拒答而是编造答案，就是幻觉。这是 RAG 最重要的安全指标。你没写这 2 道，准确率再高也说明不了"不瞎编"。

### 步骤 3：跑一遍，记录一个「评估基线」

把 `ChunkTest`（召回 @1/@3/@5）+ `RagGenerationEval`（生成准确率/幻觉率）各跑一次，记录结果。**这就是你项目的「质量基线」，以后每做一个优化都拿它对照。**

---

## 4. 自检标准（不通过不许进 Day48）

- [ ] `ChunkTest` 能输出 @1 / @3 / @5，而不仅是 @5；
- [ ] `RagGenerationEval` 能算出「准确率」和「幻觉率」（含资料外拒答的判断）；
- [ ] 能说出 Recall@1 和 Recall@5 的差异，以及为什么 @1 更贴近用户真实体验；
- [ ] 能口头讲清 RAG 的「检索层 / 生成层 / 业务层」三层评估各测什么，以及召回好≠答案好；
- [ ] 有了这一个「基线」，能说明以后怎么用它对照优化效果。

---

## 5. 关键踩坑清单（必背）

1. **只测召回、不测生成**：`ChunkTest` 证明「检索到」，但「答案对不对」是另一个半场。这是 RAG 团队最常见的盲区。
2. **只看 Recall@5**：@5 掩盖排序问题。**@1 才是用户"看到的那一条"**。找不到的说明召回弱，排不到第一的说明排序/重排弱 —— 两种病治起来完全不同。
3. **幻觉率要专门用「资料外」问题测**：没见过的问题，模型该拒答而非编造。不测这批题，你永远不知道幻觉多严重。
4. **评估成本失控**：生成评估每道题花钱。先用少量题跑通逻辑，规模化交给 Day71+ 的 LLM-as-Judge / 评估系统。
5. **语料污染**：你 `RagTestCorpus.seed()` 会 `DELETE FROM vector_store`（全表清空）—— 生产库千万别这么干，会删掉别人的数据。评估要**在独立环境/独立 corpus 标记**下进行，别污染真实知识库。
6. **判据太严苛**：关键词匹配做「简化判据」会误判同义表达。Day47 先接受它（快、确定），进阶用 LLM-as-Judge。

---

## 6. 今日小结 + 明日预告

**今天你学会了**：把「感觉好」炼成「数字」—— 建立评测集，量化三层指标（检索 Recall@1/3/5、生成准确率/幻觉率）。你已经比别人强了：`ChunkTest` 早就埋好了语义/精确/边界三维的评估骨架，今天只是打通「生成评估」这另一半。

**明日（Day48，第 7 周末；实际是 Day47 的收官）** —— 按学习计划，第 7 周结束有个**周末作业：完善知识库权限管理 + 批量上传 + 效果评估报告**。即：把 Day43-47 的能力**整合成一份《知识库项目效果评估报告》**，作为第 7 周（也是第二阶段「核心能力期」）的交付物。会教你怎么把这些散落的评估结果，串成一份能讲给老板/面试官听的完整报告。

---

*文档生成日期：2026-08-28 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
