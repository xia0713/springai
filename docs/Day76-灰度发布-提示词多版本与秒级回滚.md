# Day 76：灰度发布与版本管理 —— 提示词多版本、按用户灰度、秒级回滚

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：提示词是变更频率最高的"运行时代码" —— 多版本注册表 + 按用户灰度 + 秒级回滚 + A/B 观测口径
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day73 网关（灰度路由的最佳挂载点）+ Day72 缓存（今天有个跨天交互坑）

---

## 0. 今天要解决的问题

你的提示词现在是怎么管理的？—— **硬编码在 Java 字符串里**：

```
改一个提示词 → 改代码 → 编译 → 重启 → 全量生效
   ↓ 三个问题
① 变更风险全量爆炸：改坏了 = 100% 用户受影响
② 回滚要分钟级（重新部署）：事故已扩散
③ 无法验证：新提示词好还是旧的好？没有对照
```

**核心认知（今天最重要的一句话）：提示词是 AI 时代的代码 —— 但它是运行时变更的代码，所以它需要传统代码没有的东西：版本管理 + 灰度发布 + 秒级回滚。**

```
改提示词的正确姿势：
  v1（线上） ←→ v2（实验版）
        ↓ 按用户 hash 灰度 30% 到 v2
        ↓ 观测 A/B 指标（采纳率/准确率）
        ↓ 好则 100%，坏则秒级回滚到 v1（改个数字，不重启）
```

---

## 1. 核心知识点（40 分钟）

### 1.1 灰度对象的优先级：提示词 > 模型 > 功能

| 对象 | 变更频率 | 影响面 | 灰度方式 |
|---|---|---|---|
| **提示词** | 最高（可能天天调） | 全局（每个问答都过它） | 版本注册表 + 按用户百分比（今天做） |
| 模型 | 低（周/月级） | 全局 | Day71 已有双模型底座 → 路由比例灰度 |
| 功能 | 中 | 按功能域 | 功能开关（feature flag），网关层拦截 |

**为什么提示词是头号对象**：它是你唯一"每次上线前都忍不住再改一句"的东西 —— 变更频率最高、又没有编译期保护（写错了也照常生效）。传统代码有测试兜底，提示词的兜底就是灰度。

### 1.2 灰度策略四种（从保守到激进）

| 策略 | 规则 | 适用 |
|---|---|---|
| **白名单** | 指定用户 ID 列表先试 | 内测/老板验收 |
| **按用户 hash 百分比** | `hash(userId) % 100 < 灰度比例` → 新版 | ✅ 今天做：同一用户永远同一版本，体验稳定 |
| 按渠道 | App 端先上，Web 后上 | 风险隔离 |
| 全量开关 | 一刀切 | 无所谓风险的变更 |

**hash 稳定性是 A/B 的生命线**：同一用户每次请求必须路由到同一版本 —— 否则用户一会看到 v1 一会看到 v2（体验错乱），且 A/B 数据互相污染。`hash(userId) % 100` 满足确定性（生产用一致性哈希/Murmur，演示用 String.hashCode 够）。

### 1.3 A/B 测试三要素

1. **均匀分流**：hash 稳定 + 分流比例可验证（stats 里 v1/v2 调用数应接近配置比例）
2. **观测口径**（结合 Day47 的评测思想，搬到线上）：
   - 客观：per-version 的准确率/幻觉率（抽样本跑评测集）
   - 主观：per-version 的采纳率/点赞率/修改率（需要前端埋点，Day65 反馈层）
3. **样本量意识**：灰度 10%×日均 300 次 = 30 次/天 —— **别用 10 个样本下结论**；差异小于 5 个百分点时基本是噪声

### 1.4 回滚的速度分级（提示词必须秒级）

| 方式 | 速度 | 适用 |
|---|---|---|
| **运行时配置调整**（今天做） | **秒级**（改内存里的路由比例/版本映射） | 提示词、灰度比例 |
| 配置中心推送 | 秒~分钟 | 模型参数 |
| 重新部署/镜像回滚 | 分钟级 | 代码级变更 |

**回滚操作的具体形态：`POST /percent?p=0`** —— 灰度比例归零，所有流量回 v1，一次 HTTP 调用完成，不重启。这就是"秒级"的全部含义。

### 1.5 灰度与缓存的冲突（跨天交互坑，Day72 的债）

```
v2 上线 → 用户问题 X 被缓存（v2 的答案）
灰度回滚到 v1 → 同一个问题命中缓存 → 返回的还是 v2 的答案！
   ↓ 后果
你明明回滚了，用户看到的还是坏版本 —— 灰度体系被缓存击穿
```

**两个解法**：
1. 缓存 key 加 prompt 版本维度（`version + 归一化问题`）—— 版本切换即天然分区
2. 切版本时主动清缓存（发布动作的一部分）

