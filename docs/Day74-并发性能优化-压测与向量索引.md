# Day 74：并发性能优化 —— 压测 50 并发、向量索引、流式输出

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：先测量后优化 —— 分层压测 + 向量索引（正面解决 3072 维无索引债）+ 并发配置 + 流式输出
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + PgVector + Java 21
> 前置：过关标准（学习计划原文）：**50 并发下接口响应 ≤2 秒，成功率 ≥99%**

---

## 0. 先盘点你欠下的性能债（都在你自己代码里写着）

| # | 隐患 | 证据 | 触发条件 |
|---|---|---|---|
| 1 | **3072 维向量无索引 = 全表暴力扫描** | 你 `application.yaml` 的注释："3072 维超过 pgvector HNSW/IVFFlat 的 2000 维上限，暂不建索引" | 向量少时无感；几万条明显变慢；几十万条灾难 |
| 2 | **HikariCP 默认 10 连接** | 你没配过连接池 | 50 并发里打到 PG 的请求排队等连接 |
| 3 | **LLM 网关配额** | 你的中转平台限流 | 50 并发全打 LLM = 上游 429 洪水 |
| 4 | 假流式风险 | `streamsearch` 若 `collectList().block()` 就是同步假流式 | 首字延迟 = 全量延迟 |

**今天的方法论一句话：先测量，后优化 —— 分层压测定位瓶颈层，再对症下药。**

---

## 1. 核心知识点（40 分钟）

### 1.1 分层压测 —— 别把三层瓶颈混成一锅粥

```
第 1 层  系统底座（缓存命中路径）：/api/cost/chat 同问题二连发
         → 验证 Tomcat/网关/序列化/连接池有没有自伤
第 2 层  DB 路径（向量检索不带 LLM）：/api/documents/vector/search
         → 验证 3072 维暴力扫描的真实代价
第 3 层  LLM 路径（真问答）：/api/ai-gateway/chat 新问题
         → 瓶颈在上游配额，我们只验证"客户端层没有自伤"
```

**统计口径（必背）：不看平均值看 P95** —— 长尾才是用户骂你的原因；同时看成功率、吞吐（RPS）。

### 1.2 向量检索性能：正面解决 3072 维无索引

**为什么慢**：没有索引时 pgvector 做精确暴力扫描（逐行算距离）—— 结果精确但 O(N) 线性慢。

**三条路（按推荐排序）：**

**路线 A：换低维 embedding 重建（推荐，一步到位）**
- 换 ≤2000 维的 embedding 模型（如 1024/1536 维），`index-type` 改回 HNSW
- 代价：全部文档重灌 + 评测基线重跑（Day47 的语料要重灌）
- 顺带收益：维度减半 = 存储/内存/计算全减半
- **选型提醒：先确认兼容性**（`SELECT extversion FROM pg_extension WHERE extname='vector';` 看 pgvector 版本）

**路线 B：pgvector 0.7+ halfvec 半精度索引（不换模型不重灌，但有坑）**
```sql
-- halfvec 上限 4000 维，3072 维可建 HNSW
CREATE INDEX ON vector_store USING hnsw ((embedding::halfvec(3072)) halfvec_ops);
```
- ⚠️ **大坑（诚实标注）**：索引要被用上，**查询表达式必须同类型匹配**。Spring AI 的 PgVectorStore 生成的查询是 `embedding <=> ?::vector`（全精度），与 halfvec 索引不匹配 → 索引建了也用不上。要走这条路必须自写检索 SQL（你有 `HybridSearchService` 写原生 SQL 的先例）
- 精度代价：halfvec 半精度，召回率略降（用 `halfvec_ops` 的 ef 参数补偿）

**路线 C：数据分片/分区**（生产演进方向）
- 按租户/时间/类别分区，每次扫描的量变小 —— 配合你 Day45 的 owner 过滤天然契合（分区裁剪 + 过滤表达式双层）
- 今天不实现，建立概念

