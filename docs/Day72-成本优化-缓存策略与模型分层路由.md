# Day 72：成本优化 —— 缓存策略与模型分层路由（简单问题走便宜模型）

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：让每一分 token 都花在刀刃上 —— 精确缓存（二次调用 0 token）+ 复杂度路由（简单问题自动走便宜模型）
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day71 的 `ResilientChatService` 骨架（双 ChatClient + 缓存雏形）今天直接升级复用

---

## 0. 今天要解决的问题

你现在每个问题都打最贵的模型：

```
「你好」          → qwen3.8-27b（杀鸡用牛刀）
「1加1等于几」     → qwen3.8-27b
「分析退货政策」   → qwen3.8-27b ✓（这个合理）
同一个问题问第二遍 → 又花一遍钱
```

**成本优化不是为了省钱而省钱 —— 是把预算留给真正需要大模型的问题。** Day64 算过账：token 是运行期的持续成本，优化它就是直接改 ROI。

**你已有的底座（Day71 骨架直接升级）：**
- 两个 ChatClient：qwen（强、贵）+ deepseek（快、便宜）—— 今天从"主/备"变"大/小"
- `ResilientChatService` 里有个 `answerCache` 雏形 —— 今天升级成带用量记录的正式缓存

---

## 1. 核心知识点（40 分钟）

### 1.1 成本公式与优化优先级

```
月成本 = Σ( 每次调用的 输入tokens×输入单价 + 输出tokens×输出单价 )
              ↑
        输入往往是大头：RAG 一次问答的输入 = system + 检索上下文 + 历史
        通常是输出的 5~20 倍！只盯生成费会算错账（Day64 反直觉真相的细化）
```

**优化手段按性价比排序（今天做前两个）：**

| 优先级 | 手段 | 效果 | 说明 |
|---|---|---|---|
| ① | **缓存命中** | 该次 → **0 token** | 完全不调用，最彻底 |
| ② | **砍输入** | 输入 ↓50-80% | 上下文压缩、工具结果裁剪、按场景收窄工具（Day55） |
| ③ | **换便宜模型** | 单价 ↓5-10 倍 | 简单问题路由小模型（今天做） |
| ④ | 砍输出 | 输出 ↓ | 限制回答长度（牺牲体验，慎用） |

### 1.2 缓存的三层（今天做第一层）

| 层 | 原理 | 命中率 | 误伤风险 | 成本 |
|---|---|---|---|---|
| **① 精确缓存**（今天） | 问题原文（归一化后）做 key | 低（措辞稍变就 miss） | **零** | 零 |
| ② 语义缓存 | embedding 相似度 ≥0.95 → 视为同问题 | 高 | ⚠️ 有（"苹果手机"vs"苹果水果"） | 每次查询一次 embedding |
| ③ KV 前缀缓存 | 框架/网关层：system prompt 固定前缀复用计算 | 透明 | 零 | 网关决定 |

**语义缓存就是你第 5 周学的向量库换个用途**（和 Day59 长期记忆同源思想）—— 但阈值必须很高（≥0.95），否则相似≠相同会误伤。今天先做零风险的精确缓存。

### 1.3 模型分层路由 —— 三种路由器

| 路由器 | 做法 | 成本 | 评价 |
|---|---|---|---|
| **① 规则路由**（今天） | 关键词/长度启发式判断复杂度 | 零 | ✅ 粗但零成本、可解释；能挡 60-70% 就够本 |
| ② 小模型分类器 | 先用便宜模型分类"复杂/简单"，再路由 | 每次一次小调用 | 更准；分类费 vs 生成省的账要算 |
| ③ 升级模式 | 小模型先答 + 自评置信度，低置信升级大模型 | 简单题也可能双次调用 | 质量最优，成本最难控 |

**判断"简单"的规则示例（可解释优先）：**

```
简单：长度 ≤ 30 字 && 不含复杂意图词（分析/总结/对比/为什么/怎么/如何/方案/写一个/解释…）
复杂：其余全部
```

诚实标注：规则粗，但 Demo 的目的是把**路由骨架和统计口径**建起来 —— 词表可以永远迭代，骨架不变。

### 1.4 上下文压缩（知识点点到，实现放综合项目）

