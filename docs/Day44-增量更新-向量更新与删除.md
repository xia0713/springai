# Day 44：RAG 工程化 —— 增量更新（文档修改后的向量更新与删除）

> 所属阶段：第二阶段 · 第 7 周「RAG 工程化适配」（Day43–49）
> 今日主题：给文档建立「身份标识（docId）」，实现 删除 / 更新 / 改版 时向量的精准清理，杜绝重复和脏数据
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day43 的批量上传已跑通（`AsyncConfig` / `BatchDocumentProcessor` / `BatchDocumentService` 已落地）

---

## 0. 今天要解决的问题

你 Day43 已经把「灌进去」做成了异步批量 + 秒回 + 进度 + 重试。但你现在只有「新增文件」这一条路。

**先做个测试思维实验（动手前先想明白）**：

```bash
# 你现在的 /api/documents/vector/search 检索
curl "http://localhost:8080/api/documents/vector/search?query=采购流程&category=WOS"
```

现在同一个页面里，**你把 `WOS.pdf` 重新上传一次**（内容改了两个字）。会发生什么？

**答案是：坏的。** 旧 `WOS.pdf` 的向量还在库里，新 `WOS.pdf` 的向量又插进去。检索时同一个问题会同时命中两套 chunk —— 旧版本和新版本混在一起回答，越答越乱。

再看两个更常见的工作场景：

| 场景 | 你现在的系统能做吗 | 后果 |
|---|---|---|
| **删掉一个文档**（比如某份合同要下架） | ❌ 不能 | 向量还留在库里，检索时照样被回答，**数据泄露风险** |
| **更新一个文档**（改版、修订） | ❌ 不能 | 新旧版本共存，回答互相打架 |
| **重传同一个文件**（版本更新） | ❌ 不能 | 重复入库，越积越脏 |

Day44 的核心就一句话：**给每个文档发一个「唯一身份证（docId）」，让它能被精准地找到、删掉、替换。** 这就是「增量更新」。

---

## 1. 前置回顾 —— 你现在的身份体系有什么坑

看你 `DocumentProcessingService.processAndStore()`（Day43 写的）补充的元数据：

```java
String category = filename != null ? filename.replaceAll("\\.[^.]+$", "") : "unknown";
long createTime = System.currentTimeMillis();
docs = docs.stream().map(d -> {
    d.getMetadata().put("source", filename);      // 文件名，如 "WOS.pdf"
    d.getMetadata().put("category", category);    // 去扩展名，如 "WOS"
    d.getMetadata().put("createTime", createTime); // 毫秒时间戳
    return d;
}).toList();
```

三个字段各有问题，**都不能当身份标识**：

| 字段 | 当 docId 有什么问题 |
|---|---|
| `source`(文件名) | **文件名会变**（`采购流程.pdf` 改成 `采购流程v2.pdf`），变了就找不到旧向量了 |
| `category` | 太粗，一个 category 下几十个文件，删一个会误删整类 |
| `createTime` | **每次上传都不一样**，没法用它对应「同一个文档」 |

**所以必须引入一个「跨版本稳定不变」的字段 —— `docId`。** 这是今天所有代码的根基。

---

## 2. 核心知识点（40 分钟）

### 2.1 docId 到底该用什么（关键的设计决策）

「增量更新」的成败，全看你 docId 选得对不对。常见 3 种做法：

| 方案 | 例子 | 能当稳定 docId 吗 | 说明 |
|---|---|---|---|
| **UUID 每次生成** | `9f3a-...` | ❌ 不行 | 每次重传都换新，永远找不到旧的，等于从零开始 |
| **内容 MD5** | `md5(文件内容)` | ❌ 单独不行 | 内容一改，docId 就变 —— 旧版向量彻底成孤儿，无法清理 |
| **业务逻辑键**（推荐） | `category + 文件名`，如 `WOS:采购流程.pdf` | ✅ 行 | 只要这个「逻辑文档」还是它，docId 就不变，能精准锚定它所有版本的 chunk |

**核心思想：docId 要「稳定」，用来锚定删除目标；内容是否变化，用另一个字段「fileHash」来判断。**

两者分工：
- **`docId`** = 逻辑身份，重传/改版都不变 → 删除、替换、更新的**锚点**
- **`fileHash`**（MD5/SHA-256 of 文件字节）= 内容指纹，变了说明真的改了 → 判断「要不要重新 embedding」（**省 token 的关键**）