**HNSW 一句话**：分层跳表思想的高维近似索引 —— 查得快但有召回损失。三参数权衡：
- `m`：每节点连接数（大=更准、更慢建、更占内存）
- `ef_construction`：建索引时的候选队列（大=索引质量高、建得慢）
- `ef_search`：查询时候选数（大=召回高、查询慢）—— 精度换速度的旋钮

### 1.3 并发配置三件套（50 并发前的必改项）

**① 请求处理层：Tomcat 虚拟线程**
```yaml
spring:
  threads:
    virtual:
      enabled: true    # Boot 3.2+：请求处理用虚拟线程，50 并发不占死平台线程
```
（Day43 你在异步池用过虚拟线程，这次开到请求处理层。）

**② 连接池：HikariCP（50 并发的隐形杀手）**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        # 默认 10！50 并发打 DB 会排队等连接
      connection-timeout: 3000     # 3 秒拿不到连接快速失败（别挂 30s）
```
关键认知：**LLM 调用期间绝不能占着 DB 连接等**（无事务的普通查询用完即还，检查代码别在事务里调 LLM）。

**③ 批量向量化**：`embed(List)` 本来就是批量接口（一次网络往返多文本）—— 你 Day43 的批量入库已经受益；别在循环里单条 embed（N 次往返 = N 倍延迟）。

### 1.4 流式输出的性能与内存

- **流式的真正价值：首字延迟（TTFT）**。全量返回 = 用户等 5-10s 见到第一个字；流式 = 首 token 即出，体感提升 5 倍+
- **内存控制**：流式天然不必在内存拼全量响应；`Flux<String>` + SSE 边生成边推
- **背压（backpressure）**：Reactor 的核心机制 —— 下游消费慢时通知上游减速，防止生产过快撑爆内存。你用 `Flux` 时框架默认带，但要警惕**假流式**：任何 `collectList().block()` 都把流式变同步（Day10 的坑）
- 检查你 `DocumentProcessingService.streamsearch1` —— 它返回 `Flux<String>` 是真流式 ✓

---

## 2. 实操作业（80 分钟）

> 目标：基线压测 → 配置调优 → 索引决策 → RAG 路径压测，50 并发数字说话。

### 步骤 1：压测脚本（Git Bash 直接可用）

```bash
# ===== 压测工具：50 并发 × 200 请求，输出 HTTP码 + 耗时 =====
# （Git Bash 的 GNU xargs 支持 -P 并行；无 xargs 用 & + wait 也行）
seq 1 200 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
  -G "http://localhost:8080/api/cost/chat" --data-urlencode "question=你好" > results.txt

# ===== 统计：成功率 / 平均 / P95 =====
awk '{n++; if($1==200) ok++; t[NR]=$2} END {
  asort(t);
  printf "总请求=%d 成功=%d 成功率=%.1f%% 平均=%.3fs P95=%.3fs\n",
    n, ok, ok*100/n, (function(){s=0;for(i=1;i<=n;i++)s+=t[i];return s/n})(), t[int(n*0.95)]
}' results.txt
```

> 注：awk 的 asort 需要 gawk；没有就把排序交给 `sort -n results.txt` 人眼看前几行也行。**先跑起来再追求统计优雅。**

### 步骤 2：基线压测（第 1 层：缓存命中路径）

```bash
# 先预热一次（填缓存），再压测 → 全部走缓存命中
curl -G "http://localhost:8080/api/cost/chat" --data-urlencode "question=你好"
# 然后跑上面的压测脚本
```
**预期**：成功率 100%、平均 <0.1s、P95 <0.5s —— 证明系统底座（Tomcat/网关/序列化）无瓶颈。**如果这里都过不了，先查底座（连接池/线程配置），别碰向量索引。**

### 步骤 3：并发配置落地（yaml）

```yaml
spring:
  threads:
    virtual:
      enabled: true
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000
```
改完重启，重跑步骤 2 —— 数字不应变差。

### 步骤 4：第 2 层压测（DB 路径）+ 索引决策

```bash
# 向量检索压测（不带 LLM，看暴力扫描的真实代价）
seq 1 100 | xargs -P 20 -I{} curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
  -G "http://localhost:8080/api/documents/vector/search" \
  --data-urlencode "query=退货" --data-urlencode "category=WOS" >> db_results.txt
