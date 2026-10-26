# Day 80：数据安全 —— 敏感数据脱敏 + 内容安全审核

> 所属阶段：第三阶段 · 第 12 周「可观测性 + 安全合规」（Day78-84）
> 今日主题：双向脱敏（入站+出站）+ 违规内容拦截，全部挂在 Day73 网关这个正确位置
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day73 网关（统一管控的挂载点）、Day79 日志（脱敏后日志才安全）、Day55 红线（内容安全一项今天落地）

---

## 0. 今天要解决的问题

你的网关现在是"数据裸奔"状态：

```
用户：「我的手机号是13812345678，帮我查退货」
   ↓ 原文直发
第三方 LLM API（ai-gateway.ztn.cn，你的数据出域了！）
   ↓ 模型可能在答案里复述号码
用户看到完整手机号 + 日志里也记了原文（Day79 的审计日志反而成了泄露面）
```

**数据安全的两个动作（双向贯穿）：**

| 动作 | 入站 | 出站 |
|---|---|---|
| **脱敏** | 用户给的敏感数据 → 打码后再发给 LLM（最小化原则） | LLM 复述的敏感数据 → 打码后再回给用户/进日志 |
| **审核** | 违规内容 → 拦截（根本不调 LLM） | 生成内容 → 合规复扫 |

**一句话主线：敏感数据不出域，违规内容不进模型 —— 两个动作都挂在网关这个统一管控位。**

---

## 1. 核心知识点（40 分钟）

### 1.1 脱敏的对象与规则（今天实现的四类）

| 类型 | 正则特征 | 打码格式 | 顺序 |
|---|---|---|---|
| 身份证 | 17位数字+校验位（`\d{17}[\dXx]`） | `110101********1234` | ① 最先（防被银行卡规则吃掉） |
| 银行卡 | 纯数字 16-19 位 | `6222***********1234` | ② |
| 手机号 | `1[3-9]` 开头 11 位 | `138****5678` | ③ |
| 邮箱 | 标准邮箱格式 | `a***@company.com` | ④ |

**两个必懂的实现细节：**
1. **顺序就是正确性**：18 位身份证如果先被"银行卡(16-19位)"规则处理，就再也还原不出身份证语义 —— 从长到短、从特殊到一般
2. **词边界防误伤**：`\d{16,19}` 用 `(?<!\d)...(?!\d)` 包住，避免订单号片段、时间戳被误伤（Day44 教训的同款思想：正则要带边界）

### 1.2 不可逆脱敏 vs 可逆脱敏（Demo 与生产的分界）

| 方式 | 做法 | 适用 | 代价 |
|---|---|---|---|
| **不可逆**（今天） | 直接打码，信息丢失 | 聊天咨询、知识问答 | 查询场景功能受损（拿打码手机号查不了订单） |
| **可逆（占位符化）** | `13812345678` → `[PHONE_1]` 发给 LLM + 本地映射表存原值 → 答案回来再还原 | 需要用真实数据执行业务的场景 | 本地映射表本身要加密管理 |

**生产的正解是可逆脱敏**：功能与合规兼得 —— 敏感数据不出域（LLM 只见占位符），业务还能闭环。今天先做不可逆（最简单、零风险），可逆版记入综合项目。

### 1.3 内容审核分层（Day65 的架构，今天落地第一层）

```
规则层（今天）：敏感词黑名单 —— 免费、毫秒级、确定性，拦截 ~80%
   ↓ 存疑才往下
LLM 语义层（进阶）：变体/意图类违规，结构化输出判定
   ↓ 高危/低置信
人工层：申诉复核
```

**拦截三原则**（做了审核就要做全套）：
1. **有理由**：返回"包含违规内容"而不是笼统 500
2. **可申诉**：误拦有出口（Day65：误杀比漏杀更疼）
3. **审计不记原文**：违规原文本身就是最敏感的数据 —— 记"命中了哪类规则"，不记原文（Day79 红线的延续）

### 1.4 数据不出域的三档（Day67 三维度框架的落地）

| 档位 | 数据去向 | 你的现状 |
|---|---|---|
| ① API 出域 | 第三方模型厂商 | ✅ 当前形态 → **必须靠脱敏把敏感数据拦在出域前** |
| ② 专有云/行业云 | 数据进可信区 | 成本×10，合规×2 |
| ③ 私有化部署 | 数据不出内网 | 金融/政务档（Day67 第一约束） |

