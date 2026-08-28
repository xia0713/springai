# Day 43：RAG 工程化 —— 批量文档处理（异步任务 + 进度反馈 + 失败重试）

> 所属阶段：第二阶段 · 第 7 周「RAG 工程化适配」（Day43–49）
> 今日主题：把「一次上传一个文件、同步等到天荒地老」升级成「一次传 100 个文件、秒回 taskId、后台异步跑、随时查进度、失败自动重试」
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21

---

## 0. 今天要解决的问题

先看你现在代码的现状（`DocumentProcessingService.java` + `DocumentController.java`）：

```java
// 现状：HTTP 请求线程里一口气做完所有事，然后才返回
@PostMapping("/upload")
public UploadResult upload(@RequestParam("file") MultipartFile file) throws IOException {
    List<Document> chunks = processingService.process(file);  // 解析→清洗→分块→embedding→入库
    return new UploadResult(...);
}
```

这里藏了 3 个**生产环境会直接炸**的问题：

| # | 问题 | 后果 |
|---|---|---|
| 1 | **同步阻塞** | 一个文件要「Tika 解析 + 调用 embedding API（网络往返）+ 批量写入 PG」。100 个文件 = 几百秒，HTTP 请求早超时了，Tomcat 线程也被占满 |
| 2 | **无进度** | 前端点了上传，界面卡死，不知道跑到第几个、成功几个、失败几个 |
| 3 | **无重试** | embedding API 偶发超时（你用的 micuapi.ai 中转，网络抖动很常见），一个文件失败 = 整个请求报错，前功尽弃 |

本周的过关标准是 **「支持 100 份文档批量上传」**，Day43 就是打下这个地基。今天的三个关键词：**异步、进度、重试**。

---

## 1. 前置回顾（你现在有什么）

Day29–42 你已经建好了一条可用的单文件 RAG 链路，今天**不重写它，只给它加一层"工程化外壳"**：

```
已有：上传文件 → Tika 解析 → TextCleanTransformer 清洗 → TokenTextSplitter 分块 → 补元数据 → vectorStore.add()
今天：把这个链路搬进「异步线程池」，外面包一层「任务状态机 + 重试」
```

关键已有资产（今天会复用/改造）：
- `DocumentProcessingService` —— 解析→入库的完整链路（今天要拆出一个**可重试**的方法）
- `vectorStore`（PgVectorStore）—— `add(List<Document>)` 支持批量写入，`max-document-batch-size: 10000`
- 你 Java 版本是 **21**（`pom.xml` 里 `<java.version>21</java.version>`）—— 这是今天的大杀器，见 §2.3

---

## 2. 核心知识点（40 分钟）

### 2.1 为什么必须异步（先懂"为什么要做"）

一次文档处理 = **CPU 密集（Tika 解析、分块）+ I/O 密集（embedding API 网络调用）**，其中网络调用占大头（一个文件几十个 chunk，每个 chunk 都要调一次 embedding）。

同步模型下，你的 Tomcat 工作线程（默认 200 个）会全部卡在「等 embedding 返回」上。并发一上来，整个服务失去响应。

异步模型的核心思想一句话：**「HTTP 线程只负责收活、派活、回执，脏活累活交给后台线程池」**。

```
同步：  请求线程 [解析→清洗→分块→embedding→入库] → 返回结果（慢，一直占线程）
异步：  请求线程 [收文件→发任务→立即返回 taskId] → 后台线程池慢慢跑 → 前端轮询进度
```

### 2.2 Spring 异步任务三件套（`@EnableAsync` / `@Async` / 线程池）

Spring 的异步核心就三步：

1. **`@EnableAsync`**：标注在配置类上，开启异步能力。
2. **自定义线程池 Bean**：默认 Spring 会用一个简单的 `SimpleAsyncTaskExecutor`（每任务新建线程，生产不可用），所以必须自己配。
3. **`@Async("线程池名")`**：标在方法上，该方法调用就被丢进指定线程池。

**⚠️ 第一大坑（必考）：`@Async` 的自调用失效**

`@Async` 靠 Spring AOP 代理实现。如果你在**同一个类里**调用自己的 `@Async` 方法（`this.asyncMethod()`），代理不会生效，还是同步执行。正确做法：**把 `@Async` 方法放到独立的 Bean 里，通过注入来调用**（今天 §3.4 就是按这个拆的）。

