# Day 45：RAG 工程化 —— 权限控制（多租户数据隔离）

> 所属阶段：第二阶段 · 第 7 周「RAG 工程化适配」（Day43–49）
> 今日主题：给知识库文档打上「归属标签」，让不同用户只能检索自己权限内的文档 —— 数据隔离
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21
> 前置：Day43 批量上传、Day44 docId/增量更新已跑通

---

## 0. 今天要解决的问题

你现在的知识库是**全局共享**的 —— 任何一次 `similaritySearch` 都能命中所有向量。真机上有一个致命场景：

```
你公司有多个部门都在用一个知识库：
- 财务上传了《报销制度.pdf》
- 研发上传了《代码规范.md》

现在研发用户搜索"报销"，向量库把财务的文档也检索出来回答了。
✅ 检索命中了 —— 但这是不该命中的！A 部门看到了 B 部门的数据。
```

**这就是数据越权。** 生产环境里知识库一旦多人用，「谁能看什么」就是第一个要堵的洞。很多人以为"知识库 = 存进去 + 能搜"就行，实际上不讲权限的知识库根本不敢上线。

Day45 的核心思想一句话：**给每个文档打上「归属标签」，检索时强制按当前用户过滤，让数据只能被有权限的人看到。**

---

## 1. 前置回顾 —— 你现在有什么、缺什么

**你已经有的（能复用的）：**
- `KnowledgeService` 里成熟的 `FilterExpressionBuilder` 用法，尤其 `searchWithComplexFilter` 里的 **`and()` 组合多条件**（Day38 学的）—— 权限过滤就是再叠一个 `eq`，完全同一套。
- `document_registry` 表（Day44 建的）—— 文档清单。

**你现在缺的（今天要补的）：**

| 位置 | 现状 | 问题 |
|---|---|---|
| `document_registry` 表 | 只有 doc_id/filename/category/file_hash/chunk_count/时间 | **没有 owner/部门字段**，无法知道文档是谁的 |
| 文档元数据（入库时） | docId/source/category/fileHash/createTime | **没打归属标签**，检索时无从过滤 |
| `SimilaritySearch`（检索） | 有的按 category 过滤，多数不过滤 | 不过滤 = 越权 |
| `RagConfig#QuestionAnswerAdvisor`（问答） | **完全没有过滤条件** | ⚠️ **最大漏洞**：问答入口谁也不认，谁都能问出任何文档 |

**重点看这个漏洞** —— `RagConfig.java` 里的：

```java
QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder()
                .similarityThreshold(0.6)
                .topK(5)
                .build())        // ← 没有任何 filterExpression！
        .build()
```

`DocumentController` 的 `/ask`、`/askWithSource` 都走这个 `ragChatClient`。**也就是说：你最核心的问答功能，现在是全库无差别检索的。** 今天必须在这里也加过滤，否则检索过滤了半天、问答那边漏了个大窟窿。

---

## 2. 核心知识点（40 分钟）

### 2.1 多租户数据隔离的三种典型方案

先懂理论，知道自己在做三层里的哪一层：

| 方案 | 做法 | 隔离级别 | 适用 |
|---|---|---|---|
| **① 应用层过滤**（今天做的） | 每个文档打 `owner` 元数据，检索 SQL 里 `WHERE owner=?` | 软隔离（同一张表） | 中小规模、租户友好的 SaaS |
| **② 独立 schema/表** | 每个租户一张表或一个 schema | 物理隔离 | 强合规、要审计 |
| **③ 独立数据库** | 每个租户一个库 | 最强隔离 | 数据量极大、合规极严 |

「隔离」是**越彻底越安全，但也越贵越难运维**。企业实际用的最多是**①**（一张表 + metadata 过滤 + 权限判断），因为：
- 灵活：一个用户可被授权多个部门/多类文档
- 省钱：共享资源，不用为每个租户开一套库
- 够用：配合应用层的鉴权、审计日志（Day82），安全达标

今天做 ①，你以后的进阶方向是 ② 和 ③。

### 2.2 归属标签放哪？—— metadata 里加 `owner`

你的向量是存在 `vector_store` 表的 `metadata` 字段（JSONB）里的。归属标签就放这，检索时用 Filter 匹配它。

两种归属粒度，按需选：

| 粒度 | 元数据字段 | 例子 | 说明 |
|---|---|---|---|
| **单用户**（最细） | `owner` | `"owner": "zhangsan"` | 精确到人，权限最细 |
| **部门/组**（常用） | `group` | `"group": "finance"` | 按团队授权，好维护 |

