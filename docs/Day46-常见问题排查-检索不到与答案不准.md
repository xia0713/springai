# Day 46：RAG 工程化 —— 常见问题排查（检索不到 / 答案不准 / 幻觉严重）

> 所属阶段：第二阶段 · 第 7 周「RAG 工程化适配」（Day43–49）
> 今日主题：教你一套「定位 RAG 百病」的三板斧 —— 先分清是「没检索到」还是「检索到但答得烂」，再对症下药
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day43 批量、Day44 增量、Day45 权限隔离已跑通

---

## 0. 今天要解决的问题

你做知识库问答，一定会天天撞上这几句反馈：

```
用户：「我明明把文档传上去了，怎么问不出来？」  → 检索不到
用户：「答是答了，可答的完全不是我的文档里的？」  → 答案不准
用户：「这回答一看就是它自己编的！」         → 幻觉严重
```

新手一遇到，第一反应是「再调调 prompt」「换个模型」「加大 topK」—— 全瞎撞，浪费半天。**真正的高手是先定位，再改。** 今天教你把这三个问题，用一套标准流程定位到病灶。

**核心心法一句话：RAG 的一切问题，先分成「检索环节」和「生成环节」两类。是检索的问题就修检索，是生成的问题就修生成，绝不混着调。**

---

## 1. 前置回顾 —— 你现在的链路（拿它当排查地图）

你 Day36-42 已经攒了一套完整的多路召回链路（`MultiRecallRagService`），今天就拿它当排查路线图：

```
用户问题
  ↓
① 查询改写  QueryRewritingService.rewrite()   → 用户口语 → 检索词
  ↓
② 多路召回  hybridSearch + vectorStore.similaritySearch  → 并行跑
  ↓
③ 去重合并  Document::getId 去重
  ↓
④ 重排序    RerankingService.rerank(question, candidates, 5)  → 取 top5
  ↓
⑤ 生成      chatClient.prompt(user(拼了 context 的 prompt)).call()
  ↓
答案
```

**排查的第一个动作，就是把「①到④这一步的结果」和「⑤的最终答案」分开看** —— 因为这两段的病根完全不同。

---

## 2. 核心知识点（40 分钟）

### 2.1 先学会「看检索结果」，别急着看答案

**这是整个排查方法论的第 0 步，也是最容易漏的一步。**

绝大多数新手的问题是：直接看最终答案好不好，然后瞎调。但你要知道——**「检索到什么」是「生成什么」的唯一依据。** 所以第一步永远是：**单独把检索结果 dump 出来看**。

你已经有一半了：`MultiRecallRagService.retrieve(question)` 就只干「召回」不生成，正好拿来看。**但你现在没暴露它。** 后边作业会给它加一个 debug 接口。

看检索结果时，重点盯这两个信号：

| 观察点 | 健康 | 有问题 |
|---|---|---|
| **结果是空/很少？** | top5 基本满 | 空 or 只有 1-2 条 → 检索不到 |
| **结果的相似度分数？** | 分数都不低（>0.6） | 分数很低（<0.4）→ 检索到了但匹配弱 |

**发现是「空 / 很少 / 分数低」→ 病根在检索环节（①②③④）；发现「结果看起来都对，但答案胡说」→ 病根在生成环节（⑤）。** 这一刀切下去，问题直接缩小一半。

### 2.2 检索不到 —— 从「格式」到「语义」层层排查

「检索不到」按**由浅入深**四层查，命中哪层修哪层：

**第 1 层：先确认「真有数据吗」—— 路由/权限/异步 的坑（你这两天刚碰到过）**

别一上来怀疑算法。先问三件事：
- **数据入库没？** 你 Day43 批量上传是**异步**的，`/batch/upload` 返回后任务**还没跑完**。立刻检索当然空。→ 查 `/batch/{taskId}` 看 `status` 是不是 `SUCCESS`。
- **权限过滤挡住了？** 你 Day45 加了 `owner` 过滤。如果拿 `currentUser=zhangsan` 查，但文档是 `public` 或 `lisi` 上传的，**检索结果就是空**（但这是对的！）。→ 看检索接口有没有传对 owner。
- **路由表对不对？** 你有多路召回/混合检索，有没有用对那条接口？

> 这一层最像「玄学」，其实是**工程问题**：数据没到、被权限挡、走错门。**排查永远从这层开始，别一上来就怀疑 embedding。**

**第 2 层：查询词和文档「对不上」—— 用查询改写 (Day39)**