输入 token 的四个大头，各有对策：
1. **会话历史** → 窗口 + 老消息摘要压缩（Day59 讲过思路）
2. **检索上下文** → 只带 top2-3 + 裁剪（Day53 的格式化）
3. **工具清单** → 按场景挂（Day55 规范 4.1）
4. **few-shot 例子** → 只带最相关的 1-2 条，不是越多越好

### 1.5 怎么证明省了？（不然全是自我感觉）

两级证据：
1. **路由/命中计数器**（今天必做）：cacheHits / smallCalls / bigCalls / tokensSaved —— 谁走了哪条路一目了然
2. **真实 token 计量**（今天做，API 已验证）：`ChatResponse.getMetadata().getUsage()` → `getPromptTokens()/getCompletionTokens()/getTotalTokens()` —— 缓存命中时把"上次同样的调用花了多少"记进 tokensSaved，账就出来了

---

## 2. 实操作业（80 分钟）

> 目标：问答缓存（二次调用 0 token）+ 复杂度路由（简单问题自动走 deepseek），带用量统计。

### 步骤 1：成本优化服务（新建 `service/CostOptimizedChatService.java`）

```java
package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本优化服务（Day72）：精确缓存 + 复杂度路由 + 用量统计。
 * <p>
 * 复用 Day71 的两个 ChatClient，角色重新定义：
 *   qwen3.8-27b = 大模型（复杂问题） / deepseek-v4-flash = 小模型（简单问题）。
 * ⚠️ 缓存 key 必须归一化；本服务无个性化上下文，若未来加入用户维度，
 *    key 必须含 userId（否则用户 A 的答案返回给用户 B —— Day45/59 的隔离教训）。
 */
@Slf4j
@Service
public class CostOptimizedChatService {

    private final ChatClient bigModel;    // qwen3.8-27b：复杂问题
    private final ChatClient smallModel;  // deepseek-v4-flash：简单问题

    private final Map<String, CachedAnswer> cache = new ConcurrentHashMap<>();

    // ---- 统计计数器（证明省了多少的口径）----
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong smallModelCalls = new AtomicLong();
    private final AtomicLong bigModelCalls = new AtomicLong();
    private final AtomicLong tokensSavedByCache = new AtomicLong();

    private static final String[] COMPLEX_HINTS = {
            "分析", "总结", "对比", "为什么", "怎么", "如何", "方案", "写一个", "解释", "设计"
    };

    public CostOptimizedChatService(@Qualifier("qwenChatClient") ChatClient bigModel,
                                    @Qualifier("deepseekChatClient") ChatClient smallModel) {
        this.bigModel = bigModel;
        this.smallModel = smallModel;
    }

    public Map<String, Object> chat(String question) {
        String key = normalize(question);

        // ① 精确缓存命中：0 token，不调用任何模型
        CachedAnswer hit = cache.get(key);
        if (hit != null) {
            cacheHits.incrementAndGet();
            tokensSavedByCache.addAndGet(hit.totalTokens());
            log.info("[cost] 缓存命中（节省 {} tokens）", hit.totalTokens());
            return Map.of(
                    "answer", "[缓存·0token] " + hit.answer(),
                    "route", "CACHE",
                    "tokens", 0);
        }

        // ② 复杂度路由：简单 → 小模型，复杂 → 大模型
        boolean simple = isSimple(question);
        ChatClient client = simple ? smallModel : bigModel;
        if (simple) smallModelCalls.incrementAndGet();
        else bigModelCalls.incrementAndGet();

        ChatResponse resp = client.prompt().user(question).call().chatResponse();
        String answer = resp.getResult().getOutput().getText();

        // ③ 真实用量计量（已验证 API：ChatResponseMetadata.getUsage()）
        Usage usage = resp.getMetadata().getUsage();
        int totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();

        cache.put(key, new CachedAnswer(answer, totalTokens));

        return Map.of(
                "answer", answer,
                "route", simple ? "SMALL(deepseek)" : "BIG(qwen)",
                "tokens", totalTokens
        );
    }

    /** 归一化缓存 key：去首尾空格 + 统一小写（英文场景），避免"你好"和"你好 " 两个 key */
    private String normalize(String q) {
        return q == null ? "" : q.strip().toLowerCase();
    }

    /** 规则路由：短 && 无复杂意图词 → 简单（走小模型） */
    private boolean isSimple(String q) {
        if (q.length() > 30) return false;
        for (String hint : COMPLEX_HINTS) {
            if (q.contains(hint)) return false;
        }
        return true;
    }

    /** 用量统计口径 */
    public Map<String, Object> stats() {
        return Map.of(
                "cacheHits", cacheHits.get(),
                "smallModelCalls", smallModelCalls.get(),
                "bigModelCalls", bigModelCalls.get(),
                "tokensSavedByCache", tokensSavedByCache.get(),
                "cacheSize", cache.size()
        );
    }

    /** 缓存条目：答案 + 当时消耗的 tokens（命中时记为"省下的"） */
    private record CachedAnswer(String answer, int totalTokens) {}
}
```