### 2.3 虚拟线程（Java 21 的"最新"答案，今天重点）

传统线程池你得调 `corePoolSize`、`maxPoolSize`、队列长度，调不好就 OOM 或吞吐上不去。而文档处理是典型的 **I/O 密集** 场景 —— 线程大部分时间在等网络返回，不是在算。

**Java 21 虚拟线程（Virtual Threads）** 完美匹配这个场景：
- 虚拟线程由 JVM 调度，**几百万个都无所谓**，几乎不占系统资源；
- 一个 embedding 调用阻塞时，JVM 自动切走执行别的虚拟线程，不浪费；
- **不用再纠结线程池大小** —— 这就是"最新"带来的红利。

Spring Framework 6.1+ / Spring Boot 3.2+ 原生支持，`ThreadPoolTaskExecutor` 一行开启：

```java
executor.setVirtualThreads(true);
```

> 结论：**I/O 密集（文档处理、向量化）→ 虚拟线程；CPU 密集（纯计算）→ 传统固定线程池**。今天就用虚拟线程。

### 2.4 进度反馈：任务状态机设计

异步之后，前端靠**轮询**拿进度。你要设计一个清晰的任务状态：

```
PENDING（已提交）→ PROCESSING（处理中）→ SUCCESS / PARTIAL_SUCCESS / FAILED（终态）
```

每个任务（一个批次）需要维护这些字段：
- `taskId`：本次批次的唯一标识，提交时生成，返回给前端
- `total` / `completed`：文件总数 / 已完成数
- `successCount` / `failedCount`：成功 / 失败文件数
- `results`：每个文件的明细（文件名、成败、分块数、错误信息）
- 起止时间

进度存储方式：
- **本期用内存 `ConcurrentHashMap`**（简单，够用，进程重启丢失）—— 今天的作业就这么做；
- 生产进阶：存 Redis / 数据库，支持分布式、重启恢复（后面 Day71+ 工程化再补）。

### 2.5 失败重试：Spring Retry

重试策略要回答三问：**什么异常重试？重试几次？间隔多久？**

Spring Retry 用注解搞定，`@Retryable` 参数：
- `retryFor`：哪些异常才重试（**只重试可恢复的错误**：网络超时、限流 429；不重试不可恢复的：文件损坏、格式不支持）
- `maxAttempts`：最多尝试几次（含首次）
- `backoff`：退避策略（**指数退避** `multiplier` 最常用，避免重试风暴把 embedding API 打爆）

```java
@Retryable(
    retryFor = Exception.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000)  // 1s → 2s → 4s
)
```

**第二坑：重试 ≠ 无脑重复**。`vectorStore.add()` 不是幂等的 —— 同一个文件重试多次会插入重复向量。今天先接受这个（失败后重试大概率是"上次根本没写进去"），但脑子里要记住「幂等重试」这个概念，生产环境用「确定性文档 ID + 先删后插」解决，后面会专门讲。

---

## 3. 实操作业（80 分钟）

> 目标：新增一个 `/api/documents/batch/upload` 接口，支持多文件上传，秒回 taskId；再配一个查进度接口。核心逻辑全在后台异步跑，单文件处理带 3 次指数退避重试。

### 步骤 1：加依赖（pom.xml）

在 `<dependencies>` 里加两个：

```xml
<!-- Spring Retry：失败重试 -->
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
<!-- @Retryable 依赖 AOP，必须加 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

同时把 `application.yaml` 的上传限制放大（现在 `max-request-size: 10MB` 传不了 100 个文件）：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 20MB        # 单个文件上限
      max-request-size: 200MB    # 整个请求上限（批量上传必须放大）
```

### 步骤 2：线程池配置（新建 `config/AsyncConfig.java`）

```java
package com.example.springai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync          // 开启 @Async 能力
@EnableRetry          // 开启 @Retryable 能力
public class AsyncConfig {

    /**
     * 文档处理专用线程池。
     * 文档处理是 I/O 密集（Tika 解析 + embedding 网络调用），
     * 用 Java 21 虚拟线程最合适：几乎不占系统线程，天然支持高并发，不用调线程数。
     */
    @Bean(name = "documentTaskExecutor")
    public AsyncTaskExecutor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);      // Java 21 虚拟线程（Spring 6.1+ 支持）
        executor.setThreadNamePrefix("doc-");
        executor.initialize();
        return executor;
    }
}
```

