# Day 51：Function Calling —— 多工具编排（自动选择 + 异常降级 + 参数校验）

> 所属阶段：第二阶段 · 第 8 周「Function Calling 与业务系统对接」（Day50-56）
> 今日主题：给 AI 挂 3 个工具让它自动选；工具挂了怎么友好降级；参数怎么校验、什么操作不能开放
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day50 `@Tool` 单工具已跑通

---

## 0. 今天要解决的问题

昨天你跑通了单工具（天气查询）。但真实业务里，用户的问题五花八门，一个工具根本不够：

```
「ORD-142 到哪了？」            → 该调 查订单
「3.7 乘 12.5 是多少？」        → 该调 计算
「给张三发个通知说明天开会」     → 该调 发通知
```

多工具一上，立刻冒出三类新问题（今天的三大主题）：

| # | 问题 | 后果 |
|---|---|---|
| 1 | **模型选错工具** | 用户查订单，它去算数学 |
| 2 | **工具调用失败** | 查了不存在的订单号 / 除零 —— 异常直接把 HTTP 请求炸掉 |
| 3 | **危险操作裸奔** | "发通知"是写操作，AI 被诱导乱发怎么办 |

**一句话理解今天的核心：多工具的难点不在"注册多个"，而在"选得对 + 坏得了 + 兜得住"。**

---

## 1. 前置回顾 —— 你已有的

- ✅ `@Tool` / `@ToolParam` 注解（Day50）
- ✅ `ChatClient.builder(chatModel).defaultTools(new WeatherTool())` 注册（Day50）
- ✅ 已确认 1.0.3 API：`defaultTools(Object...)` 支持一次传多个工具对象

今天全用这些已验证的 API，只新增一个：**异常处理器**（已查 jar 确认，见 §2.3）。

---

## 2. 核心知识点（40 分钟）

### 2.1 多工具自动选择 —— 模型靠什么选对？

注册多个工具就是一行：

```java
.defaultTools(new OrderTool(), new CalculatorTool(), new NotificationTool())
```

模型选择工具的**唯一依据是每个工具的 `description` + 参数的 `description`**。推理时，模型把「用户问题 + 所有工具的描述清单」放在一起判断：这个问题该用哪个工具、传什么参数。

所以多工具场景下，`description` 的写法要升级——**必须互相"区分得开"**：

```
❌ 差的写法（三个工具都写"查询"）：
   查订单 → description="查询"
   算数   → description="查询"
   发通知 → description="查询"
   → 模型根本分不清，随机选

✅ 好的写法（各说各的边界）：
   查订单 → "根据订单号查询订单的物流状态和预计送达时间"
   算数   → "计算两个数字的四则运算结果"
   发通知 → "给指定收件人发送一条通知消息。仅在用户明确要求发送通知时使用"
   → 边界清晰，模型一眼选中
```

**进阶技巧：在 description 里写「什么时候不用我」**（如发通知那条的"仅在明确要求时使用"）—— 这是抑制误触发的最有效手段。

### 2.2 顺序调用 —— 一句话触发多步任务

模型不仅能选对一个工具，还能**串行调多个**：

```
用户：「查一下 ORD-142 到哪了，然后把结果通知张三」
  ↓
模型第 1 步：调 getOrderStatus("ORD-142") → 得到"已发货，预计 8/20 到达"
  ↓
模型第 2 步：把上一步的结果作为参数，调 sendNotification("张三", "您的订单...")
  ↓
最终回答：「已查询订单并通知张三」
```

关键认知：**中间结果不落你的代码里，模型自己"记住"上一步的工具返回值，拿去当下一步的参数。** Spring AI 的内部循环会自动处理「模型发起调用 → 执行 → 结果回传 → 模型继续 → …」直到模型不再发起调用。你什么都不用写。

### 2.3 异常处理与降级 —— 今天的核心 API（已查 jar 确认）

**问题**：用户查 `ORD-999`（不存在）、算 `100/0`（除零）—— 工具方法抛异常后，默认行为是**异常往上抛，HTTP 请求 500**。这不是用户想要的，更不是模型想要的（模型明明可以再试一次或友好转述）。

**Spring AI 1.0.3 的正规解法：`ToolExecutionExceptionProcessor`**

机制：工具方法抛出的异常会被 Spring AI 包成 `ToolExecutionException`，交给一个处理器决定怎么办：

```
ToolExecutionExceptionProcessor.process(异常) 返回 String
   ├─ alwaysThrow(true)  → 异常继续往上抛（默认/适合开发期调试）
   └─ alwaysThrow(false) → 把异常信息作为「工具结果」回传给模型
                            → 模型看到"错误说明"，生成友好回答（生产推荐）
```

注册成一个 Bean 即全局生效：

```java
@Bean
public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
    return DefaultToolExecutionExceptionProcessor.builder()
            .alwaysThrow(false)   // 工具报错 → 错误信息回传模型，模型友好提示
            .build();
}
```

