# Day 50：Function Calling —— 让 AI 调用你的函数（核心原理 + @Tool 单工具调用）

> 所属阶段：第二阶段 · 第 8 周「Function Calling 与业务系统对接」（Day50-56）
> 今日主题：从「让 AI 读文档回答」到「让 AI 动手调你的函数」—— 核心原理 + Spring AI 1.0 的 @Tool 注解 + 单工具全流程
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：第 7 周 RAG 已过关

---

## 0. 今天要解决的问题

你前 7 周做的一切，本质是「**让 AI 查资料、答问题**」—— 模型只能"说"，不能"做"。

但真实业务里，用户问的是：

```
「帮我查一下北京今天的天气」      → 不是查知识库，是要调天气 API
「订单 ORD-142 现在到哪了？」     → 不是问文档，是要查订单系统
「给张三发个通知」               → 不是生成文字，是要调发消息接口
```

这些问题的答案**不在任何文档里**，而在**你的业务系统里**。Function Calling 就是解决这个的：**让模型在需要时，主动"请求调用"你的 Java 方法，拿到真实数据再回答。**

**一句话理解质变：RAG 是"给 AI 一本书让它翻"；Function Calling 是"给 AI 一串按钮让它按"。** 从今天起，AI 从"聊天机器人"变成"生产力工具"。

---

## 1. 前置回顾 —— 你现在站在哪

你已经有：
- ✅ `ChatClient` 熟练使用（Day9-14）
- ✅ RAG 全链路（Day29-49）
- ✅ `@Tool` 相关 jar 已随 spring-ai-model 引入（无需加依赖）

今天要新学的，就是**给 ChatClient 挂上"工具"，让模型能调用你的方法**。

---

## 2. 核心知识点（40 分钟）

### 2.1 Function Calling 核心原理 —— 模型「不执行」，只「请求」

**最关键的一个认知（必考）：模型从来不执行你的函数，它只是输出一条「调用指令」。真正执行函数的是你的应用代码。**

完整流程分 6 步：

```
① 用户问：北京今天天气怎么样？
        ↓
② 你给模型一个「工具清单」：有个叫 getWeather 的工具，能查天气，参数是 city
        ↓
③ 模型判断：这个问题需要调 getWeather(city="北京")
   模型输出一条 tool_call（结构化指令，不是给人看的文本）：
   { "name": "getWeather", "arguments": {"city": "北京"} }
        ↓
④ 应用层（Spring AI）收到 tool_call → 反射调用你的 getWeather("北京") 方法
        ↓
⑤ 你的方法返回结果：北京今天晴，25 度
   Spring AI 把这个结果作为一条新消息追加回对话
        ↓
⑥ 模型看到工具结果，生成最终回答：「北京今天晴，25 度」
```

**这就是「触发逻辑 → 参数解析 → 结果返回」三步**：
- **触发逻辑**：模型根据工具的 `description` 判断"要不要调、调哪个"
- **参数解析**：模型根据参数的 `description` + JSON Schema 生成参数 JSON，Spring 反序列化成 Java 对象
- **结果返回**：你的方法返回值被序列化，作为工具结果回传模型，模型再生成最终答案

> 为什么强调"模型不执行"？因为很多人以为模型自己会去调天气 API。错。**模型只负责"决定调用 + 生成参数"，真正的 HTTP 调用、数据库查询、发消息，都是你的 Java 方法做的。** 理解这点，Function Calling 就通了。

### 2.2 Spring AI 1.0 的 `@Tool` 注解 —— 一个注解把方法变成工具

这是 Spring AI 1.0 的**重大升级**。旧版（0.8.x）要写一堆 `FunctionCallback` + `@Description` + `@JsonProperty`，1.0 简化为两个注解：

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WeatherTool {

    @Tool(description = "查询指定城市的实时天气，返回天气状况和温度")
    public String getWeather(
            @ToolParam(description = "城市名称，如北京、上海、深圳") String city) {
        // 真实场景：这里调天气 API（和风天气 / OpenWeatherMap）
        return "北京今天晴，气温 25°C";
    }
}
```

**两个注解的作用（对应原理三步）：**

| 注解 | 作用 | 影响原理哪一步 |
|---|---|---|
| `@Tool(description)` | 告诉模型「这工具是干嘛的」 | 触发逻辑（模型靠它判断要不要调） |
| `@ToolParam(description)` | 告诉模型「这参数是什么」 | 参数解析（模型靠它生成参数） |

**关键（必考）：`description` 写得好不好，直接决定模型调不调得对。** 描述模糊（`description="查询"`），模型可能判断错要不要调、参数填错。这是 Day50 之后反复要调的点。

### 2.3 工具注册 —— 最简方式 `defaultTools`

确认过的 1.0.3 正确 API，注册工具最简单是：

```java
ChatClient client = ChatClient.builder(chatModel)
        .defaultTools(new WeatherTool())   // 直接把 @Tool 对象丢进去
        .build();
```

Spring AI 会自动扫描 `WeatherTool` 里所有 `@Tool` 方法，包装成工具。

还有两种等价方式（后面 Day51 多工具时会用）：
```java
// 方式2：每次调用时注册
client.prompt().user("...").toolCallbacks(new WeatherTool()).call();