> 对比记忆：传统写法是 `setCorePoolSize(4)` / `setMaxPoolSize(16)` / `setQueueCapacity(200)`。虚拟线程下这些全都不用管了 —— 这就是"最新"省掉的麻烦。

### 步骤 3：任务状态模型（新建 `model/DocumentBatchTask.java`）

```java
package com.example.springai.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DocumentBatchTask {

    public enum TaskStatus { PENDING, PROCESSING, SUCCESS, PARTIAL_SUCCESS, FAILED }

    public record FileResult(String filename, boolean success, int chunkCount, String error) {}

    private final String taskId;
    private final int total;
    private final long startTime = System.currentTimeMillis();

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile int completed = 0;
    private volatile int successCount = 0;
    private volatile int failedCount = 0;
    private volatile long endTime;

    // CopyOnWriteArrayList：多线程并发往里加结果也安全
    private final List<FileResult> results = new CopyOnWriteArrayList<>();

    public DocumentBatchTask(String taskId, int total) {
        this.taskId = taskId;
        this.total = total;
    }

    /** 每个文件处理完就调一次，自动累加计数并在全部完成时收敛终态 */
    public synchronized void markCompleted(FileResult result) {
        results.add(result);
        completed++;
        if (result.success()) {
            successCount++;
        } else {
            failedCount++;
        }
        if (completed >= total) {
            endTime = System.currentTimeMillis();
            if (failedCount == 0) {
                status = TaskStatus.SUCCESS;
            } else if (successCount == 0) {
                status = TaskStatus.FAILED;
            } else {
                status = TaskStatus.PARTIAL_SUCCESS;
            }
        }
    }

    // getters...
    public String getTaskId() { return taskId; }
    public int getTotal() { return total; }
    public TaskStatus getStatus() { return status; }
    public int getCompleted() { return completed; }
    public int getSuccessCount() { return successCount; }
    public int getFailedCount() { return failedCount; }
    public List<FileResult> getResults() { return results; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setStatus(TaskStatus status) { this.status = status; }
}
```

### 步骤 4：内存任务仓库（新建 `store/BatchTaskStore.java`）

```java
package com.example.springai.store;

import com.example.springai.model.DocumentBatchTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class BatchTaskStore {

    // 内存存储：taskId -> 批次任务。进程重启会丢，生产用 Redis/DB。
    private final ConcurrentHashMap<String, DocumentBatchTask> tasks = new ConcurrentHashMap<>();

    public void put(DocumentBatchTask task) {
        tasks.put(task.getTaskId(), task);
    }

    public DocumentBatchTask get(String taskId) {
        return tasks.get(taskId);
    }
}
```

### 步骤 5：拆出一个「可重试」的处理方法（改造 `DocumentProcessingService`）

在你的 `DocumentProcessingService` 里**新增**一个方法（原来的 `process(MultipartFile)` 单文件入口保留不动，避免破坏已有接口）：

```java
import org.springframework.core.io.FileSystemResource;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;

/**
 * 批量任务专用：解析 → 清洗 → 分块 → 向量化入库，整体可重试。
 * 用 @Retryable：embedding API 偶发超时/限流时自动重试 3 次（1s→2s→4s 指数退避）。
 */
@Retryable(
    retryFor = Exception.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000)
)
public int processAndStore(Path file, String filename) throws Exception {
    Resource resource = new FileSystemResource(file.toFile());

    // ① 解析（Tika）
    TikaDocumentReader reader = new TikaDocumentReader(resource);
    List<Document> docs = reader.read();

    // ② 清洗
    docs = textCleanTransformer.apply(docs);

    // ③ 分块
    docs = splitter.apply(docs);

    // ④ 补元数据
    String category = filename != null ? filename.replaceAll("\\.[^.]+$", "") : "unknown";
    long createTime = System.currentTimeMillis();
    docs = docs.stream().map(d -> {
        d.getMetadata().put("source", filename);
        d.getMetadata().put("category", category);
        d.getMetadata().put("createTime", createTime);
        return d;
    }).toList();

    // ⑤ 向量化入库
    vectorStore.add(docs);

    return docs.size();   // 返回分块数，供进度明细展示
}
```