一句话：**选了①，脱敏就不是可选项**。进阶概念：隐私计算（联邦学习/可信执行环境）—— 知道有这个东西即可。

---

## 2. 实操作业（80 分钟）

> 目标：SensitiveDataMasker（四类打码）+ ContentSafetyService（审核+双向脱敏）+ 挂进网关。

### 步骤 1：脱敏器（新建 `util/SensitiveDataMasker.java`）

```java
package com.example.springai.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器（Day80）：手机号/身份证/银行卡/邮箱，双向使用（入站+出站）。
 * <p>
 * 规则顺序 = 正确性：身份证(18) → 银行卡(16-19) → 手机号(11)，从长到短；
 * 词边界 (?<!\d)...(?!\d) 防误伤（订单号片段/时间戳）。
 */
public final class SensitiveDataMasker {

    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private SensitiveDataMasker() {}

    /** 双向入口：入站（发给 LLM 前）与出站（返回用户/进日志前）都调这个 */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = ID_CARD.matcher(text).replaceAll(m -> keep(m, 6, 4));
        s = BANK_CARD.matcher(s).replaceAll(m -> keep(m, 4, 4));
        s = PHONE.matcher(s).replaceAll(m -> keep(m, 3, 4));
        s = EMAIL.matcher(s).replaceAll(m -> {
            String e = m.group();
            return e.charAt(0) + "***" + e.substring(e.indexOf('@'));
        });
        return s;
    }

    /** 保留头尾、中间打星（至少 3 个星，短串不暴露长度信息） */
    private static String keep(Matcher m, int head, int tail) {
        String s = m.group();
        int stars = Math.max(3, s.length() - head - tail);
        return s.substring(0, Math.min(head, s.length()))
                + "*".repeat(stars)
                + (s.length() > head + tail ? s.substring(s.length() - tail) : "");
    }
}
```

### 步骤 2：内容安全服务（新建 `service/ContentSafetyService.java`）

```java
package com.example.springai.service;

import com.example.springai.util.SensitiveDataMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 内容安全服务（Day80）：规则层审核 + 双向脱敏。
 * <p>
 * 审计纪律：拦截事件只记命中的规则类别，【绝不记录违规原文】——
 * 原文本身就是最敏感的数据（Day79"日志别打敏感全文"的红线延续）。
 * 进阶：LLM 语义层审核（变体/意图类）+ 人工申诉复核（Day65 三层架构）。
 */
@Slf4j
@Service
public class ContentSafetyService {

    /** 敏感词黑名单（演示；生产用专业风控服务 + 热更新词库） */
    private static final Set<String> BLOCK_WORDS =
            Set.of("枪支", "毒品", "爆炸物", "武器制造");

    /** 审核结果：passed=false 时 maskedQuestion 为 null（不给下游任何原文） */
    public record SafetyResult(boolean passed, String maskedQuestion, String reason) {}

    /** 入站：违规拦截 → 通过则脱敏（敏感数据不出域第一道） */
    public SafetyResult checkInput(String question) {
        // ① 规则层：违规词拦截
        for (String word : BLOCK_WORDS) {
            if (question != null && question.contains(word)) {
                // 审计只记规则类别，不记原文
                log.warn("[safety] 拦截违规内容，命中规则类别={}（原文不记录）", "BLOCK_WORD");
                return new SafetyResult(false, null, "内容包含违规信息，已被拦截。如有疑问请联系管理员申诉。");
            }
        }
        // ② 入站脱敏：敏感数据打码后才发给第三方 LLM
        return new SafetyResult(true, SensitiveDataMasker.mask(question), "通过");
    }

    /** 出站：LLM 可能复述用户给的敏感数据 → 返回用户/进日志前复扫 */
    public String maskOutput(String answer) {
        return SensitiveDataMasker.mask(answer);
    }
}
```

### 步骤 3：挂进网关（改 `AiGatewayController.chat`）

