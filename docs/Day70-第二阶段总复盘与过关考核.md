# Day 70：第二阶段总复盘 + 阶段过关考核

> 所属阶段：第二阶段「核心能力期」（Day29-70）**收官考核日**
> 今日主题：不学新东西 —— 四项能力验收 + GitHub 仓库整理 + 5 分钟答辩自讲
> 考核要求（学习计划原文）：独立完善企业知识库 Demo，包含文档管理、智能问答、数据查询、基础 Agent 能力；有完整的 GitHub 仓库 + README 文档，能讲清架构、优化点、业务价值

---

## 0. 考核规则

1. **独立完成**：验收、README、自讲稿全部自己来（卡住可以查自己的文档，这不算作弊 —— 查自己的笔记就是工程师的日常）
2. **三个交付物**：① 四能力验收全通过（curl 证据）② 仓库根目录 README.md ③ 5 分钟自讲（对墙讲或写下来）
3. **过关线**：三个交付物齐 + 自检清单全勾

---

## 1. 第二阶段总复盘（先看清自己走了多远）

### 1.1 旅程图

```
Day29-35  RAG 基础全流程    解析→清洗→分块→向量化→检索→生成→溯源
Day36-42  RAG 效果优化      语义分块/混合检索/重排/查询改写/多路召回（+20%）
Day43-49  RAG 工程化        异步批量/docId增量/owner权限/排查方法论/评测体系
Day50-56  Function Calling  单工具→多工具→NL2SQL→API对接→流程编排→设计规范
Day57-63  Agent             ReAct引擎/双层记忆/行业Agent/多Agent/防护矩阵/合体
Day64-70  方案认知          客服/知识库/生成审核/RPA/行业差异/ROI/Demo到生产
```

### 1.2 你手里的资产清单

| 资产 | 位置 | 对应考核能力 |
|---|---|---|
| 异步批量上传 + 进度 + 重试 | `/api/documents/batch/upload` + BatchDocumentProcessor | 文档管理 |
| docId 增量更新 + registry 清单 | `/api/documents/update` `/list` `/delete` | 文档管理 |
| RAG 问答 + 来源溯源 + owner 隔离 | `/api/documents/ask` `/askWithSource` `/vector/search` | 智能问答 |
| 多路召回评测体系 | ChunkTest + RagGenerationEval + RagTestCorpus | 智能问答（质量证明） |
| NL2SQL 三层防线 + 销售统计 | `/api/nl2sql/ask` + demo_orders | 数据查询 |
| 手写 ReAct 引擎 + 防护矩阵 | SimpleReActAgent + `/api/react-agent/run` | 基础 Agent |
| 双层记忆 | MemoryTool + ChatMemory | 基础 Agent |
| 智能助手合体版 | `/api/smart-assistant/chat` | 全部能力的集成验收口 |
| 27 篇踩坑文档 + 设计规范 | docs/ | 答辩的弹药库 |

**结论：四项考核能力你全部已建成 —— 今天的考核是把"散落的能力"验收成"完整的作品"。**

---

## 2. 考核任务一：四能力验收清单（用 curl 拿证据）

> 逐条执行并记录结果。任何一条不过 = 修复后重验（这正是"生产前验收"的预演）。

### 能力①：文档管理
```bash
# 批量上传（异步秒回 taskId）
curl -X POST "http://localhost:8080/api/documents/batch/upload?currentUser=zhangsan" -F "files=@你的文档.pdf"
# 查进度到 SUCCESS → 列表可见 → 改一字重传返回 UPDATED → 再传返回 SKIPPED → 删除后检索为空
```
- [ ] 异步秒回 + 进度状态机 + 增量 SKIPPED/UPDATED + 删除后不可检索

### 能力②：智能问答
```bash
curl -G "http://localhost:8080/api/documents/askWithSource" --data-urlencode "question=退货需要什么条件" --data-urlencode "currentUser=zhangsan"
```
- [ ] 回答带来源；换 currentUser 检索结果隔离（owner 过滤生效）
- [ ] 评测数字可复现：`./mvnw test -Dtest=ChunkTest`（Recall@5）+ `RagGenerationEval`（准确率/幻觉率）

### 能力③：数据查询
```bash
curl -G "http://localhost:8080/api/nl2sql/ask" --data-urlencode "question=有多少订单还在待发货状态"
curl -G "http://localhost:8080/api/nl2sql/ask" --data-urlencode "question=把所有订单状态改成已签收"
```
- [ ] 数字与库一致；诱导写库被拒（三层防线），数据毫发无损

### 能力④：基础 Agent
```bash
# 多步任务
curl -G "http://localhost:8080/api/react-agent/run" --data-urlencode "task=查一下8月的销售情况，算一下平均每单金额，然后把结果通知张三"
# 熔断演示
curl -G "http://localhost:8080/api/react-agent/loop-demo" --data-urlencode "task=执行一次系统故障演练"
# 合体版：跨会话记忆 + 个性化
curl -G "http://localhost:8080/api/smart-assistant/chat" --data-urlencode "sessionId=m2" --data-urlencode "message=结合我的技术背景，说说这个月销售数据有什么值得关注的"
```
- [ ] 多工具自主串联（stepsUsed≥3）；熔断返回介入单不 500；跨会话 recall + 个性化回答

---

## 3. 考核任务二：README.md（仓库的门面）