> 这条是 Day44 的灵魂。记住：**docId 求「稳定」，fileHash 求「变化」。** 很多人搞反了，用 MD5 当 docId，结果永远删不干净 —— 这是最常见的坑。

### 2.2 Spring AI VectorStore 的删除能力（Spring AI 1.0.3 有这俩方法）

`VectorStore` 接口提供两个删除方法：

```java
public interface VectorStore {
    Optional<Boolean> delete(List<String> idList);           // ① 按 document 内置 id 删
    Optional<Boolean> delete(Filter.Expression filter);      // ② 按元数据过滤删（今天的重点）
    ...
}
```

**① `delete(List<String> idList)`** —— 按每个 `Document` 的**内置 id** 删。Spring AI 给每份 Document 自动生成一个 id（存在 `document.getId()`）。但问题：**你入库时没刻意控制这个 id，你拿不到它**，用它删不方便。

**② `delete(Filter.Expression)`** —— 按**元数据表达式**删，这才是工程上的正解。只要 docId 存在 metadata 里：

```java
vectorStore.delete(Filter.builder().eq("docId", "WOS:采购流程.pdf").build());
// 这行就把 document_registry 里 docId=xxx 的所有 chunk 全删了（pgvector DELETE WHERE metadata->>'docId'=...）
```

同时，你之前检索时已经用过**字符串版**过滤表达式：

```java
.filterExpression("category == '" + category + "'")   // 你现在的写法
```

⚠️ **这在生产是个隐患**：字符串拼接有注入风险（category 如果用户可控）。Day44 我们统一改成**类型安全的 `Filter.builder()`**，一组参数一个对象，Spring 帮你转义。这和你 Day27 学的「Prompt 注入防护」是同一个安全意识，只是这里是**向量元数据过滤**而已。

### 2.3 更新策略：先删后插（Upsert）

更新一个文档，最朴素也最可靠的模式是 **「先删后插」**：

```
更新流程：
1. 用 docId 查 registry，拿到旧 fileHash
2. 算新文件 fileHash，和旧的比
   ├─ 一样 → 内容没变，直接跳过（不删不插，省 token）✅
   └─ 不一样 → ③
3. delete(docId 过滤)     ← 删掉旧版本所有 chunk
4. 解析 → 清洗 → 分块 → 重新 embedding → add    ← 灌入新版本
5. 更新 registry 里的 fileHash / chunkCount / updateTime
```

为什么「先删后插」更好，而不是「读到旧的再改」？
- 向量不可变（向量只是文本的数学映射，没有"更新"概念）：**改文档 = 旧的作废，新的进来**。
- 先删后插天然**幂等**：哪怕中途挂了，重试时 `delete` 删的是「标」，`add` 插的是「新」，不会出现双重版本。
- 代价：删除到插入之间有**短暂的空窗**，期间检索可能查不到。生产上两种解法（先写后删 / 版本号路由），今天是 Demo 阶段用先删后插即可，记住这个坎，Day71 工程化再谈。

### 2.4 「文件清单」registry —— 为什么一定要一张关系表

**关键认知：向量库负责「相似度检索」，不负责「文档清单」。**

`VectorStore` 接口**没有**「列出所有文档」的 API —— 你只能按查询去搜，不能遍历出「库里现在有哪些文档、各有多少 chunk、最后什么时间更新的」。而文档管理（列表、删哪个、改哪个）恰恰需要这些。

**所以必须单独维护一张关系表**（你用 JDBC 很熟，直接 JdbcTemplate 建一涨 `document_registry`）：

```sql
CREATE TABLE IF NOT EXISTS document_registry (
    doc_id      VARCHAR(255) PRIMARY KEY,       -- 逻辑ID，主业锚点
    filename    VARCHAR(255) NOT NULL,           -- 文件名
    category    VARCHAR(100),                    -- 分类
    file_hash   VARCHAR(64) NOT NULL,            -- 内容指纹（MD5），判断是否真的变了
    chunk_count INT DEFAULT 0,                   -- 分块数
    create_time BIGINT,                          -- 首次入库时间
    update_time BIGINT                           -- 最近修改时间
);
```

这张表的作用：
- **列出**所有文档（`GET /documents/list`）→ 前端管理界面
- 存 `file_hash` → 更新时判断「内容变没变」，**没变就跳过，省 embedding token**（这是增量最实在的省钱点）
- 存 `chunk_count` → 展示/统计
- 删文档 → 先删 registry 这一行，再删向量（顺序：先删数据、后删向量，或反过来都行，但注意幂等）

---

## 3. 实操作业（80 分钟）