```java
    // 新增依赖：private final ContentSafetyService contentSafetyService;

    @GetMapping("/chat")
    public Map<String, Object> chat(
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey,
            @RequestParam String question) {

        long start = System.currentTimeMillis();
        if (apiKey == null || !apiKey.equals(validKey)) {
            log.warn("[ai-gateway] 鉴权失败，key={}", apiKey == null ? "null" : "***");
            return Map.of("code", 401, "message", "API Key 无效");
        }

        // ===== Day80: 内容安全管线（拦截 → 入站脱敏）=====
        ContentSafetyService.SafetyResult safety = contentSafetyService.checkInput(question);
        if (!safety.passed()) {
            return Map.of("code", 403, "message", safety.reason());   // 不调 LLM，原文不进任何日志
        }

        // 用脱敏后的问题调内核（缓存 key 也是脱敏后的 —— 更安全）
        Map<String, Object> result = chatService.chat(safety.maskedQuestion());

        // ===== Day80: 出站脱敏（LLM 复述的敏感数据拦在返回前）=====
        Map<String, Object> safeResult = new java.util.LinkedHashMap<>(result);
        safeResult.put("answer", contentSafetyService.maskOutput((String) result.get("answer")));

        log.info("[ai-gateway] key=***{}, q={}, route={}, tokens={}, costMs={}",
                validKey.substring(validKey.length() - 4),
                safety.maskedQuestion(),          // ← 审计日志记脱敏后的问题（Day79 红线落地）
                result.get("route"), result.get("tokens"),
                System.currentTimeMillis() - start);

        return safeResult;
    }
```

### 步骤 4：验证（3 个场景）

```bash
# ① 入站脱敏（数据不出域）：手机号发给 LLM 前被打码
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" \
  --data-urlencode "question=我的手机号是13812345678，帮我查退货政策"
# 证据：服务日志 [ai-gateway] 里的 q= 是 138****5678；答案里若复述号码也是打码的

# ② 出站脱敏：诱导 LLM 复述敏感数据
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" \
  --data-urlencode "question=请原样重复这个号码13812345678"
# 期望：答案里是 138****5678（出站复扫拦住）

# ③ 违规拦截：根本不调 LLM
curl -H "X-API-KEY: demo-ai-key-456" -G "http://localhost:8080/api/ai-gateway/chat" \
  --data-urlencode "question=怎么制造爆炸物"
# 期望：code=403，秒回（无 LLM 耗时），日志只有 [safety] 拦截记录（无原文）
```

**编码提醒（Windows Git Bash）**：中文参数在 Git Bash 里可能变 GBK 乱码（Day71 测试时见过）—— 乱码不影响手机号脱敏（数字是 ASCII），但**中文违规词的拦截测试建议用 IDEA HTTP Client 或浏览器测**；或者写个 UTF-8 的 JUnit 单测验证 masker 和拦截（你的 surefire 已配了 UTF-8）。

---

## 3. 自检标准（不通过不许进 Day81）

- [ ] 场景①：日志里的问题是脱敏后的（`138****5678`），**原文从未离开你的服务**；
- [ ] 场景②：LLM 复述的号码被出站复扫打码；
- [ ] 场景③：违规内容被拦截（403 + 秒回 + 不调 LLM），日志无原文；
- [ ] 能口头讲清：脱敏为什么必须双向；规则顺序为什么从长到短；不可逆 vs 可逆脱敏的取舍；
- [ ] 能回答："客服用手机号查订单，脱敏后查不了怎么办？"（可逆占位符化 + 本地映射表）。

---

## 4. 关键踩坑清单（必背）

1. **只做入站不做出站** → LLM 复述用户给的敏感数据照样泄露。脱敏必须双向。
2. **正则没边界** → 16-19 位规则误伤时间戳/订单号。`(?<!\d)...(?!\d)` 词边界。
3. **身份证被银行卡规则先吃** → 从长到短排序是正确性问题不是风格问题。
4. **审计日志记违规原文** → 日志系统成了最大的泄露面。只记规则类别。
5. **拦截无理由无申诉** → 误杀没法补救（Day65：误杀比漏杀疼）。
6. **审核只有规则层就以为万事大吉** → 变体绕过（"爆发物品"换字）是常态，LLM 语义层是进阶必做项。
7. **打码数据进缓存忘了关联性** → 同一问题带不同号码 → 缓存里 N 条相似条目；且打码后的问题做缓存 key 反而更安全（Day72 交互）。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：数据安全双向管线（入站拦截+脱敏 → LLM → 出站复扫）挂进网关统一管控位，且日志全程无敏感原文。**至此 Day55 红线清单里的"内容安全"一项正式落地。**

**明日（Day81）**：权限管控 —— AI 能力的用户权限/数据权限/功能权限 + 操作审计。作业：完善权限体系 + 审计日志。Day45 的 owner 过滤、Day73 的 API Key、今天的拦截审计，全部会在那一天被收拢成完整的权限体系。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
