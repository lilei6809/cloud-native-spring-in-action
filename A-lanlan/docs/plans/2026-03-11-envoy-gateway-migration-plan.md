# Envoy Gateway 渐进替换 Spring Gateway Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保留本地开发能力的前提下，将项目入口层从 Spring Gateway 渐进迁移到 Envoy Gateway，最终删除 `edge-server`。

**Architecture:** 先把现有 `edge-server` 职责拆分为“标准入口能力”和“应用特定能力”，再将标准入口能力逐步迁移到 Envoy Gateway 的 `Gateway`、`HTTPRoute` 和相关策略资源上。迁移过程中同时建设本地 `direct mode` 与 `envoy mode`，确保开发体验与线上演进路径都可持续。

**Tech Stack:** Kubernetes Gateway API、Envoy Gateway、Spring Boot 3.4.x、Spring Cloud Config、Redis、OpenTelemetry、Gradle

---

### Task 1: 固化迁移范围与责任边界

**Files:**
- Review: `edge-server/src/main/resources/application.yaml`
- Review: `config-repo/edge-server-k8s.yaml`
- Review: `edge-server/k8s/deployment.yaml`
- Review: `edge-server/k8s/ingress.yaml`
- Create: `docs/plans/2026-03-11-envoy-gateway-migration-design.md`

**Step 1: 校验设计文档是否覆盖当前入口职责**

检查以下职责是否都出现在设计文档中：

- 路由
- Retry
- Rate limit
- Circuit breaker
- Session
- Ingress
- Observability

**Step 2: 运行文本检查，确认设计文档包含这些关键词**

Run: `rg -n "路由|重试|限流|熔断|会话|Ingress|观测" docs/plans/2026-03-11-envoy-gateway-migration-design.md`
Expected: 输出多行命中结果

**Step 3: 如有遗漏，补充设计文档**

仅在设计文档缺项时更新该文件，避免实现阶段再回头补边界。

**Step 4: 提交文档快照**

```bash
git add docs/plans/2026-03-11-envoy-gateway-migration-design.md
git commit -m "docs: add envoy gateway migration design"
```

### Task 2: 为 Envoy Gateway 建立正式资源目录

**Files:**
- Review: `envoy/quickstart.yaml`
- Review: `envoy/infra.yaml`
- Review: `envoy/sample/gateway.yaml`
- Create: `envoy/base/gatewayclass.yaml`
- Create: `envoy/base/gateway.yaml`
- Create: `envoy/base/httproute-books.yaml`
- Create: `envoy/base/httproute-orders.yaml`
- Create: `envoy/README.md`

**Step 1: 写一个最小目录设计说明**

在 `envoy/README.md` 中说明：

- `base/` 放稳定资源
- `samples/` 放学习示例
- 后续策略资源如何命名

**Step 2: 创建 `GatewayClass` 资源**

以仓库已有 `envoy/quickstart.yaml` 为参考，抽出正式版 `envoy/base/gatewayclass.yaml`。

**Step 3: 创建 `Gateway` 资源**

定义主入口监听器，作为后续 HTTPRoute 绑定对象。

**Step 4: 为 `/books` 和 `/orders` 创建独立 `HTTPRoute`**

分别创建：

- `envoy/base/httproute-books.yaml`
- `envoy/base/httproute-orders.yaml`

路径规则应与当前 `edge-server` 保持一致。

**Step 5: 用 YAML 搜索检查资源之间引用关系**

Run: `rg -n "gatewayClassName|parentRefs|backendRefs|catalog-service|order-service" envoy/base`
Expected: 能看到 `GatewayClass`、`Gateway`、`HTTPRoute` 的引用闭环

**Step 6: 提交基础网关资源**

```bash
git add envoy/base envoy/README.md
git commit -m "feat: add base envoy gateway resources"
```

### Task 3: 让 Kubernetes 入口先具备基础路由能力

**Files:**
- Modify: `polar-deployment/kubernetes/applications/development/README.md`
- Modify: `polar-deployment/kubernetes/applications/development/Tiltfile`
- Review: `edge-server/k8s/ingress.yaml`
- Review: `edge-server/k8s/deployment.yaml`
- Create: `polar-deployment/kubernetes/applications/development/envoy-gateway.md`

**Step 1: 明确开发环境入口切换策略**

文档中写清楚：

- 当前默认入口是谁
- Envoy Gateway 如何启用
- 什么时候仍允许使用 `edge-server`

**Step 2: 在开发环境说明中加入 Envoy 入口部署步骤**

新增一段文档，描述如何在开发集群中应用 `envoy/base/*.yaml`。

**Step 3: 标记旧 Ingress 为过渡状态**