用户问的是口语（「报销要多久」），文档里写的是书面语（「报销周期」）。向量相似度可能不达标。→ **用 `QueryRewritingService.rewrite()` 把口语改写成语义更贴近文档的查询词**，再检索。你已经有这个能力了，改完再查一次看分数会不会上来。

**第 3 层：topK / 阈值太严 —— 调召回参数**

查 `similarityThreshold` 是不是设太高了（你 `MultiRecallRagService` 里有的路用 0.4，有的 0.6）。`topK` 太小（取 10 但进 `rerank` 只有 5）。→ 先放宽 `threshold`（0.4），`topK` 加大，看能不能召回更多。

**第 4 层：分块策略 (Day34-35) —— chunk 太大/太小**

这是最深的坑。你 Day36 已经研究过语义分块、父子分块了。**如果内容明明在文档里但检索不到，多半是分块问题**：
- chunk 太大（一个块塞了整节）→ 向量焦点散，用「句中一点」去匹配整块，命中弱
- chunk 太小 → 语义被切碎
- **换个检索粒度**：用 Day35 的「父块检索、子块回答」或「子块检索、父块上下文化」

### 2.3 答案不准 —— 检索到了，但生成没用对

如果「④重排序出来的是对的，但⑤答案不对」，病根在生成环节。三宗罪：

**① 上下文没喂好**：`rank` 后可能把**不相关**的块也拼进 `context` 了（top5 里后 3 条是噪声）。→ 只喂「分数靠前 + 确实相关」的块，或把 `rerank` 后的分数作为权重/过滤。

**② prompt 指令弱**：你生成的 prompt 只写了一句「基于资料回答」。→ 用 Day22-25 的结构化 prompt：明确「只依据【资料】，禁止补充」「关键结论标 `[资料N]`」「没有就拒答」。

**③ 上下文被截断/太长**：top5 全塞进去，可能超模型窗口或被中间内容干扰。→ 控制 `topK`，或按相关性排序、只取前 2-3 块。

### 2.4 幻觉严重 —— 生成环节没守住

和「答案不准」类似，但更极端：模型**编造了资料里没有的内容**。破法三招（你 Day24-25 学过）：

1. **强制对应**：prompt 里写「你回答的每句话必须能在【资料】里找到依据，找不到就回答『知识库中未收录』」
2. **拒答规则**：明确「当资料不足以回答时，必须拒答，不得猜测」。这是 RAG 最重要的防线。
3. **约束来源**：要求「关键结论后标注来源编号」，甚至让模型输出时把来源带上 —— 一旦要求溯源，模型编造的成本变高，幻觉自然会压制。

### 2.5 总结：一张排查决策表

| 症状 | 先查 | 动手改 |
|---|---|---|
| 结果为空 | ①数据/异步/权限/路由 | 确认任务状态、owner、接口对不对 |
| 结果少/分数低 | ②③④ | 查询改写 → 放宽阈值/topK → 换分块 |
| 结果对但答错 | ⑤ | 上下文清洗、结构化 prompt |
| 答出资料没有的 | ⑤ | 强制溯源、拒答规则、要求标来源 |

---

## 3. 实操作业（80 分钟）

> 目标：给 `MultiRecallRagService` 加一个 **debug 检索接口**，让你能看到「每一步到底召回、重排了什么」。这是排查的灵魂——**看得见，才能修得动。**

### 步骤 1：给 `MultiRecallRagService` 加「召回调试」方法

在 `MultiRecallRagService` 里加一个方法，把 `retrieve()` 里的中间结果暴露出来（含分数、来源）：

```java
/** Day46: 召回调试 —— 返回每一步的中间结果，供排查怎么调 */
public Map<String, Object> debugRetrieve(String question) {
    Map<String, Object> debug = new LinkedHashMap<>();
    debug.put("query", question);

    // ① 查询改写结果
    try {
        debug.put("rewrittenQueries", rewriter.rewrite(question));
    } catch (Exception e) {
        debug.put("rewrittenQueries", "改写失败: " + e.getMessage());
    }

    // ② 混合检索 + 多路召回（复用 retrieve 内部的召回逻辑，这里重新跑一遍展示）
    List<Document> rawHybrid = hybridSearch.hybridSearch(question);
    debug.put("hybridRecallCount", rawHybrid.size());
    debug.put("hybridRecallScores", rawHybrid.stream()
            .map(d -> d.getMetadata().getOrDefault("distance", "?"))
            .toList());

    // ④ 重排后 top5（含分数）
    List<Document> reranked = reranker.rerank(question, rawHybrid, 5);
    debug.put("rerankTop5", reranked.stream()
            .map(d -> Map.of(
                    "text", d.getText().substring(0, Math.min(120, d.getText().length())) + "...",
                    "score", d.getMetadata().getOrDefault("distance", "?"),
                    "source", d.getMetadata().getOrDefault("source", "?"),
                    "docId", d.getMetadata().getOrDefault("docId", "?")
            ))
            .toList());

    return debug;
}
```