// 方式3：手动构建 MethodToolCallbackProvider（多工具精细控制时用）
MethodToolCallbackProvider.builder().toolObjects(new WeatherTool()).build();
```

### 2.4 单工具调用完整流程（今天作业就是它）

```
定义工具（@Tool 方法）→ 注册（defaultTools）→ 用户自然提问 → 模型触发 → Spring 执行 → 结果回传 → 生成答案
```

今天作业「天气查询」，把这 7 个环节全部跑通。

---

## 3. 实操作业（80 分钟）

> 目标：实现一个「天气查询」工具，让 AI 在用户问天气时**自动调用**你的方法，返回正确答案。

### 步骤 1：新建工具类（`tool/WeatherTool.java`）

新建包 `tool`，放工具类：

```java
package com.example.springai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 天气查询工具（Day50 第一个工具）。
 * 演示 Function Calling 最小闭环：模型判断要查天气 → 调用本方法 → 返回结果。
 */
public class WeatherTool {

    @Tool(description = "查询指定城市的实时天气，返回天气状况和气温")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海、深圳") String city) {

        // TODO 真实场景：调用和风天气 / OpenWeatherMap 等天气 API
        // 这里先用静态数据演示 Function Calling 的完整流程
        return switch (city) {
            case "北京" -> "北京今天晴，气温 25°C";
            case "上海" -> "上海今天多云，气温 28°C";
            case "深圳" -> "深圳今天阵雨，气温 30°C";
            default -> city + "今天晴，气温 24°C";
        };
    }
}
```

### 步骤 2：新建控制器（`controller/FunctionCallingController.java`）

```java
package com.example.springai.controller;

import com.example.springai.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Function Calling 演示（Day50）：天气查询。
 * 用户用自然语言问天气，模型自动判断并调用 WeatherTool。
 */
@RestController
@RequestMapping("/api/weather")
public class FunctionCallingController {

    private final ChatClient chatClient;

    public FunctionCallingController(ChatModel chatModel) {
        // 关键：把带 @Tool 的工具对象注册进 ChatClient
        this.chatClient = ChatClient.builder(chatModel)
                .defaultTools(new WeatherTool())
                .build();
    }

    /**
     * 自然语言天气问答。
     * GET /api/weather/ask?question=北京今天天气怎么样
     */
    @GetMapping("/ask")
    public Map<String, String> ask(@RequestParam String question) {
        String answer = chatClient.prompt()
                .user(question)
                .call()
                .content();
        return Map.of("answer", answer);
    }
}
```

> 注意：`question` 是**自然语言**，不是拼好的 city 参数。真正验证 Function Calling 的，就是模型能否从"北京今天天气怎么样"里**自己判断**要调 `getWeather(city="北京")`。

### 步骤 3：验证

启动项目，用 curl 测（**观察模型是否自动调用工具**）：

```bash
# 正常触发工具
curl "http://localhost:8080/api/weather/ask?question=北京今天天气怎么样"
# 期望：返回 "北京今天晴，气温 25°C"

# 换个城市，验证参数解析
curl "http://localhost:8080/api/weather/ask?question=帮我看看深圳的天气"
# 期望：返回 "深圳今天阵雨，气温 30°C"

# 问一个和天气无关的，观察模型不调用工具
curl "http://localhost:8080/api/weather/ask?question=1加1等于几"
# 期望：直接回答 2，不调用 getWeather
```

**关键验证点**：第 3 条"1加1等于几"—— 模型应该**不调用**天气工具，直接回答。这说明模型是"按需调用"，而不是无脑调。

---

## 4. 自检标准（不通过不许进 Day51）

- [ ] 用户问"北京天气"，AI 能**自动调用** `getWeather` 并返回正确天气；
- [ ] 换城市（深圳）能正确**解析参数** `city="深圳"`；
- [ ] 问无关问题（1+1），AI **不调用**天气工具，直接回答；
- [ ] 能在日志里**看到工具调用**（Spring AI 会打 tool call 日志，或你自己加日志）；
- [ ] 能口头讲清 6 步流程，尤其是「模型不执行函数，只请求调用，真正执行是应用层」。

---

## 5. 关键踩坑清单（必背）

1. **误以为模型会执行函数**：模型只输出 tool_call 指令，真正调 Java 方法的是 Spring AI。这是最大的认知误区。
2. **`description` 写太模糊**：`@Tool(description="查询")` 会导致模型判断不出该不该调、参数填啥。**描述要具体**，像文档注释一样写清楚。
3. **用旧版 API 查教程**：Spring AI 1.0 是 `@Tool` + `@ToolParam`；旧版 0.8.x 是 `FunctionCallback` + `@Description` + `@JsonProperty`。网上大量旧教程，照着抄会编译不过（你 Day44 已经吃过 `Filter.builder()` 的亏了）。
4. **工具方法参数要简单**：参数最好是 `String`、`int`、`long`、`boolean` 等基础类型，或简单 POJO。复杂嵌套对象会让模型生成参数出错。
5. **返回值要简洁**：返回 String 最简单。别返回一堆无关内容，模型会"淹没"在结果里。

---

## 6. 今日小结 + 明日预告

**今天你完成了**：从「AI 只会说」到「AI 能动手」的第一步 —— 用 `@Tool` 注解把 Java 方法变成模型可调用的工具，跑通了「触发 → 解析 → 执行 → 返回 → 生成」全流程。这是 Function Calling 的地基。

**明日（Day51）**：多工具编排 —— 给 AI 挂 3 个工具（查询、计算、发通知），让它**自动选择**调哪个；工具调用的异常处理、参数校验、安全控制。你会认识到：工具一多，"让 AI 选对工具"和"工具调错了怎么兜底"才是真正的难点。

---

*文档生成日期：2026-09-07 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