> 目标：给文档加上 `docId`，新增 3 个接口 —— `DELETE`(删) / `PUT`(更新) / `GET list`(列)。更新时用 fileHash 判断「变没变」，变了才先删后插。

### 步骤 1：改 `processAndStore`，给每个文档补 `docId`（改造 `DocumentProcessingService`）

在 `DocumentProcessingService` 里新增一个工具方法，并让 `process`(单文件) 与 `processAndStore`(批量) 都调用它。先加 import：

```java
import org.springframework.ai.vectorstore.filter.Filter;
```

新增方法（docId 生成规则：`category + ":" + filename`，逻辑文档不变则 docId 不变）：

```java
/**
 * 给文档补「身份元数据」。
 * docId：逻辑身份（category + 文件名），跨版本稳定 → 删除/更新的锚点
 * fileHash：内容指纹（不建议用 toHashCode，不同 JVM 可能不同），用 MD5 更稳，此处简化用 hashCode 演示
 * 注意：真正生产用 MD5/SHA-256，这里用 hashCode 只是演示「内容变化 hash 变」这个行为
 */
private List<Document> attachIdentity(List<Document> docs, String filename, String category, String fileHash) {
    String docId = category + ":" + filename;   // ✅ 稳定的逻辑ID，重传/改版都不变
    return docs.stream().map(d -> {
        d.getMetadata().put("docId", docId);     // 新增：身份锚点
        d.getMetadata().put("source", filename);
        d.getMetadata().put("category", category);
        d.getMetadata().put("fileHash", fileHash); // 新增：内容指纹，判断是否真变了
        d.getMetadata().put("createTime", System.currentTimeMillis());
        return d;
    }).toList();
}
```

然后在 `processAndStore` 里，把原来「手写 metadata 那一段」替换成调用它（并接收 fileHash）：

```java
@Retryable(retryFor = Exception.class, maxAttempts = 3,
           backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 8000))
public int processAndStore(Path file, String filename) throws Exception {
    Resource resource = new FileSystemResource(file.toFile());

    List<Document> docs = new TikaDocumentReader(resource).read();
    docs = textCleanTransformer.apply(docs);
    docs = splitter.apply(docs);

    String category = filename != null ? filename.replaceAll("\\.[^.]+$", "") : "unknown";
    String fileHash = md5(file.toFile());            // 新增：内容指纹
    docs = attachIdentity(docs, filename, category, fileHash);

    vectorStore.add(docs);
    return docs.size();
}
```

上面的 `md5(File)` 工具方法（用 JDK 自带的，不用引第三方）：

```java
private String md5(File f) throws Exception {
    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
    byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
        sb.append(String.format("%02x", b));
    }
    return sb.toString();
}
```

> 保留 `process(MultipartFile)` 单文件方法不动（别破坏 Day43 之前已调通的东西）。

### 步骤 2：文档注册表服务（新建 `service/DocumentRegistryService.java`）

用你已有的 `JdbcTemplate`（pom 里有 `spring-boot-starter-jdbc`）建表 + 增删查改。这是「文件清单」的落点。

```java
package com.example.springai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentRegistryService {

    private final JdbcTemplate jdbc;

    /** 启动时自动建表（幂等） */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS document_registry (
                    doc_id      VARCHAR(255) PRIMARY KEY,
                    filename    VARCHAR(255) NOT NULL,
                    category    VARCHAR(100),
                    file_hash   VARCHAR(128),
                    chunk_count INT DEFAULT 0,
                    create_time BIGINT,
                    update_time BIGINT
                )
                """);
    }

    /** 注册或更新一条文档记录（docId 相同则覆盖） */
    public void upsert(String docId, String filename, String category,
                       String fileHash, int chunkCount) {
        long now = Instant.now().toEpochMilli();
        // 先查是否已有 create_time，有就保留，没有就用当前时间
        Long createTime = jdbc.queryForObject(
                "SELECT create_time FROM document_registry WHERE doc_id = ?",
                Long.class, docId);
        long ct = (createTime != null) ? createTime : now;

        jdbc.update("""
                INSERT INTO document_registry
                    (doc_id, filename, category, file_hash, chunk_count, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (doc_id) DO UPDATE SET
                    filename = EXCLUDED.filename,
                    category = EXCLUDED.category,
                    file_hash = EXCLUDED.file_hash,
                    chunk_count = EXCLUDED.chunk_count,
                    update_time = EXCLUDED.update_time
                """, docId, filename, category, fileHash, chunkCount, ct, now);
    }

    /** 按 docId 查 fileHash（更新时判断内容是否真的变了） */
    public String getFileHash(String docId) {
        try {
            return jdbc.queryForObject(
                    "SELECT file_hash FROM document_registry WHERE doc_id = ?",
                    String.class, docId);
        } catch (EmptyResultDataAccessException e) {
            return null;   // 没这条记录
        }
    }

    /** 删除注册记录（注意：只删表，向量由调用方删） */
    public void delete(String docId) {
        jdbc.update("DELETE FROM document_registry WHERE doc_id = ?", docId);
    }

    /** 列出所有文档（管理界面用） */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                SELECT doc_id, filename, category, chunk_count, create_time, update_time
                FROM document_registry ORDER BY update_time DESC
                """);
    }
}
```