> 这一套就是「排查三板斧」的落地：**改写是否有效、混合召回多少条、重排 top5 是什么、分数多少、来源是什么。** 你一看这几个数字，就知道问题卡在哪一环。

### 步骤 2：加一个 debug 控制器（新建 `controller/RagDebugController.java`）

```java
package com.example.springai.controller;

import com.example.springai.service.MultiRecallRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class RagDebugController {

    private final MultiRecallRagService multiRecallRagService;

    /**
     * 排查召回：看清每一步。
     * GET /api/debug/retrieve?question=报销要多久&currentUser=zhangsan
     */
    @GetMapping("/retrieve")
    public Map<String, Object> debugRetrieve(@RequestParam String question) {
        return multiRecallRagService.debugRetrieve(question);
    }
}
```

### 步骤 3：用 debug 接口做一次真实的排查演练

启动项目，传一个「之前答不好」的问题，看每一步：

```bash
curl "http://localhost:8080/api/debug/retrieve?question=报销要多久"
```

**重点看两个信号**（对应 §2.1）：
1. `hybridRecallCount` 是不是 0 / 很小？→ 是，检索环节问题，往下查 §2.2
2. `rerankTop5` 的 `score` 是不是都很低？→ 是，匹配弱，改查询改写/阈值
3. `rerankTop5` 的内容是不是跟你文档相关的？→ **都相关但答案还是错**，那病根在生成环节（§2.3/2.4）

### 步骤 4：写一份你自己的《排查记录》（沉淀）

把你这几天遇到的 1-2 个「答不好」的问题，用 debug 接口跑一遍，记录：问题 → 召回几条 → 分数 → top5 内容 → 判定（检索/生成）→ 怎么改。**这份记录就是你的 RAG 排错手册。**

---

## 4. 自检标准（不通过不许进 Day47）

- [ ] `debugRetrieve` 能返回：改写查询、混合召回数、重排 top5（含分数和来源）；
- [ ] 用 1 个「答不好」的问题跑 debug，能**明确判定**是「检索问题」还是「生成问题」；
- [ ] 对判定为「检索问题」的，能说出是四层里的哪层（数据/权限 → 查询改写 → 阈值/topK → 分块）；
- [ ] 对判定为「生成问题」的，能说出是该换上下文清洗 / 结构化 prompt / 拒答溯源中的哪一项；
- [ ] 能口头讲清整套流程：**先分开检索和生成 → 检索按四层查 → 生成按三宗罪查**。

---

## 5. 关键踩坑清单（必背）

1. **一上来就瞎调算法**：要先看数据/异步/权限/路由（你 Day43/45 的坑），别开箱就怀疑 embedding。
2. **只看最终答案，不看检索中间结果**：这是最大浪费。答案不好，先看召回几条、分数多少、内容对不对。
3. **权限过滤导致的"假检索不到"**：Day45 加了 owner，`currentUser` 传不对，检索空是**正常的**。别当成 bug 乱调 threshold。
4. **异步上传没等完成就查**：Day43 批量是异步的，`/upload` 返回 ≠ 入库完成。查 `/batch/{taskId}` 状态。
5. **上下文没清洗就喂给模型**：top5 里可能有噪声块，全塞进去让模型更糊涂。
6. **prompt 只写「基于资料回答」**：太弱。加「只依据资料、可拒答、要求溯源」。

---

## 6. 今日小结 + 明日预告

**今天你学会了**：RAG 排错的核心方法论 —— **先分「检索」和「生成」，检索按「数据→改写→阈值→分块」层层查，生成按「上下文→prompt→幻觉」三宗罪清**。并学会了「把中间结果暴露出来看」这个 debug 思维。这是从「瞎调」到「定位」的分水岭。

**明日（Day47，第 7 周末）**：效果评估 —— 构建测试集，量化评估 RAG 效果。终于从「感觉好多了」到「有数字证明好多了」：建一套评测问题-标准答案，算 `Recall@N`、准确率、幻觉率，用数据说话。

---

*文档生成日期：2026-08-28 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