在文档而不是代码中先标注 `edge-server/k8s/ingress.yaml` 为迁移期入口，不再作为长期目标。

**Step 4: 核对文档中包含本地和 K8s 两条路径**

Run: `rg -n "direct mode|envoy mode|edge-server|Envoy Gateway" polar-deployment/kubernetes/applications/development`
Expected: 至少命中新增说明

**Step 5: 提交开发环境入口文档**

```bash
git add polar-deployment/kubernetes/applications/development/README.md \
  polar-deployment/kubernetes/applications/development/Tiltfile \
  polar-deployment/kubernetes/applications/development/envoy-gateway.md
git commit -m "docs: add development envoy gateway entry docs"
```

### Task 4: 迁移基础路由前，补一组入口行为验证用例

**Files:**
- Create: `docs/plans/2026-03-11-envoy-gateway-verification-checklist.md`
- Review: `edge-server/src/main/resources/application.yaml`
- Review: `catalog-service/src/main/java/com/polarbookshop/catalogservice/web/BookController.java`
- Review: `order-service/src/main/java/com/polarbookshop/orderservice/web/OrderController.java`

**Step 1: 写验证清单**

覆盖以下入口行为：

- `/books/**` 正常路由
- `/orders/**` 正常路由
- 下游超时时的返回码
- Retry 生效与否
- 限流命中表现
- Trace 是否贯通

**Step 2: 为每项行为写清观察方式**

例如：

- `curl` 返回码
- 应用日志
- tracing UI
- metrics 指标

**Step 3: 用文本搜索检查清单完整性**

Run: `rg -n "books|orders|超时|重试|限流|Trace" docs/plans/2026-03-11-envoy-gateway-verification-checklist.md`
Expected: 所有关键项均被命中

**Step 4: 提交验证清单**

```bash
git add docs/plans/2026-03-11-envoy-gateway-verification-checklist.md
git commit -m "docs: add envoy gateway verification checklist"
```

### Task 5: 将 Retry 与超时语义从 Spring 配置映射到 Envoy 策略

**Files:**
- Review: `edge-server/src/main/resources/application.yaml`
- Create: `envoy/policies/backend-traffic-policy-books.yaml`
- Create: `envoy/policies/backend-traffic-policy-orders.yaml`
- Create: `docs/plans/2026-03-11-envoy-gateway-policy-mapping.md`

**Step 1: 提取当前 Spring Gateway 中与超时、重试相关的参数**

至少整理：

- `connect-timeout`
- `response-timeout`
- Retry 次数
- Retry 条件
- Backoff 规则

**Step 2: 在策略映射文档中写清“不可直接照抄”的差异**

说明 Spring 与 Envoy 语义不同，参数需要重校准。

**Step 3: 为 `books` 与 `orders` 分别创建后端流量策略草案**

避免一开始就把两个服务绑成统一模板。

**Step 4: 搜索验证策略文件是否引用了正确目标**

Run: `rg -n "catalog-service|order-service|timeout|retry" envoy/policies`
Expected: 命中两个后端策略文件

**Step 5: 提交超时与重试策略草案**

```bash
git add envoy/policies docs/plans/2026-03-11-envoy-gateway-policy-mapping.md
git commit -m "feat: add envoy timeout and retry policy drafts"
```

### Task 6: 设计限流迁移方案，而不是直接搬运 Java 实现

**Files:**
- Review: `edge-server/src/main/java/com/polarbookshop/edgeserver/config/RateLimiterConfiguration.java`
- Review: `edge-server/src/main/java/com/polarbookshop/edgeserver/ratelimit/ResilientGatewayRateLimiter.java`
- Review: `edge-server/src/main/java/com/polarbookshop/edgeserver/ratelimit/RedisScriptRateLimitRunner.java`
- Create: `docs/plans/2026-03-11-envoy-rate-limit-design.md`
- Create: `envoy/policies/rate-limit-policy-books.yaml`
- Create: `envoy/policies/rate-limit-policy-orders.yaml`

**Step 1: 梳理当前限流语义**

至少说明：

- key 维度
- 每条路由的桶参数
- Redis 失败后的本地 fallback
- 头部返回语义

**Step 2: 明确目标策略**

明确迁移后要选择：

- `fail-open` 还是 `fail-closed`
- 是否保留限流响应头
- 是否按路由区分策略

**Step 3: 写 Envoy 限流方案设计文档**

重点写“保住能力目标，不复刻 Java 结构”。

**Step 4: 写限流策略资源草案**

先按 `books` 与 `orders` 拆开。

**Step 5: 搜索验证两个服务都有独立限流定义**