### 步骤 3：文档管理服务（新建 `service/DocumentManagementService.java`）—— 核心逻辑

这是今天的**主场**：把「删除 / 更新 / 列出」串起来。

```java
package com.example.springai.service;

import com.example.springai.exception.AiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentManagementService {

    private final VectorStore vectorStore;
    private final DocumentRegistryService registryService;
    private final DocumentProcessingService processingService;

    /**
     * 删除文档：先从向量库删掉该 docId 的所有 chunk，再删注册表记录。
     * 顺序建议：先删向量再删注册表 —— 如果删向量成功但删表失败，重试还是幂等的；
     * 反过来先删表会留下“无主向量”，检索会撞到已删除文档。
     */
    public void deleteDocument(String docId) {
        // ① 删向量（按 metadata.docId 过滤，把所有 chunk 一次删干净）
        vectorStore.delete(Filter.builder().eq("docId", docId).build());
        // ② 删注册表记录
        registryService.delete(docId);
        log.info("文档已删除: {}", docId);
    }

    /**
     * 更新文档（改版）：
     * 1. 算新文件 fileHash，和旧注册表里的比；一样就跳过（省 token）
     * 2. 不一样 → 先删旧向量，再重灌 + 更新注册表
     */
    public String updateDocument(String filename, MultipartFile file) throws Exception {
        String category = filename.replaceAll("\\.[^.]+$", "");
        String docId = category + ":" + filename;

        // 落临时文件算 hash（不能在请求线程外读 MultipartFile，参考 Day43 的坑）
        Path tmp = Files.createTempFile("update-", filename);
        file.transferTo(tmp);
        String newHash = md5(tmp.toFile());

        String oldHash = registryService.getFileHash(docId);
        if (newHash.equals(oldHash)) {
            // 内容没变：直接跳过，绝不重灌。这就是增量刷新最省钱的点。
            log.info("内容未变化，跳过更新: {}", docId);
            return "SKIPPED";   // 内容无变化
        }

        // 内容变了 → 先删旧的
        vectorStore.delete(Filter.builder().eq("docId", docId).build());

        // 再灌新的（processAndStore 内部会重新解析+embedding+写入）
        int chunks = processingService.processAndStore(tmp, filename);

        // 更新注册表
        registryService.upsert(docId, filename, category, newHash, chunks);

        // 清理临时文件
        Files.deleteIfExists(tmp);
        return "UPDATED:" + chunks;   // 返回新分块数
    }

    public List<Map<String, Object>> listAll() {
        return registryService.listAll();
    }

    private String md5(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

### 步骤 4：控制器加 3 个接口（改造 `DocumentController`）

在构造器里加 `DocumentManagementService`，然后加接口。**原来所有接口都别动。**

部分代码（在类里追加）：

```java
private final DocumentManagementService managementService;   // 构造器追加

/** 列出所有已注册文档 */
@GetMapping("/list")
public List<Map<String, Object>> listDocuments() {
    return managementService.listAll();
}

/** 删除文档（按 docId） */
@DeleteMapping("/{docId}")
public Map<String, String> deleteDocument(@PathVariable String docId) {
    managementService.deleteDocument(docId);
    return Map.of("message", "deleted", "docId", docId);
}

/** 更新文档：重新上传同一 docId，内容变了才先删后插 */
@PutMapping("/update")
public Map<String, String> updateDocument(@RequestParam("file") MultipartFile file) throws Exception {
    String filename = file.getOriginalFilename();
    String result = managementService.updateDocument(filename, file);
    return Map.of("result", result, "filename", filename);
}
```

> 注意：`@DeleteMapping("/{docId}")` 里的 `docId` 含中文/冒号（如 `WOS:采购流程.pdf`）时，URL 需要做 URL 编码。测试时用 curl 传 `--data-urlencode` 或手动编码；更稳妥的做法是前端传之前 encode，或者这里用 `@RequestParam` 接收避免路径编码坑。

### 步骤 5：验证（启动项目，按顺序 curl）

```bash
# ① 上传（沿用 Day43 的批量接口，docId 会自动带上）
curl -X POST "http://localhost:8080/api/documents/batch/upload" \
  -F "files=@D:/docs/WOS.pdf" && echo

