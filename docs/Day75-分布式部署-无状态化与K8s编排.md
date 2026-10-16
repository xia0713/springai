# Day 75：分布式部署 —— 集群方案、会话一致性、容器化与 K8s

> 所属阶段：第三阶段 · 第 11 周「AI 应用工程化治理」（Day71-77）
> 今日主题：把单体 Demo 变成可水平扩展的多副本服务 —— 无状态化改造 + Dockerfile + 完整 K8s 编排
> 技术栈锚点：Spring Boot 3.4.4 + Spring AI 1.0.3 + Java 21 + Docker/K8s
> 前置：Day74 压测已证明底座健康；过关标准：**一键部署到 K8s，支持水平扩展**

---

## 0. 今天要解决的问题：副本一多，这些代码会坏

水平扩展的前提是**无状态**。我扫了你的代码 —— **7 个内存态资产**，多副本部署后逐个出事：

| 资产 | 多副本后的症状 | 严重度 |
|---|---|---|
| `MemoryConfig` 的 InMemory ChatMemory | 用户上一句在副本 A，下一句路由到副本 B → **对话失忆** | 🔴 用户可感知 |
| `ReportFlowTools.DRAFTS`（草稿 store） | 存草稿在副本 A，发邮件打到副本 B → **"草稿不存在"** | 🔴 功能直接坏 |
| `BatchTaskStore`（批量进度） | 查进度打到没任务的副本 → "任务不存在" | 🔴 功能坏 |
| `NotificationTool.SENT` / 审计列表 | 跨副本看不见彼此的记录 | 🟡 审计漏洞 |
| `CostOptimizedChatService.cache` | 各副本各缓存各的 → 命中率 ÷ 副本数 | 🟢 只降效不出错 |
| `ResilientChatService.answerCache` | 同上 | 🟢 同上 |
| `ChatService` 的多轮对话历史 | 同 ChatMemory | 🔴 用户可感知 |

**今天的主线：把"有状态"逐个外置或降级，让任意副本都能处理任意请求 —— 这就是"会话一致性"的本质。**

**先说一个好消息（架构选型的红利）**：你的向量库和 registry 都在**外部共享的 PostgreSQL** 里 —— 多副本天然共享同一份数据，**向量数据同步这个分布式最难的问题，在选型时就被消掉了**（如果当初用内嵌向量库，今天就得写数据同步）。外部存储 = 天然多副本安全。

---

## 1. 核心知识点（40 分钟）

### 1.1 会话一致性三方案（按推荐排序）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **① 外置存储**（推荐） | 会话/进度/草稿全落 PG 或 Redis | 真无状态，副本随意扩缩、宕机无损 | 一次改造 + 每请求多一次存储往返 |
| ② Sticky Session | Ingress 按 sessionId/cookie 把同一用户固定到同一副本 | 零改造 | 破坏无状态；副本宕机丢会话；负载不均 |
| ③ 接受降级 | 跨副本失忆 | 无 | 用户可感知 bug，**不可接受** |

**方案①的落地清单（综合项目执行，今天建立清单）：**
- ChatMemory → JDBC 仓库（Spring AI 有 `spring-ai-starter-model-chat-memory-repository-jdbc` starter，引入后自动换仓库；落地时按惯例用 javap 验证包名）
- `BatchTaskStore` → 落 PG（一张 batch_task 表，进度状态机照搬 Day43 设计）
- 工具的内存态（草稿/发送记录）→ 落库 + 审计（本来就该落，Day55 自查已发现）
- 缓存 → Redis（含 Day72 的语义缓存预留）

### 1.2 容器化：Dockerfile 的三个坑（都是你环境特有的）

1. **toolchains 陷阱（你必踩）**：你 pom 里 `maven-toolchains-plugin` 依赖本机 `~/.m2/toolchains.xml`（指向 `C:\Users\zt23006\.jdks\ms-21.0.11`）。容器里没有这个文件 → 构建直接失败。**解法：镜像里生成一份 toolchains.xml 指向容器自己的 JDK**
2. **分层构建**：Maven 构建层（大、含缓存）和 JRE 运行层（小）分开 —— 代码改动只重建运行层
3. **JVM 容器感知**：`-XX:MaxRAMPercentage=75` 让 JVM 按 Pod 的内存 limit 自适应堆大小，而不是默认吃 1/4