### 步骤 2：演示控制器（新建 `controller/CostDemoController.java`）

```java
package com.example.springai.controller;

import com.example.springai.service.CostOptimizedChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cost")
@RequiredArgsConstructor
public class CostDemoController {

    private final CostOptimizedChatService chatService;

    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String question) {
        return chatService.chat(question);
    }

    /** 用量统计：缓存命中率 / 大小模型分布 / 累计节省 tokens */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return chatService.stats();
    }
}
```

### 步骤 3：验证（4 个场景，账目清晰）

```bash
# ① 复杂问题 → 大模型（route=BIG，tokens 有值）
curl -G "http://localhost:8080/api/cost/chat" --data-urlencode "question=分析一下退货政策的核心要点"

# ② 简单问题 → 小模型（route=SMALL，tokens 通常更少）
curl -G "http://localhost:8080/api/cost/chat" --data-urlencode "question=你好"

# ③ 同一问题第二次 → 缓存（route=CACHE，tokens=0，answer 带 [缓存·0token] 前缀）
curl -G "http://localhost:8080/api/cost/chat" --data-urlencode "question=你好"

# ④ 查账：cacheHits=1，tokensSavedByCache>0，大小模型调用分布
curl "http://localhost:8080/api/cost/stats"
```

**过关铁证**：场景③的 `tokens=0` + 场景④的 `tokensSavedByCache` 等于场景②那次调用的 totalTokens —— "相同问题二次调用不消耗 Token"从口号变成账本。

---

## 3. 自检标准（不通过不许进 Day73）

- [ ] 相同问题第二次调用 **0 token**（tokens=0 + tokensSavedByCache 增加）；
- [ ] 简单问题自动走小模型（route=SMALL），复杂问题走大模型（route=BIG）；
- [ ] "你好 " 带尾空格再问也命中缓存（归一化生效）；
- [ ] 能口头讲清：优化优先级四档（缓存→砍输入→换模型→砍输出）；输入 token 为什么是大头；缓存三层及各自的误伤风险；
- [ ] 能回答："缓存里如果答案带用户个性化怎么办？"（key 必须加用户维度，否则 A 的答案漏给 B —— Day45/59 隔离教训）。

---

## 4. 关键踩坑清单（必背）

1. **缓存 key 不归一化** → "你好" 和 "你好 " 是两个 key，命中率虚低。strip + 小写。
2. **语义缓存误伤** → 相似 ≠ 相同。阈值 ≥0.95 且先在容错高的场景试点（今天没做语义缓存，就是先躲开这个坑）。
3. **缓存不设过期** → "今天天气" 的答案三天后还在 —— 知识/时效类答案必须 TTL。精确缓存也建议加过期时间（综合项目补）。
4. **个性化答案进了共享缓存** → 用户 A 的答案返回给用户 B，隔离事故。**key 必须含用户/权限维度**。
5. **只算生成费不算输入费** → 输入 token 是 5-20 倍大头，账要分开记。
6. **规则词表越写越长** → 规则路由的维护成本会随词表膨胀，挡到 60-70% 就该考虑换小模型分类器（2.3 的②）。
7. **统计口径缺失** → 没有计数器和 usage 计量，"优化了"永远是感觉 —— 今天 stats 接口就是口径。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：成本优化的两记实招 —— 精确缓存（二次 0 token）+ 复杂度路由（简单问题走便宜模型），并建立了"谁走了哪条路、省了多少 token"的统计口径。Day71 的多模型骨架正式从"故障切换"扩展出"成本路由" —— 一套基础设施，两种用途。

**明日（Day73）**：AI 能力中台化 —— 统一接入、统一管控、统一鉴权，把这两周沉淀的 稳定性（Day71）+ 成本（Day72）能力封装成 SDK，让其他业务系统 **5 行代码接入 AI 能力**。这是从"一个项目"到"一个平台"的跨越。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