Run: `rg -n "books|orders|rate" envoy/policies docs/plans/2026-03-11-envoy-rate-limit-design.md`
Expected: 两类路由和限流设计均可见

**Step 6: 提交限流迁移设计**

```bash
git add docs/plans/2026-03-11-envoy-rate-limit-design.md envoy/policies
git commit -m "docs: design envoy rate limiting migration"
```

### Task 7: 删除网关内业务 fallback 的依赖设计

**Files:**
- Review: `edge-server/src/main/resources/application.yaml`
- Review: `edge-server/src/main/java/com/polarbookshop/edgeserver/web/FallbackController.java`
- Create: `docs/plans/2026-03-11-failure-semantics.md`

**Step 1: 记录当前 fallback 的入口点**

明确哪些路由依赖 `fallbackUri` 或其他应用内降级处理。

**Step 2: 定义迁移后的失败语义**

文档中明确：

- 网关返回标准错误
- 用户友好降级不由网关承担
- 如未来有需要，交给 BFF 或前端

**Step 3: 通过文本检查确认文档明确提到 BFF**

Run: `rg -n "标准错误|BFF|前端|fallback" docs/plans/2026-03-11-failure-semantics.md`
Expected: 至少命中 3 处相关说明

**Step 4: 提交失败语义设计**

```bash
git add docs/plans/2026-03-11-failure-semantics.md
git commit -m "docs: define gateway failure semantics"
```

### Task 8: 规划本地开发双模式

**Files:**
- Create: `docs/plans/2026-03-11-local-dev-modes.md`
- Modify: `README.md`
- Modify: `docker-compose.yml`
- Create: `envoy/local/README.md`

**Step 1: 在文档中定义两种本地模式**

- `direct mode`
- `envoy mode`

**Step 2: 明确每种模式的适用场景**

避免团队长期依赖旧 `edge-server`。

**Step 3: 在根 README 中加入入口模式说明**

让新同学一进仓库就能看到本地开发路径。

**Step 4: 如需要，为 `envoy mode` 预留本地依赖说明**

先写清运行方式，即使暂时还没完全自动化。

**Step 5: 搜索验证仓库入口文档已经提到双模式**

Run: `rg -n "direct mode|envoy mode" README.md docs/plans/2026-03-11-local-dev-modes.md envoy/local/README.md`
Expected: 至少命中 3 处

**Step 6: 提交本地开发模式文档**

```bash
git add README.md docker-compose.yml docs/plans/2026-03-11-local-dev-modes.md envoy/local/README.md
git commit -m "docs: define local direct and envoy modes"
```

### Task 9: 为删除 edge-server 制定退场清单

**Files:**
- Create: `docs/plans/2026-03-11-edge-server-decommission-checklist.md`
- Review: `edge-server/build.gradle`
- Review: `edge-server/k8s/deployment.yaml`
- Review: `config-repo/edge-server-k8s.yaml`

**Step 1: 列出删除 `edge-server` 前必须满足的退出条件**

至少包括：

- 路由已迁完
- 流量治理已迁完
- 本地 `envoy mode` 可用
- 文档已切换
- 观测已对齐

**Step 2: 把删除动作拆成静态清单**

例如：

- 删除模块
- 删除配置
- 删除部署资源
- 删除文档引用

**Step 3: 文本检查清单中是否同时覆盖代码、配置、部署、文档**

Run: `rg -n "模块|配置|部署|文档|观测" docs/plans/2026-03-11-edge-server-decommission-checklist.md`
Expected: 全部命中

**Step 4: 提交退场清单**

```bash
git add docs/plans/2026-03-11-edge-server-decommission-checklist.md
git commit -m "docs: add edge server decommission checklist"
```

### Task 10: 执行前复盘与验收口径

**Files:**
- Modify: `docs/plans/2026-03-11-envoy-gateway-migration-plan.md`
- Review: `docs/plans/2026-03-11-envoy-gateway-migration-design.md`
- Review: `docs/plans/2026-03-11-envoy-gateway-verification-checklist.md`

**Step 1: 在计划文档末尾补充“阶段完成定义”**

每个阶段至少包含：

- 完成标准
- 观测检查
- 回滚点

**Step 2: 明确先执行哪些任务**

第一批建议只执行：

- Task 2
- Task 3
- Task 4

**Step 3: 运行文本检查，确认计划文档含有“完成标准”和“回滚”**

Run: `rg -n "完成标准|回滚|阶段" docs/plans/2026-03-11-envoy-gateway-migration-plan.md`
Expected: 至少命中 3 处

**Step 4: 提交实施计划**

```bash
git add docs/plans/2026-03-11-envoy-gateway-migration-plan.md
git commit -m "docs: add envoy gateway migration plan"
```