### 1.3 K8s 核心对象 → 你的项目逐个映射

| K8s 对象 | 干什么 | 你的对应物 |
|---|---|---|
| **Deployment** | 副本数、滚动更新、探针 | 应用本体，replicas: 2 起步 |
| **Service** | 副本的稳定访问入口（负载均衡） | ClusterIP:8080 |
| **ConfigMap** | 非敏感配置外置 | datasource url、gateway base-url |
| **Secret** | 敏感配置（base64 编码） | LLM/embedding API Key、PG 密码 |
| **HPA** | 按 CPU 自动扩缩副本 | min 2 / max 6 / 目标 70% |

**探针语义（K8s 的生死判定，配错会连环重启）：**
- **liveness**（活没活）：失败 → **重启容器**。只放"进程是否还活着"，别放重逻辑
- **readiness**（能不能接流量）：失败 → **摘除流量但不重启**。放"下游是否可用"（如 PG 连通）
- 都走 actuator：`/actuator/health/liveness` 和 `/actuator/health/readiness` —— **你 pom 里还没有 actuator，今天加**

### 1.4 多副本的隐性账（两个乘法）

```
① 连接池 × 副本数：Hikari 20 × 6 副本 = 120 > PG 默认 max_connections(100)
   → 调低每副本连接数 或 调高 PG 上限 —— 上线前必算
② 限流 × 副本数：每副本 20/s × 6 = 120/s 实际总量
   → 单机限流在多副本下失效；精确限流要 Redis/网关层分布式限流（Day82 展开今天先记概念）
```

---

## 2. 实操作业（80 分钟）

> 交付物：actuator 接入 + Dockerfile + 完整 K8s yaml。无 docker/集群环境下，自检走"语法校验"路径，真机部署标注待执行。

### 步骤 1：加 actuator（pom.xml）

```xml
<!-- K8s 健康探针（Day75）：liveness/readiness 端点 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

application.yaml 追加：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true     # 显式开启 /actuator/health/liveness 和 /readiness
```

### 步骤 2：Dockerfile（项目根目录新建，含 toolchains 修复）

```dockerfile
# ===== 构建层：Maven + JDK21 =====
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src

# ⚠️ 坑修复：pom 用了 toolchains 插件，容器里没有 toolchains.xml 会构建失败
#    生成一份指向容器自身 JDK 的 toolchains.xml
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n <toolchain>\n  <type>jdk</type>\n  <provider>default</provider>\n  <configuration>\n   <version>21</version>\n   <jdkHome>%s</jdkHome>\n  </configuration>\n </toolchain>\n</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml

RUN mvn -q package -DskipTests

# ===== 运行层：只有 JRE，体积小 =====
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/SpringAI-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
```

> 注意：配置全部走 K8s 的 ConfigMap/Secret 注入（Spring 环境变量覆盖），镜像里不放 application.yaml 的敏感值。

### 步骤 3：完整 K8s 编排（新建 `k8s/app.yaml`，一个文件五种对象）

```yaml
# ===== 1. Secret：敏感配置（生产用 sealed-secrets/vault，演示用明文 base64）=====
apiVersion: v1
kind: Secret
metadata:
  name: ai-app-secret
type: Opaque
stringData:
  SPRING_DATASOURCE_PASSWORD: "xia0713"
  SPRING_AI_OPENAI_API_KEY: "sk-RtUONsbppcygBmXyRI56E1evZVh9sqhAwYtphlK0Jxjey2tv"
  APP_AI_GATEWAY_API_KEY: "demo-ai-key-456"
---
# ===== 2. ConfigMap：非敏感配置 =====
apiVersion: v1
kind: ConfigMap
metadata:
  name: ai-app-config
data:
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/ai_db"
  SPRING_DATASOURCE_USERNAME: "postgres"
  SPRING_AI_OPENAI_BASE_URL: "https://ai-gateway.ztn.cn"
  # LLM 场景连接占用时间=生成时间，连接池按 1.4 节的乘法收紧
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "10"
---
# ===== 3. Deployment：2 副本 + 探针 + 资源限额 =====
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-knowledge-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ai-knowledge-app
  template:
    metadata:
      labels:
        app: ai-knowledge-app
    spec:
      containers:
        - name: app
          image: your-registry/ai-knowledge-app:1.0.0   # 替换为你的镜像仓库地址
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: ai-app-config
            - secretRef:
                name: ai-app-secret
          resources:
            requests:
              memory: "768Mi"
              cpu: "500m"
            limits:
              memory: "1.5Gi"
              cpu: "1000m"
          # 探针：语义必须分清（1.3 节）—— liveness 只判活，readiness 判流量
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60      # Spring 启动慢，给足预热
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 40
            periodSeconds: 5
---
# ===== 4. Service：副本的稳定入口 =====
apiVersion: v1
kind: Service
metadata:
  name: ai-knowledge-svc
spec:
  type: ClusterIP
  selector:
    app: ai-knowledge-app
  ports:
    - port: 8080
      targetPort: 8080
---
# ===== 5. HPA：按 CPU 自动水平扩展（过关标准的核心）=====
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ai-knowledge-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ai-knowledge-app
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

### 步骤 4：部署路径（按你的环境三选一）

```bash
# 路径 A：有 K8s 集群 → 真部署（自检原文路径）
docker build -t your-registry/ai-knowledge-app:1.0.0 .
docker push your-registry/ai-knowledge-app:1.0.0
kubectl apply -f k8s/app.yaml
kubectl get pods -w        # 看 2 个副本 READY、滚动更新
kubectl scale deployment/ai-knowledge-app --replicas=4   # 手动扩缩验证水平扩展