**这就是「降级」的本质：让失败的信息流回模型，让模型决定下一步（重试 / 换参数 / 告知用户），而不是让系统崩溃。** 和你 Day71 之后要学的熔断降级是同一个思想，只不过这里发生在"模型-工具"这一环。

### 2.4 参数校验 —— 两道防线

**第一道：`@ToolParam(required = false)` 标注可选参数**（默认全必填）。模型生成参数时缺了必填项，Spring AI 会拦截。但别依赖它 —— 模型仍可能传错值。

**第二道（主力）：方法体内手动校验，返回明确错误提示**：

```java
@Tool(description = "根据订单号查询订单")
public String getOrderStatus(@ToolParam(description = "订单号，格式如 ORD-142") String orderId) {
    // 校验：格式不对 → 返回提示（不是抛异常），模型能看懂并转告用户
    if (orderId == null || !orderId.matches("ORD-\\d+")) {
        return "订单号格式不正确，应为 ORD-数字，例如 ORD-142";
    }
    ...
}
```

为什么"返回提示"比"抛异常"好？—— **提示会进入模型的上下文，模型能理解"参数错了"并用人类语言告诉用户该怎么改**；抛异常走的是降级通道，信息更粗。原则：**可预期错误用返回值，意外故障才抛异常**。

### 2.5 安全控制 —— 什么操作不能随便开放

3 个工具里「发通知」是**写操作**，和另外两个只读工具性质完全不同。原则：

| 原则 | 说明 |
|---|---|
| **只读优先** | 查询、计算随便开放；写操作（发通知、改数据、付款）从严 |
| **description 设门槛** | 写"仅在用户明确要求时使用"，抑制误触发 |
| **权限收窄** | 工具方法内部用固定的账号/模板，不给模型自由发挥的参数空间（如收件人白名单） |
| **人工介入** | 生产环境高危操作要"AI 生成草稿 → 人确认 → 执行"，Day62 专门讲 |

今天的 Demo：发通知工具加**收件人白名单**校验，白名单外直接拒绝 —— 这是"工具层安全"的最小实现。

---

## 3. 实操作业（80 分钟）

> 目标：3 个工具（查订单 / 计算 / 发通知）+ 异常降级 + 参数校验，用户不同问题 AI 自动选对工具，异常时友好提示。

### 步骤 1：3 个工具类（新建包 `tool`）

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/** 工具一：订单查询（只读） */
public class OrderTool {

    private static final Map<String, String> ORDER_DB = Map.of(
            "ORD-142", "订单 ORD-142（蓝牙耳机）：已发货，8/18 发出，预计 8/20 送达",
            "ORD-143", "订单 ORD-143（智能手表）：运输中，预计 8/22 到达",
            "ORD-145", "订单 ORD-145（双肩背包）：已签收"
    );

    @Tool(description = "根据订单号查询订单的物流状态和预计送达时间")
    public String getOrderStatus(
            @ToolParam(description = "订单号，格式如 ORD-142") String orderId) {
        // 参数校验第一道：格式
        if (orderId == null || !orderId.matches("ORD-\\d+")) {
            return "订单号格式不正确，应为 ORD-数字，例如 ORD-142";
        }
        // 参数校验第二道：存在性
        String info = ORDER_DB.get(orderId);
        return info != null ? info : "未找到订单 " + orderId + "，请确认订单号是否正确";
    }
}
```

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 工具二：四则运算（只读、无副作用） */
public class CalculatorTool {

    @Tool(description = "计算两个数字的四则运算（加减乘除）")
    public String calculate(
            @ToolParam(description = "第一个数字") double a,
            @ToolParam(description = "运算符，只能是 + - * / 之一") String op,
            @ToolParam(description = "第二个数字") double b) {
        double result = switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> {
                if (b == 0) throw new IllegalArgumentException("除数不能为 0");
                yield a / b;
            }
            default -> throw new IllegalArgumentException("不支持的运算符: " + op + "，只支持 + - * /");
        };
        return String.format("%s %s %s = %s", a, op, b, result);
    }
}
```

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** 工具三：发送通知（写操作！带白名单安全控制） */
public class NotificationTool {

    /** 收件人白名单：工具层安全控制的最小实现 */
    private static final Set<String> ALLOWED_RECEIVERS = Set.of("张三", "李四", "王五");

    /** 已发送的通知（演示用，生产要落库+审计） */
    public static final List<String> SENT = new CopyOnWriteArrayList<>();