```

**记录数字后做决策**：
- 语料几百条 + P95 <200ms → 暴力扫描暂时扛得住，索引列"数据量到 5 万条前做"（诚实的债务管理，不是逃避）
- 要现在解决 → **路线 A（换低维 embedding 重建）**：换 1024/1536 维模型 → 重灌语料 → `index-type: HNSW` → 重跑 Day47 评测确认召回没掉

### 步骤 5：第 3 层压测（LLM 路径）+ 解读

```bash
# 50 并发打真问答（新问题，不走缓存）
seq 1 50 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
  -G "http://localhost:8080/api/ai-gateway/chat" -H "X-API-KEY: demo-ai-key-456" \
  --data-urlencode "question=第{}号测试问题：退货流程是什么" > llm_results.txt
```
**预期与解读**：部分请求耗时会到秒级甚至遇到上游 429 —— **瓶颈在上游配额，不在你的代码**。这一层的合格标准是"我们客户端层没有自伤"（连接不堆积、无超时雪崩、429 被降级为友好提示）。这也正是 Day71 熔断 + 限流存在的意义 —— 压测时你会亲眼看到它们工作。

### 步骤 6（可选加分）：流式对比 TTFT

```bash
# 流式 vs 全量的首字延迟对比（同一个问题）
time curl -N -G "http://localhost:8080/api/documents/stream?query=退货流程" -o /dev/null
# 看 curl 的 time_starttransfer（TTFT） vs 全量接口的总耗时
```

---

## 3. 自检标准（不通过不许进 Day75）

- [ ] 第 1 层压测：50 并发缓存路径成功率 100%、P95 <0.5s（过关标准 ≤2s 大幅超越）；
- [ ] 并发配置落地并能讲清：为什么 HikariCP 默认 10 是 50 并发的隐形杀手；
- [ ] 第 2 层：记录了暴力扫描的真实数字，并给出索引决策（做/不做 + 理由 + 触发条件）；
- [ ] 第 3 层：知道瓶颈在上游配额，且观察到限流/熔断在压测中工作；
- [ ] 能讲清 HNSW 三参数权衡 + halfvec 的查询匹配坑；
- [ ] 能讲清流式的价值是 TTFT 而非总耗时。

---

## 4. 关键踩坑清单（必背）

1. **平均值骗人** → P95/P99 才是用户真实体感，长尾来自 GC/连接池排队/上游抖动。
2. **HikariCP 默认 10 连接** → 50 并发打 DB 全在排队，`Connection is not available` 超时 —— 第一嫌疑犯。
3. **压测打爆 LLM 网关配额** → 429 洪水把降级链全触发（这其实是好事：正好验证 Day71），但压测前先算配额。
4. **halfvec 索引建了用不上** → 查询表达式必须与索引同类型 cast；Spring AI 生成的查询不匹配，要走这条路就得自写 SQL。
5. **假流式** → `collectList().block()` 是同步全量，首字延迟毫无改善。
6. **压测数据量失真** → 3 条向量压不出暴力扫描的问题，用接近真实的数据量（几千条以上）才有意义。
7. **虚拟线程 pin 陷阱（进阶）** → Java 21 里 `synchronized` 块会钉住虚拟线程（JDK 24 才修复）；你的 `DocumentBatchTask.markCompleted` 的 synchronized 区间极小无碍，但别在虚拟线程里放长临界区。
8. **LLM 调用占着 DB 连接** → 无事务代码用完即还；千万别在 `@Transactional` 方法里调 LLM（连接被拽几十秒，连接池瞬间枯竭）。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：第一份用数字说话的性能报告 —— 分层压测定位瓶颈、并发配置三件套、3072 维索引的技术决策（做/不做+触发条件）。**"先测量后优化"从此成为你的默认动作。**

**明日（Day75）**：分布式部署 —— AI 应用的集群部署方案、会话一致性、向量数据同步、容器化部署与 K8s 适配。作业：编写完整的 K8s 部署 yaml，一键部署支持水平扩展。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