今天在踩坑清单立此存照，综合项目做缓存时必须处理。

---

## 2. 实操作业（80 分钟）

> 目标：提示词注册表（v1/v2）+ 按用户 hash 灰度 + 运行时比例调整（秒级回滚）+ per-version A/B 计数。

### 步骤 1：灰度配置（application.yaml 追加）

```yaml
app:
  # 提示词灰度（Day76）：比例运行时可调，提示词文本启动时加载注册表
  prompt-gray:
    percent: 30          # 30% 用户进 v2（hash(userId)%100 < 30）
    default-version: v1
    prompts:
      knowledge-qa:
        v1: |
          你是企业知识库助手。基于已知信息回答，先给结论再给依据，简洁准确。
        v2: |
          你是企业知识库助手（v2 实验版）。
          回答结构固定为：【结论】→【依据】→【补充说明】。
          依据必须引用资料来源；资料中没有的信息明确说"资料未覆盖"，禁止编造。
```

### 步骤 2：灰度服务（新建 `service/PromptGrayService.java`）

```java
package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词灰度服务（Day76）：多版本注册表 + 按用户 hash 灰度 + 运行时秒级回滚 + A/B 计数。
 * <p>
 * 生产演进：注册表落库（提示词=数据不是代码）+ 网关统一取用（Day73 统一管控的延伸）。
 */
@Slf4j
@Service
public class PromptGrayService {

    private final ChatClient chatClient;

    /** 提示词注册表：promptKey → (version → 文本)。启动时从 yaml 加载，运行时可热更 */
    private volatile Map<String, Map<String, String>> promptRegistry;
    /** 灰度比例（0-100）：volatile 保证多线程立即可见 —— 秒级回滚的物理基础 */
    private volatile int grayPercent;
    private volatile String defaultVersion;

    /** A/B 观测口径：per-version 调用计数 */
    private final AtomicLong v1Calls = new AtomicLong();
    private final AtomicLong v2Calls = new AtomicLong();
    /** 分流一致性验证：userId → 最近版本（演示用，观测"同一用户是否稳定"） */
    private final Map<String, String> userVersionMap = new ConcurrentHashMap<>();

    public PromptGrayService(@Qualifier("qwenChatClient") ChatClient chatClient,
                             @Value("${app.prompt-gray.percent}") int percent,
                             @Value("${app.prompt-gray.default-version}") String defaultVersion) {
        this.chatClient = chatClient;
        this.grayPercent = percent;
        this.defaultVersion = defaultVersion;
        // 生产：注册表从 DB/配置中心加载；演示：直接内置两个版本（文本与 yaml 一致）
        this.promptRegistry = Map.of(
                "knowledge-qa", Map.of(
                        "v1", """
                                你是企业知识库助手。基于已知信息回答，先给结论再给依据，简洁准确。
                                """,
                        "v2", """
                                你是企业知识库助手（v2 实验版）。
                                回答结构固定为：【结论】→【依据】→【补充说明】。
                                依据必须引用资料来源；资料中没有的信息明确说"资料未覆盖"，禁止编造。
                                """));
    }

    /** 灰度问答：路由 → 取对应版本提示词 → 生成 → 记录版本 */
    public Map<String, Object> chat(String userId, String question) {
        String version = route(userId);
        if ("v1".equals(version)) v1Calls.incrementAndGet();
        else v2Calls.incrementAndGet();
        userVersionMap.put(userId, version);

        String systemPrompt = promptRegistry
                .getOrDefault("knowledge-qa", Map.of())
                .getOrDefault(version, promptRegistry.get("knowledge-qa").get(defaultVersion));

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        return Map.of("answer", answer, "promptVersion", version);
    }

    /** 按用户 hash 灰度路由：确定性 —— 同一用户永远同一版本（A/B 的生命线） */
    public String route(String userId) {
        int bucket = Math.abs(userId.hashCode() % 100);
        return bucket < grayPercent ? "v2" : "v1";
    }

    /** 秒级回滚/放量：改一个数字，不重启。回滚 = setPercent(0) */
    public Map<String, Object> setGrayPercent(int percent) {
        if (percent < 0 || percent > 100) {
            return Map.of("ok", false, "message", "比例必须在 0-100");
        }
        int old = this.grayPercent;
        this.grayPercent = percent;
        log.warn("[prompt-gray] 灰度比例 {}% → {}%（{}）", old, percent, percent == 0 ? "回滚" : "放量");
        return Map.of("ok", true, "oldPercent", old, "newPercent", percent);
    }

    /** A/B 观测口径：分流分布 + 一致性抽样 */
    public Map<String, Object> stats() {
        long v1 = v1Calls.get(), v2 = v2Calls.get();
        long total = v1 + v2;
        return Map.of(
                "grayPercent", grayPercent,
                "v1Calls", v1,
                "v2Calls", v2,
                "v2Share", total == 0 ? "0%" : String.format("%.1f%%", v2 * 100.0 / total),
                "trackedUsers", userVersionMap.size()
        );
    }
}
```