    @Tool(description = "给指定收件人发送一条通知消息。仅在用户明确要求发送通知时使用，不要主动发送")
    public String sendNotification(
            @ToolParam(description = "收件人姓名，必须是公司通讯录里的人") String receiver,
            @ToolParam(description = "通知内容") String content) {
        // 安全控制：白名单外直接拒绝
        if (!ALLOWED_RECEIVERS.contains(receiver)) {
            return "拒绝发送：" + receiver + " 不在允许的收件人名单内";
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("通知内容为空");   // 意外故障 → 走异常降级
        }
        SENT.add(receiver + ": " + content);
        return "已成功发送通知给 " + receiver;
    }
}
```

### 步骤 2：异常降级配置（新建 `config/ToolConfig.java`）

```java
package com.example.springai.config;

import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfig {

    /**
     * 工具异常降级：工具抛异常时，不炸 HTTP 请求，
     * 而是把错误信息作为工具结果回传给模型，由模型生成友好提示。
     * alwaysThrow(false) = 降级模式（生产推荐）；true = 直接抛（调试期用）。
     */
    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .build();
    }
}
```

### 步骤 3：控制器（新建 `controller/AssistantController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.CalculatorTool;
import com.example.springai.tool.NotificationTool;
import com.example.springai.tool.OrderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多工具助手（Day51）：3 个工具自动选择。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final ChatClient chatClient;

    public AssistantController(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是企业助手，可以查订单、做计算、发通知。工具调用失败时，根据错误信息友好地告诉用户。")
                .defaultTools(new OrderTool(), new CalculatorTool(), new NotificationTool())
                .build();
    }

    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        return Map.of("answer", chatClient.prompt().user(question).call().content());
    }
}
```

### 步骤 4：验证（6 个场景，覆盖全部知识点）

```bash
# ① 选对工具：查订单
curl "http://localhost:8080/api/assistant/ask?question=ORD-142到哪了"

# ② 选对工具：计算
curl "http://localhost:8080/api/assistant/ask?question=3.7乘以12.5等于多少"

# ③ 选对工具：发通知（写操作）
curl "http://localhost:8080/api/assistant/ask?question=给张三发个通知，说明天上午十点开会"

# ④ 参数校验：订单号格式错 → 模型友好转述格式要求
curl "http://localhost:8080/api/assistant/ask?question=帮我查一下订单ABC123"

# ⑤ 异常降级：除零 → 工具抛异常 → 错误回传 → 模型友好提示（不炸500！）
curl "http://localhost:8080/api/assistant/ask?question=100除以0等于多少"

# ⑥ 安全控制：白名单外收件人 → 拒绝
curl "http://localhost:8080/api/assistant/ask?question=给赵六发通知说放假"

# ⑦ 顺序调用（进阶）：一句话触发两步
curl "http://localhost:8080/api/assistant/ask?question=查一下ORD-142的状态，然后把结果通知张三"
```

---

## 4. 自检标准（不通过不许进 Day52）

- [ ] 场景①②③：AI **各自调对工具**，结果正确；
- [ ] 场景④：订单号格式错，AI **友好提示**格式要求（而不是报 500 或瞎编）；
- [ ] 场景⑤：除零触发异常，请求**不报 500**，AI 回复里体现"除数不能为 0"之类的友好提示 —— **这条验证了异常降级真的生效**；
- [ ] 场景⑥：白名单外收件人被**拒绝发送**；
- [ ] 场景⑦：AI 自动**串行调两个工具**完成"查询+通知"；
- [ ] 能口头讲清：模型怎么选工具（description）、异常降级机制（ToolExecutionExceptionProcessor）、可预期错误用返回值 vs 意外故障抛异常。

---

## 5. 关键踩坑清单（必背）

1. **description 写得雷同** → 模型选错工具。多工具场景 description 必须互相区分得开，写清边界。
2. **以为异常会自动降级** → 默认 `alwaysThrow` 是炸请求的姿势。必须显式注册 `alwaysThrow(false)` 的处理器（今天已验证的 API）。
3. **所有错误都抛异常** → 可预期错误（格式错、不存在）应返回提示文本，让模型能理解并转述；只有意外故障才抛。
4. **写操作不设防** → 发通知/改数据类工具必须有白名单/确认机制，description 里写"仅在明确要求时使用"。
5. **工具返回大对象** → 返回值会占模型上下文，保持简洁的字符串。
6. **以为中间结果要自己存** → 顺序调用中，模型自己记住上一步返回值当下一步参数，Spring AI 内部循环处理，你不用写编排代码。

---

## 6. 今日小结 + 明日预告

**今天你完成了**：从"单工具"到"多工具助手"的跨越 —— 3 个工具自动选择（靠 description）、异常友好降级（ToolExecutionExceptionProcessor）、参数两道校验、写操作白名单。AI 现在是一个"能干活的助手"，不是"只会聊天的机器人"。

**明日（Day52）**：实战场景 **NL2SQL（自然语言转 SQL）** —— 对接业务数据库，"自然语言查数据"。表结构注入、SQL 语法约束、权限控制（禁止删改只允许查询）。这是 Function Calling 最经典、面试最爱问的落地场景。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