**生产推荐「owner + group 双标签」**：默认可看 `group`，特殊文档只 `owner` 可见。今天演示用 **`owner`**（简单直观），你理解后自己加 `group` 即可。

**权限模型**（一句话）：
```
能看到的文档 = owner == 当前用户  OR  group == 当前用户所在部门
```

用 Filter 表达就是（你会的 and/or 组合）：
```java
FilterExpressionBuilder b = new FilterExpressionBuilder();
Filter.Expression visible = b.or(
        b.eq("owner", currentUser),
        b.eq("group", currentUserGroup)
).build();
```

### 2.3 关键认知：**隔离要贯穿「入库 + 检索 + 问答」全链路**

新手最容易犯的错：只在检索接口加了 filter，问答那边忘了。记住这张图：

```
入库(打标签) → 检索(过滤) → 问答(过滤)
   owner=xx    WHERE owner=   Advisor里也要 filter
```

**一个都不能少。** 尤其 `QuestionAnswerAdvisor` 这种"方便到忘了它也会检"的入口，最容易漏。

### 2.4 用户从哪来？—— 信任边界（安全 vs 便利）

今天 Demo，模拟「当前用户」最简单的是**请求参数传入**（`?currentUser=zhangsan`）。但这有个**安全红线**：

> ⚠️ **绝对不能信任前端传的用户身份。** 如果拿参数当用户，用户把自己 public 改 zhangsan 就能看别人的文档 —— 等于没隔离。

真实生产，用户身份必须来自**可信来源**：
- `JWT token`（从 `/v1/chat` 等登录接口换来的）
- Session / Spring Security `Authentication` 对象
- 网关层注入的 `X-User-Id` 请求头（上游已鉴权）

**Day45 先用参数跑通隔离效果**（方便 curl 测），但文档、代码注释里都明确标出这个安全约束，Day82 第 7 周「权限管控」再换成真实的鉴权来源。这是「最小闭环」的正确姿势 —— 先让逻辑对，再套安全壳。

### 2.5 `RagConfig` 怎么动态传用户？—— Advisor 的动态 CallContext

`QuestionAnswerAdvisor` 的过滤得**按每次请求的用户**来，不能写死在 Bean 里。Spring AI 的做法是：

1. Advisor 的 searchRequest 里不写死 filter，而是用一个**占位**；
2. 每次调用时通过 `.advisors(c -> c.param("currentUser", "zhangsan"))` 传入动态值；
3. Advisor 内部用 `context.getName()`/`getEntry` 读取运行时参数拼到 filter 里。

> 具体实现：`QuestionAnswerAdvisor` 继承 `CallAdvisor`，它的 `advise` 方法里可以拿到当前 `ChatClient` 调用时的动态参数（存进 `ChatMemory` 或 advisor context）。如果直接配 filter 受限，**退路是原生 SQL**：你的 `KnowledgeService.rawQuery()` 已经演示过用 `pgVectorStore.getNativeClient()` 拿 JdbcTemplate 写原生 SQL —— 权限过滤最可靠的兜底就是那句 `WHERE metadata ->> 'owner' = ?`。今天先尽力用 Advisor 动态 filter，卡住就 SQL 兜底。

---

## 3. 实操作业（80 分钟）

> 目标：① 给 `document_registry` 加 owner 列；② 上传时打 owner 标签；③ 检索 + 问答都按 owner 过滤。用 `?currentUser=` 模拟用户（标注安全约束）。

### 步骤 1：`document_registry` 加 owner 列（改 `DocumentRegistryService`）

在 `init()` 的建表 SQL 里加 `owner` 列，并**加一条迁移**让已有表也补上（幂等）：

```java
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
                update_time BIGINT,
                owner       VARCHAR(100)          -- 新增：归属用户
            )
            """);
    // 迁移：老表如果没有 owner 列就补上（幂等）
    try {
        jdbc.execute("ALTER TABLE document_registry ADD COLUMN IF NOT EXISTS owner VARCHAR(100)");
    } catch (Exception e) {
        log.warn("owner 列可能已存在，忽略: {}", e.getMessage());
    }
}
```

`upsert` 方法加一个 `owner` 参数并写进 SQL：

```java
public void upsert(String docId, String filename, String category,
                   String fileHash, int chunkCount, String owner) {
    ...
    jdbc.update("""
            INSERT INTO document_registry
                (doc_id, filename, category, file_hash, chunk_count, create_time, update_time, owner)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (doc_id) DO UPDATE SET
                owner = EXCLUDED.owner
            """, docId, filename, category, fileHash, chunkCount, ct, now, owner);
}
```