> 需要补的 import：`java.nio.file.Path`、`org.springframework.core.io.FileSystemResource`、`org.springframework.retry.annotation.Retryable`、`org.springframework.retry.annotation.Backoff`。

**思考（写在作业注释里）**：为什么 `@Retryable` 要放在这个独立方法、而不是放在带 `@Async` 的方法上？—— 因为「异步」和「重试」是两个正交的关注点，分开更清晰；且 `@Async` 和 `@Retryable` 都是 AOP 代理，叠加在同一方法上行为容易出意外。

### 步骤 6：异步处理器（新建 `service/BatchDocumentProcessor.java`）

```java
package com.example.springai.service;

import com.example.springai.model.DocumentBatchTask;
import com.example.springai.model.DocumentBatchTask.FileResult;
import com.example.springai.store.BatchTaskStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDocumentProcessor {

    private final DocumentProcessingService processingService;
    private final BatchTaskStore taskStore;

    /**
     * 后台异步执行整个批次。
     * 注意：这个 @Async 方法在独立 Bean 里，被 BatchDocumentService 跨 Bean 调用，代理才生效。
     */
    @Async("documentTaskExecutor")
    public void processAsync(String taskId, List<Path> files, List<String> filenames) {
        DocumentBatchTask task = taskStore.get(taskId);

        for (int i = 0; i < files.size(); i++) {
            String name = filenames.get(i);
            FileResult result;
            try {
                // 内部自带 3 次重试，重试耗尽仍失败会抛异常
                int chunks = processingService.processAndStore(files.get(i), name);
                result = new FileResult(name, true, chunks, null);
            } catch (Exception e) {
                log.error("文件处理失败: {}", name, e);
                result = new FileResult(name, false, 0, e.getMessage());
            }
            task.markCompleted(result);   // 每个文件完成都更新进度

            log.info("批次 {} 进度 {}/{}（成功 {} 失败 {}）",
                    taskId, task.getCompleted(), task.getTotal(),
                    task.getSuccessCount(), task.getFailedCount());
        }
    }
}
```

### 步骤 7：批量服务（新建 `service/BatchDocumentService.java`）

```java
package com.example.springai.service;

import com.example.springai.exception.AiException;
import com.example.springai.model.DocumentBatchTask;
import com.example.springai.model.DocumentBatchTask.TaskStatus;
import com.example.springai.store.BatchTaskStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchDocumentService {

    private final BatchDocumentProcessor processor;
    private final BatchTaskStore taskStore;

    /**
     * 提交批量任务：先把文件落地到本地临时目录，再异步处理，立即返回 taskId。
     *
     * ⚠️ 第三大坑（必考）：为什么必须先落地？
     * HTTP 请求一结束，Servlet 容器会立刻删除 MultipartFile 对应的临时文件。
     * 如果直接把 MultipartFile 传给异步线程，处理时文件早没了，报 FileNotFoundException。
     * 所以要在【请求线程内】先把文件 transferTo 到自己的临时目录，再把 Path 交给异步线程。
     */
    public String submit(List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("至少上传一个文件");
        }

        String taskId = UUID.randomUUID().toString();
        DocumentBatchTask task = new DocumentBatchTask(taskId, files.size());
        task.setStatus(TaskStatus.PROCESSING);
        taskStore.put(task);

        // 请求线程内同步落地文件（安全），只存 Path 和文件名给异步线程
        Path tempDir = Files.createTempDirectory("doc-batch-" + taskId);
        List<Path> paths = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            Path target = tempDir.resolve(name == null ? "unnamed" : name);
            f.transferTo(target);
            paths.add(target);
            names.add(name);
        }

        // 跨 Bean 调用，@Async 真正生效；立即返回，不等处理完
        processor.processAsync(taskId, paths, names);

        return taskId;
    }

    /** 查进度 */
    public DocumentBatchTask getStatus(String taskId) {
        DocumentBatchTask task = taskStore.get(taskId);
        if (task == null) {
            throw new AiException("任务不存在: " + taskId);
        }
        return task;
    }
}
```

> 你项目里已有 `AiException`（在 `exception/` 包下，之前 Day10 应该定义过），直接复用。

### 步骤 8：控制器加两个接口（改造 `DocumentController`）