> 在仓库根目录新建 `README.md`，按此模板写（每节都要有，内容用你自己的）：

```markdown
# Spring AI 企业知识库智能助手

> 基于 Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21 的企业知识库/智能助手系统：
> 文档管理 + RAG 问答（带溯源与权限隔离）+ 自然语言查数据 + 可防护的 ReAct Agent。

## 架构
（一张 ASCII 图：接入 → 智能助手（ReAct+防护矩阵）→ 工具面（RAG/SQL/订单/记忆）→ 存储（PgVector/PG））

## 核心功能
| 能力 | 接口 | 亮点 |
|---|---|---|
| 文档管理 | /api/documents/batch/upload 等 | 虚拟线程异步+进度+重试+docId增量 |
| 智能问答 | /api/documents/askWithSource | owner 权限隔离+来源溯源+评测体系 |
| 数据查询 | /api/nl2sql/ask | 三层防线（prompt/代码校验/只读账号）|
| 智能助手 | /api/smart-assistant/chat | 手写 ReAct+四层熔断+双层记忆 |

## 快速开始
（环境要求：JDK21/PostgreSQL+pgvector/API Key；配置说明；启动命令；3 条验证 curl）

## 技术亮点（挑 5 条，每条一句话+对应 docs/ 文档）
1. 手写 ReAct 循环：internalToolExecutionEnabled(false) + 步数熔断（docs/Day58）
2. 防护矩阵：步数/重复调用/耗时/异常四层熔断 + 人工介入单（docs/Day62）
3. NL2SQL 三层防线：prompt 软约束 → 代码硬校验 → 只读账号兜底（docs/Day52）
4. 评测体系：40 用例 × 5 版本 Recall 对比 + 生成准确率/幻觉率（docs/Day47）
5. 增量更新：docId 稳定锚 + fileHash 判变化 + 先删后插幂等（docs/Day44）

## 踩坑记录
（列 5 个最痛的，链接对应文档：Filter.builder()/MemoryAdvisor 400/密钥安全…）

## 已知限制与路线图
- 演示模式三把刀待收（owner 固定/内存记忆/种子数据）→ Day82 登录态+持久化
- 3072 维向量无索引 → Day74 性能优化
```

**README 的三条标准：陌生人 10 分钟能跑起来；技术亮点每条有文档背书；诚实写已知限制（这反而是加分项）。**

---

## 4. 考核任务三：5 分钟答辩自讲稿（三讲结构）

> 对墙讲一遍或写下来。这是"能讲清架构、优化点、业务价值"的落地形式。

**第一讲：架构（2 分钟）** —— 讲"为什么这么分"
```
"系统分四层：文档接入层解决'知识怎么进来'（异步批量+增量更新）；
检索问答层解决'怎么答得准'（混合检索+重排+评测驱动优化）；
工具执行层解决'怎么干活'（NL2SQL 三层防线 + 8 类工具）；
Agent 编排层解决'怎么自主完成任务'（手写 ReAct + 防护矩阵 + 双层记忆）。
每层的选型我都踩过坑：比如……（举 1 个坑）"
```

**第二讲：优化（2 分钟）** —— 用数字讲，不讲感觉
```
"检索质量：纯向量基线 → 加混合检索+重排，Recall@5 从 __% 到 __%（40 用例 5 版本对比）；
可靠性：Agent 从裸循环到四层熔断，死循环/异常/超预算全部可控，熔断产出人工介入单；
安全：NL2SQL 诱导写库测试通过，权限全链路 owner 过滤；
成本：月 token 成本 __ 元，大结果引用传递后上下文节省 __%"
```

**第三讲：价值（1 分钟）** —— 讲 Day68 的账
```
"以客服知识库场景算账：开发 6 万一次性 + 月运行 4 千，年化收益 18 万，
第一年 ROI 61%，回本 5.6 个月；自助率打七折仍为正。
验收点：试点 3 个月自助解决率 ≥50%，埋点数据验收。"
```

---

## 5. 过关标准（第二阶段毕业线）

- [ ] 四能力验收清单全部勾完（有 curl 证据）
- [ ] README.md 完成（三标准达标）
- [ ] 5 分钟自讲完成，三讲结构齐全
- [ ] 仓库已提交：所有代码 + docs/ 全部文档入库（`git add . && git commit`，需要推送远程再说一声我帮你）
- [ ] 能不看文档回答：为什么手写 ReAct 循环？NL2SQL 三层防线是什么？Agent 防护矩阵有哪四层？

**全部勾完 = 第二阶段（核心能力期）正式毕业。** 这 42 天你从"会调 API"走到了"有完整作品、有评测数据、有踩坑库、会算账" —— 这是质的跨越。

---

## 6. 第三阶段预告（Day71-98：生产级进阶 + 综合项目）

```
第 11 周（Day71-77）工程化治理：稳定性(Resilience4j)/成本优化/SDK封装/压测/K8s/灰度
第 12 周（Day78-84）可观测+安全：Prometheus监控/链路追踪/脱敏/注入防护/权限审计/大模型网关
第 13-14 周（Day85-98）综合实战：企业智能助手平台开发 + 作品集打磨（简历/博客/答辩）
```

第三阶段的关键词是**把你这个 Demo 变成"敢给同事用、敢写进简历、敢在面试白板讲"的生产级系统** —— 收演示三把刀、上监控、压测到 50 并发、写作品集。你现在的一切都是它的原材料。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