### 步骤 2：上传时打 owner 标签（改 `DocumentProcessingService`）

你入库的 `processAndStore(Path file, String filename)` 目前没有 owner 概念，加个参数，并在 `attachIdentity` 里写入 metadata：

```java
public int processAndStore(Path file, String filename, String owner) throws Exception {
    ...
    docs = attachIdentity(docs, filename, category, fileHash, owner);
    vectorStore.add(docs);
    String docId = category + ":" + filename;
    registryService.upsert(docId, filename, category, fileHash, docs.size(), owner);
    return docs.size();
}

/** attachIdentity 加 owner 参数 */
private List<Document> attachIdentity(List<Document> docs, String filename,
                                      String category, String fileHash, String owner) {
    String docId = (category != null && !category.isBlank()) ? category + ":" + filename : filename;
    return docs.stream().map(d -> {
        d.getMetadata().put("docId", docId);
        d.getMetadata().put("source", filename);
        d.getMetadata().put("category", category);
        d.getMetadata().put("fileHash", fileHash);
        d.getMetadata().put("owner", owner == null ? "public" : owner);   // 新增：归属
        d.getMetadata().put("createTime", System.currentTimeMillis());
        return d;
    }).toList();
}
```

> **注意**：`processAndStore` 的调用方 `BatchDocumentProcessor`（批量上传）和 `DocumentManagementService`（更新）都要跟着改传 owner。批量上传走 `BatchDocumentService.submit(List<MultipartFile>, String owner)`，一路把 owner 带进去。

### 步骤 3：检索过滤器（改 `DocumentController` / `VectorController` 的 search）

把之前**字符串拼**的 `filterExpression("category == 'xxx'")` 换成**类型安全 + 带 owner 权限**的：

```java
@GetMapping("/vector/search")
public List<Map<String, Object>> search(
        @RequestParam String query,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String currentUser,   // ⚠️ 演示用，生产别信前端
        @RequestParam(defaultValue = "3") Integer topK) {

    FilterExpressionBuilder b = new FilterExpressionBuilder();
    Filter.Expression filter = null;

    // ① 权限过滤：只看到 owner == currentUser 的数据（无 currentUser 则视为只看 public）
    String owner = (currentUser == null || currentUser.isBlank()) ? "public" : currentUser;
    filter = b.eq("owner", owner).build();

    // ② 可选叠加 category 过滤
    if (StringUtils.isNotBlank(category)) {
        filter = b.and(filter, b.eq("category", category)).build();
    }

    List<Document> results = vectorStore.similaritySearch(
            SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.6)
                    .filterExpression(filter)      // ← 类型安全 Filter
                    .build()
    );
    ...
}
```

### 步骤 4：问答也要过滤（改 `RagConfig` + 调用处）—— 最关键

`RagConfig` 的 `QuestionAnswerAdvisor` 目前没有 filter。给它加一个**按 owner 动态过滤**的 advisor。由于 `@Bean(ragChatClient)` 是静态配置，最实用的做法是 `RagConfig` 里的 advisor 配置成从**运行时参数**取 owner 的 searchRequest。

Spring AI 1.0.3 的动态过滤写法（通过 advisor 读取调用时的 `ChatClient` 参数）：

```java
// 方案A：给 Advisor 配置一个 filter，再在调用时通过 .advisors() 传值
// 在 RagConfig 里改成：
QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder()
                .similarityThreshold(0.6)
                .topK(5)
                .build())
        .build()
// 然后在每次调用（DocumentController/ask、askWithSource）时注入当前用户：
ragChatClient.prompt()
        .user(question)
        .advisors(advisor -> advisor.param("currentUser", currentUser))  // 动态传参与
        .call()
        .chatResponse();
```

`QuestionAnswerAdvisor` 内部可以拿到 `advisor.param("currentUser")` 并拼进 filter。**如果 1.0.3 的 Advisor 动态 filter 支持不到位（这是常见坑），就用 SQL 兜底** —— 在 `RagConfig` 里不配过滤，而是把「检索」这一步单独抽出来：

```java
// 方案B（兜底，100%可控）：不用 Advisor 自动检索，自己检索后手动拼上下文再生成
// 你 Day 25/26 已经这么写过（streamsearch），这里加上 owner 过滤即可
List<Document> docs = vectorStore.similaritySearch(
        SearchRequest.builder().query(question).topK(5)
                .filterExpression(new FilterExpressionBuilder().eq("owner", owner).build())
                .build());
// 把 docs 拼成上下文，拼 prompt，再 chatClient 生成
```