在现有 `DocumentController` 里追加（原有的 `/upload`、`/ask` 等**不要动**）：

```java
private final BatchDocumentService batchService;   // 构造器里加这个依赖

/**
 * 批量上传：多文件，秒回 taskId
 * 注意：这里要用 MultipartFile[] 或 List<MultipartFile> 接收多文件
 */
@PostMapping("/batch/upload")
public Map<String, String> batchUpload(@RequestParam("files") List<MultipartFile> files) throws IOException {
    String taskId = batchService.submit(files);
    return Map.of(
        "taskId", taskId,
        "total", String.valueOf(files.size()),
        "hint", "轮询 GET /api/documents/batch/" + taskId + " 查进度"
    );
}

/** 查询批次进度 */
@GetMapping("/batch/{taskId}")
public DocumentBatchTask batchStatus(@PathVariable String taskId) {
    return batchService.getStatus(taskId);
}
```

### 步骤 9：验证（启动项目，用 curl 测）

```bash
# 1. 批量上传 3 个文件（注意参数名是 files，可重复传）
curl -X POST "http://localhost:8080/api/documents/batch/upload" \
  -F "files=@D:/docs/a.pdf" \
  -F "files=@D:/docs/b.docx" \
  -F "files=@D:/docs/c.md"
# 立即返回：{"taskId":"xxxx-xxxx","total":"3", ...}

# 2. 轮询进度（用返回的 taskId）
curl "http://localhost:8080/api/documents/batch/xxxx-xxxx"
# 返回：{"taskId":"...","status":"PROCESSING","total":3,"completed":1,...}

# 3. 再查一次，直到 status 变成 SUCCESS / PARTIAL_SUCCESS / FAILED
```

---

## 4. 自检标准（不通过不许进 Day44）

- [ ] 上传 3 个文件，接口**秒回 taskId**，不卡住等处理完成；
- [ ] 轮询进度接口，能看到 `completed` 从 1 → 2 → 3 递增，最终 `status` 收敛到 `SUCCESS`；
- [ ] 每个文件的 `results` 明细里能看到 `chunkCount`（分块数）正确；
- [ ] 断网/填错 embedding key 制造一次失败，能观察到**重试 3 次**（日志里 3 次调用）后才标记 `FAILED`，而不是一次就放弃；
- [ ] 混传「1 个正常文件 + 1 个坏文件」，最终 `status = PARTIAL_SUCCESS`，好的照常入库、坏的标记失败，互不影响；
- [ ] 能口头讲清 **3 个坑**：`@Async` 自调用失效、MultipartFile 临时文件被删、重试非幂等。

---

## 5. 关键踩坑清单（必背）

1. **`@Async` 自调用失效**：同类的 `this.asyncMethod()` 不走代理 → 把 `@Async` 方法放到独立 Bean。
2. **MultipartFile 临时文件被删**：请求结束容器删临时文件 → 必须请求线程内 `transferTo` 到自己的目录再异步。
3. **重试非幂等**：`vectorStore.add()` 重复调用会插重复向量 → 记住"确定性 ID + 先删后插"，后面专门处理。
4. **`@Retryable` 也靠 AOP**：同理，被重试的方法也要通过代理调用（我们放进了 `DocumentProcessingService`，由 `BatchDocumentProcessor` 跨 Bean 调用，正确）。
5. **embedding API 限流**：批量处理时虚拟线程并发度很高，别把中转平台的 embedding 接口打爆，必要时要加限速（Day74 并发优化再展开）。
6. **内存任务仓库会丢**：`ConcurrentHashMap` 进程重启即失，生产换 Redis/DB。

---

## 6. 今日小结 + 明日预告

**今天你学会了**：把一条"同步单文件"链路，用「虚拟线程线程池 + 任务状态机 + Spring Retry」包成了"异步批量 + 秒回 + 进度可查 + 失败可重试"的工程化版本。这背后是你 Java 微服务经验（线程池、异步、重试）在 AI 场景的一次落地 —— 这就是"Java 本位"的差异化竞争力。

**明日（Day44）**：增量更新 —— 文档修改后，向量怎么更新、怎么删旧的、怎么避免重复。这是批量处理的"另一半"，今天只解决了"灌进去"，明天解决"改起来"。

---

*文档生成日期：2026-08-28 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