# 路径 B：有 kubectl 无集群 → 语法校验
kubectl apply -f k8s/app.yaml --dry-run=client

# 路径 C：都没有（你当前环境）→ 把 yaml 贴给任何 K8s 校验器或 CI
#         部署环节标注"待有集群环境执行"，改造清单（1.1 方案①）先行落地
```

---

## 3. 自检标准（不通过不许进 Day76）

- [ ] actuator 引入，探针端点可访问（本地 `curl localhost:8080/actuator/health/liveness`）；
- [ ] Dockerfile 完整且能讲清 toolchains 陷阱的修复原理；
- [ ] K8s yaml 五对象齐全，探针语义正确（liveness ≠ readiness）；
- [ ] 无状态化体检表 7 项资产逐个给出外置方案（ChatMemory→JDBC / 进度→PG / 工具态→落库 / 缓存→Redis）；
- [ ] 能口头讲清：为什么向量库外置消掉了数据同步问题；连接池×副本数的乘法账；liveness 配错的后果（连环重启）。

---

## 4. 关键踩坑清单（必背）

1. **liveness 里放重逻辑** → 下游抖动被判"死了"→ K8s 连环重启 → 雪崩。liveness 只判进程死活，下游检查放 readiness。
2. **toolchains 插件在容器里失败** → 本机能构建 ≠ 镜像里能构建。环境假设要显式化（今天在镜像里生成 toolchains.xml）。
3. **InMemory 状态是最隐蔽的多副本炸弹** → 单测全过、单机全通，上集群就"薛定谔的 bug"（打哪个副本决定行为）。**无状态化体检要在写代码时做，不是部署时。**
4. **连接池乘法** → 每副本 Hikari × maxReplicas > PG max_connections = 全员等连接。部署前算这笔账。
5. **单机限流在多副本下失效** → 限流上限 = 每副本上限 × 副本数，真限流要集中式（Redis/网关）。
6. **Secret 不是加密** → 只是 base64；生产用 sealed-secrets/vault + RBAC。
7. **探针 initialDelaySeconds 太短** → Spring 启动 20-40s，探针过早开始判死刑 → 启动期被反复重启（CrashLoopBackOff 经典成因）。

---

## 5. 今日小结 + 明日预告

**今天你完成了**：无状态化体检（7 项资产逐个定罪与改造方案）+ 容器化 Dockerfile（含你环境特有的 toolchains 陷阱修复）+ 五对象 K8s 编排（探针/资源/HPA 全套）。你的项目现在具备了"水平扩展"的形态 —— 虽然真正的无状态化落地（1.1 方案①）排进综合项目。

**明日（Day76）**：灰度发布与版本管理 —— 提示词/模型/功能的灰度切换、A/B 测试、迭代与回滚。作业：实现提示词的多版本管理与灰度切换。Day73 的网关门面正好是灰度路由的最佳挂载点。

---

*文档生成日期：2026-09-08 · 技术版本：Spring Boot 3.4.4 / Spring AI 1.0.3 / Java 21*