# ② 列出文档，确认 registry 有记录
curl "http://localhost:8080/api/documents/list" && echo
# 应看到 doc_id = "WOS:WOS.pdf"，chunk_count = ?

# ③ 检索（能命中）
curl "http://localhost:8080/api/documents/vector/search?query=采购&category=WOS" && echo

# ④ 更新：改一个字后再传一次同一个文件
#    第一次传 → 应返回 "UPDATED:n"（内容变了，删旧 + 灌新）
#    第二次传一模一样的文件 → 应返回 "SKIPPED"（内容没变，省 token）
curl -X PUT "http://localhost:8080/api/documents/update" -F "file=@D:/docs/WOS_updated.pdf" && echo

# ⑤ 删除
curl -X DELETE "http://localhost:8080/api/documents/WOS%3AWOS.pdf" && echo
#    再检索同一个问题 → 应检索不到（向量已清空）
```

**自检核心**：第④步你传**两次相同文件**，第二次必须返回 `SKIPPED` —— 这证明「fileHash 判断内容变化」生效了，这是今天增量优化的真正价值（省 embedding token）。

---

## 4. 自检标准（不通过不许进 Day45）

- [ ] 上传后 `GET /documents/list` 能看到这条记录（`doc_id`、`filename`、`chunk_count`、`update_time`）；
- [ ] **重复上传同一个文件**（内容不变）→ 更新接口返回 `SKIPPED`，向量不重复入库，检索列表无重复；
- [ ] **改一个字后重传** → 更新接口返回 `UPDATED:n`，旧的被删、新的入库，`chunk_count` 更新；
- [ ] **删除后** → `list` 里没这条记录了，且 `vector/search` 检索该内容**返回空**（旧向量真没了）；
- [ ] 删一个文档，其他文档的检索不受影响（`delete(Filter)` 只删目标 docId 的 chunk）；
- [ ] 能口头讲清：为什么 docId 必须稳定、为什么用 fileHash 判断变化、为什么不能拿 MD5 当 docId。

---

## 5. 关键踩坑清单（必背）

1. **docId 求稳定，fileHash 求变化**：拿 MD5 当 docId → 内容一改 docId 就换，旧向量变孤儿永远删不干净。**这是今天最大的坑。**
2. **向量库没有「列清单」能力**：`VectorStore` 接口只支持按查询搜，不支持遍历。**文档清单必须单独建 registry 关系表**。
3. **删除顺序**：建议**先删向量、再删注册表**。先删注册表会留下「无主向量」，检索撞到已删除文档（数据泄露）。
4. **别信 `hashCode()` 当文件指纹**：不同 JVM / 不同运行可能不同。生产用 MD5/SHA-256（MD5 已不抗碰撞，审计场景用 SHA-256）。Demo 用 MD5 足够。
5. **字符串拼过滤表达式有注入风险**（`"category == '" + category + "'"`）：改用类型安全的 `Filter.builder().eq(...)`，和你 Day27 的注入防护是同一层意识。
6. **更新期间的空窗**：先删后插之间有短暂「检索不到」的窗口。生产用「先写新版本号路由 / 双写」解决，Demo 先接受。
7. **MultipartFile 依旧只在请求线程内用**：`updateDocument` 里先 `transferTo` 落地再算 hash，别把 MultipartFile 传进异步/别处（延续 Day43 的坑）。

---

## 6. 今日小结 + 明日预告

**今天你学会了**：给文档发「身份证（docId）」的工程方法论 —— docId 稳定锚定、fileHash 判断变化、registry 表管清单、先删后插保证幂等。这让你从「只能往里灌」进化到「能增、能删、能改」，是生产级知识库的分水岭。

**明日（Day45）**：权限控制 —— 不同用户只能检索自己权限内的文档。这是把「数据隔离」加进来：检索时过滤 `docId`/`category`/`owner`，让 multi-tenant 真正落地。你会认识到：**知识库一旦有权限，向量检索只是第一步，鉴权过滤才是让人敢上线的关键。**

---

*文档生成日期：2026-08-28 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