### 步骤 3：控制器（新建 `controller/PromptGrayController.java`）

```java
package com.example.springai.controller;

import com.example.springai.service.PromptGrayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prompt-gray")
@RequiredArgsConstructor
public class PromptGrayController {

    private final PromptGrayService service;

    /** 灰度问答：同一 userId 永远同一版本 */
    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestParam String userId,
            @RequestParam String question) {
        return service.chat(userId, question);
    }

    /** A/B 观测：分流分布（应接近配置比例）+ 一致性追踪 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    /** 秒级放量/回滚：改一个数字，不重启（回滚 = p=0） */
    @PostMapping("/percent")
    public Map<String, Object> setPercent(@RequestParam int p) {
        return service.setGrayPercent(p);
    }
}
```

### 步骤 4：验证（灰度三连）

```bash
# ① 灰度一致性：同一 userId 多次调用，版本必须稳定
curl -G "http://localhost:8080/api/prompt-gray/chat" --data-urlencode "userId=u1" --data-urlencode "question=退货政策是什么"
curl -G "http://localhost:8080/api/prompt-gray/chat" --data-urlencode "userId=u1" --data-urlencode "question=年假有几天"
# 两次的 promptVersion 必须相同（u1 的 hash 决定，不随请求变化）

# ② A/B 分流分布：找几个 userId 试出 v2 用户，或直接放量
curl -X POST "http://localhost:8080/api/prompt-gray/percent?p=100"
curl -G "http://localhost:8080/api/prompt-gray/chat" --data-urlencode "userId=any" --data-urlencode "question=退货政策"
# 期望：promptVersion=v2，且回答带【结论】结构（v2 的格式约束生效 —— 这就是"提示词版本真的切了"的证据）

# ③ 秒级回滚：改一个数字
curl -X POST "http://localhost:8080/api/prompt-gray/percent?p=0"
curl -G "http://localhost:8080/api/prompt-gray/chat" --data-urlencode "userId=any" --data-urlencode "question=退货政策"
# 期望：promptVersion=v1，服务不重启 —— 这就是"秒级回滚"的全部含义

# ④ A/B 观测：stats 的 v2Share 应接近你设置的比例
curl "http://localhost:8080/api/prompt-gray/stats"
```

---

## 3. 自检标准（不通过不许进 Day77）

- [ ] 同一 userId 多次调用版本稳定（hash 确定性生效）；
- [ ] `percent=100` 后 v2 的格式约束真实生效（回答带【结论】结构）—— 证明切的不只是标签；
- [ ] `percent=0` 秒级回滚，全程不重启；
- [ ] stats 的 v2Share 接近配置比例（分流均匀性可验证）；
- [ ] 能口头讲清：为什么提示词是灰度头号对象；hash 稳定性为什么是 A/B 生命线；灰度与缓存的冲突及两个解法。

---

## 4. 关键踩坑清单（必背）

1. **提示词变更走重新部署** → 回滚分钟级，事故已扩散。提示词必须运行时可调（注册表 + 热更）。
2. **hash 不稳定** → 同一用户来回换版本，体验错乱 + A/B 数据作废。`hash(userId)%100` 确定性分流。
3. **灰度比例直接 100%** → 没有灰度的灰度。30% 起步，看观测数据逐级放量。
4. **A/B 没有观测口径** → 分了流但下不了结论。per-version 计数是最低配，采纳率埋点是标准配（Day65 反馈层）。
5. **提示词版本没记录** → 事故回溯时不知道"当时用的是哪版"。每次调用的版本要进审计日志（今天返回体里带了 promptVersion）。
6. **灰度被缓存击穿** → 切版本后缓存里还是旧版答案（Day72 的缓存 key 不含版本）。解法：key 加版本维度 or 切版本清缓存 —— 综合项目必做。
7. **注册表写死在代码里** → 今天的 yaml/内置 Map 是演示形态；生产必须落库（提示词=数据），配合管理界面（综合项目）。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：提示词的"版本管理 + 按用户灰度 + 秒级回滚"全套 —— 并建立了三个跨天连接：A/B 观测口径接 Day47 评测、灰度挂载点接 Day73 网关、缓存冲突接 Day72。**提示词从"代码里的字符串"变成了"有版本、可灰度、能回滚的运行时资产"。**

**明日（Day77，第 11 周收官）**：本周复盘 —— 整理《AI 应用生产级落地规范》（学习计划原文的作业），覆盖稳定性/成本/性能/部署四大维度，把 Day71-76 的全部治理成果收口成一份团队可用的规范文档。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