> **Day45 的重点不是纠结 Advisor 动态参数有多优雅，而是「问答入口必须过滤」这个**思想**。** 如果你的 advisor 过滤没跑通，用方案B（自己检索 + 过滤 + 生成）100% 符合今天目标，还更直观。推荐先方案B跑通，再回头优化方案A。

### 步骤 5：验证（启动项目，curl 测隔离）

```bash
# ① 按用户上传两份文档
curl -X POST "http://localhost:8080/api/documents/batch/upload?currentUser=zhangsan" \
  -F "files=@D:/docs/zhangsan_private.pdf"
curl -X POST "http://localhost:8080/api/documents/batch/upload?currentUser=lisi" \
  -F "files=@D:/docs/lisi_private.pdf"

# ② zhangsan 检索，只应看到自己那篇
curl "http://localhost:8080/api/documents/vector/search?query=报销&currentUser=zhangsan"
# ✅ 命中 zhangsan 的，看不到 lisi 的

# ③ lisi 检索同样的词，只应看到 lisi 的
curl "http://localhost:8080/api/documents/vector/search?query=报销&currentUser=lisi"
# ✅ 命中 lisi 的，看不到 zhangsan 的

# ④ 不传 user（默认 public），应看不到任何 private 文档
curl "http://localhost:8080/api/documents/vector/search?query=报销"
# ✅ 只命中 owner=public 的（或为空）

# ⑤ 问答也隔离
curl "http://localhost:8080/api/documents/ask?query=报销制度&currentUser=zhangsan"
```

---

## 4. 自检标准（不通过不许进 Day46）

- [ ] `document_registry` 表有了 `owner` 列，上传后能正确写入；
- [ ] 上传的文档向量 metadata 里有 `owner` 字段；
- [ ] `zhangsan` 检索，**看不到** `lisi` 上传的文档（命中结果不含 lisi 的）；
- [ ] 反之 `lisi` 看不到 `zhangsan` 的（**双向隔离**，不是单向）；
- [ ] 不传 `currentUser`（默认 public），检索不到任何 private 文档；
- [ ] `QuestionAnswerAdvisor` / 问答接口**也**按 owner 过滤了（不是只改检索接口，问答那条路检查过）；
- [ ] 能口头讲清：为什么用户身份不能信任前端参数、`and()` 组合 owner+category 怎么写。

---

## 5. 关键踩坑清单（必背）

1. **隔离要贯穿「入库+检索+问答」**：最容易漏的是 `QuestionAnswerAdvisor`（问答入口），光改检索接口就是漏网之鱼。
2. **别用前端参数当用户身份**：`?currentUser=` 只能做本地演示。生产必须用 JWT/Session/网关头，否则用户可以伪造身份看别人数据 —— 越权是最严重的漏洞。
3. **字符串拼 filter 有注入风险**：`"category == '" + category + "'"` 要换成类型安全 `FilterExpressionBuilder`，和 Day44 一并整改。
4. **`and()`/`or()` 组合别弄错优先级**：权限过滤是「owner==我 OR group==我部门」，给**一个或**；如果要叠加 category，是 `(owner==我) AND (category==x)`。先构建权限这一个 or，再 and 别的。
5. **owner 默认值要明确**：null / 空串处理成 `public` 还是不显示？建议明确约定：**没打 owner=public 全可见，打了 owner 只有本人可见**。别隐式空串当 "没权限"，容易漏数据。
6. **`DocumentRegistryService.upsert` 的 `queryForObject`**：Day44 已加了 try/catch，owner 列只是再叠一个参数，别把那个异常又搞回来。

---

## 6. 今日小结 + 明日预告

**今天你学会了**：给知识库做最基础的**数据隔离** —— metadata 打 owner 标签、检索与问答都按用户过滤、认识「应用层过滤 vs 物理隔离」三档方案。这让你从「能用」走向「敢给多人用」。

**明日（Day46 预告，第 7 周收尾）**：常见问题排查 —— 检索不到、答案不准、幻觉严重，这三类「知识库天天遇到的玄学问题」的定位思路。你会学会：怎么判断是「没检索到」还是「检索到了但答得烂」，以及加日志、看相似度、对比原始检索结果这三板斧。

---

*文档生成日期：2026-08-28 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
