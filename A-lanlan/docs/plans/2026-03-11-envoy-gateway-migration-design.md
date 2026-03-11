# Envoy Gateway 渐进替换 Spring Gateway 设计文档

**日期：** 2026-03-11

## 1. 背景

当前项目使用 `edge-server` 作为统一入口，承担了以下职责：

- 路由转发：`/books/**`、`/orders/**`
- 流量治理：重试、限流
- 弹性保护：熔断
- 入口暴露：Kubernetes Ingress 指向 `edge-server`
- 会话相关：`SaveSession` 与 Redis Session
- 可观测性：Actuator、指标、Tracing

这套结构适合学习 Spring Cloud Gateway，但如果目标是对齐国际化企业的生产规范，就需要进一步把“平台网关职责”和“应用逻辑职责”拆开。

本次迁移的终点不是“换一个网关框架”，而是把入口层演进为更标准的生产模型：

- `Envoy Gateway` 负责南北向流量入口
- `Gateway API` / `Policy` 负责路由与流量治理配置
- 业务服务只负责业务能力
- 将来如有信息聚合需求，单独创建 `bff-service` 或 `aggregation-service`
- 最终完全删除 `edge-server`

## 2. 设计目标

### 2.1 业务目标

- 在不一次性推翻现有系统的前提下，引入 Envoy Gateway
- 逐步减少 `edge-server` 的职责，直到完全移除
- 保留本地开发能力，不能要求所有开发都先进入完整 K8s 集群

### 2.2 工程目标

- 按生产规范拆分职责，而不是继续把业务降级、会话、聚合逻辑塞进网关
- 所有入口能力尽量转为声明式配置，而不是散落在 Java 代码中
- 每一阶段都可验证、可回滚、可观察

### 2.3 学习目标

- 学习企业级入口层设计，而不仅是“功能能跑”
- 学习渐进式迁移，而不是一次性切换
- 学习失败语义、观测、策略治理这些生产关键点

## 3. 非目标

- 不追求把 Spring Gateway 的实现细节 1:1 迁到 Envoy
- 不在网关层继续保留 `fallbackUri` 之类的业务降级页面
- 不把未来的 BFF / 聚合职责重新塞回 Envoy Gateway
- 不要求本地开发与线上 100% 同构，但要求有一条接近线上行为的 `envoy mode`

## 4. 目标架构

最终入口架构如下：

1. 外部流量进入 Envoy Gateway
2. Envoy Gateway 根据 `Gateway`、`HTTPRoute`、`Policy` 将请求转发到 `catalog-service`、`order-service` 或未来新增的 BFF
3. 网关层仅承担入口流量能力：
   - 路由
   - 超时
   - 重试
   - 限流
   - 熔断 / 异常实例摘除 / 并发保护
   - 认证鉴权策略
4. 业务聚合由独立服务承担，不再由网关承担

这符合国际化企业常见做法：

- 平台网关负责流量控制
- 业务服务负责业务语义
- 用户可见降级由前端或 BFF 负责，而不是由网关拼装业务 fallback

## 5. 当前职责到目标归属的映射

| 当前职责 | 当前实现 | 目标归属 | 处理方式 |
|---|---|---|---|
| `/books/**`、`/orders/**` 路由 | Spring Gateway routes | Envoy Gateway `HTTPRoute` | 直接迁移 |
| Ingress 暴露 | `edge-server/k8s/ingress.yaml` | Envoy Gateway `Gateway` | 直接替换 |
| Retry | Spring `default-filters` | Envoy Gateway / Gateway API 策略 | 迁移并重新校准参数 |
| 连接超时、响应超时、连接治理 | Spring `httpclient` | Envoy traffic policy | 迁移，按 Envoy 语义重设 |
| Request rate limit | Spring + Redis + 本地 fallback | Envoy Gateway rate limiting | 迁移能力，不追求 Java 细节复刻 |
| CircuitBreaker | Spring CircuitBreaker | Envoy circuit breaker / outlier detection | 迁移理念，不迁 `fallbackUri` |
| `fallbackUri: forward:/catalog-fallback` | 应用内转发 | 删除 | 改为标准错误语义 |
| `SaveSession`、Redis Session | Spring Session | 删除入口层依赖 | 会话与身份治理转到认证系统或未来 BFF |
| 网关内演示/控制器 endpoint | `edge-server` controller | 删除或迁到独立服务 | 不保留在入口层 |
| tracing / metrics / health | Spring Actuator + OTEL | Envoy + app 双侧观测 | 重建观测口径 |
| 未来聚合接口 | 暂无 | 新建 `bff-service` | 不回流到网关 |

## 6. 熔断与降级的生产规范

在生产环境中，“熔断”通常不是指网关返回一个自定义 fallback 页面，而是指一整套故障隔离与快速失败策略：

- 网关 / 代理层负责：
  - 超时
  - 有限重试
  - 熔断 / 并发保护
  - 异常实例摘除
- 服务调用层负责：
  - 下游隔离
  - 线程池/连接池保护
  - 本地缓存或默认值等业务可接受的降级
- 前端 / BFF 负责：
  - 用户可感知的降级体验

因此本项目不建议把 `fallbackUri` 迁到 Envoy。正确做法是：

- 入口层返回标准化失败
- 真正需要业务降级时，由未来的 BFF 或前端承担

这样更接近企业真实规范，也更能锻炼正确的边界意识。

## 7. 本地开发策略

由于项目需要保留本地开发环境，迁移后建议保留两种本地模式：

### 7.1 Direct Mode

开发者直接访问本地业务服务：

- `catalog-service:9001`
- `order-service:9002`

适合：

- 业务功能开发
- 单服务调试
- 不依赖入口策略时的日常迭代

### 7.2 Envoy Mode

本地提供一条尽量接近线上入口行为的路径，例如：

- `kind` / `k3d` + Envoy Gateway
- 或本地最小化的 Envoy 启动方案

适合：

- 验证路由、重试、限流、认证
- 复现入口层行为
- 集成测试

原则是：

- `edge-server` 只能作为迁移期缓冲，不是长期本地依赖
- 最终本地开发不应再依赖 Spring Gateway

## 8. 渐进式迁移阶段

### Phase 0：基线盘点

目标：

- 明确 `edge-server` 当前职责
- 形成“职责 -> 最终归属”的迁移地图
- 建立迁移前验证基线

产出：

- 路由与策略清单
- 迁移对照表
- 基线验证清单：成功路由、超时、限流、故障、trace

### Phase 1：引入 Envoy Gateway 基础设施

目标：

- 在 K8s 中落地 Envoy Gateway
- 把 Gateway API 资源纳入仓库
- 保证本地具备 `envoy mode` 的最小跑通路径

此阶段不替换生产主入口。

### Phase 2：迁移标准入口能力

优先迁移最标准、最少业务语义的能力：

- 对外暴露
- 基础路由
- Path/Header 匹配与改写
- 超时
- Retry

此阶段完成后，Envoy Gateway 应成为主入口，而 `edge-server` 只保留少量尚未迁走的历史能力。

### Phase 3：迁移流量治理能力

迁移顺序建议为：

1. 限流
2. 熔断 / 并发保护 / 异常实例摘除
3. 认证鉴权

原则：

- 保持能力目标一致
- 不强求实现细节一致
- 主动删除 `fallbackUri` 和 `SaveSession` 依赖

### Phase 4：收口本地开发路径

目标：

- `direct mode` 成为日常开发默认路径
- `envoy mode` 成为入口层验证标准路径
- 不再要求任何人继续使用 Spring Gateway 作为本地统一入口

### Phase 5：删除 `edge-server`

动作：

- 删除应用代码
- 删除配置中心相关配置
- 删除 K8s Deployment、Service、Ingress
- 更新文档、启动方式、排障手册

完成标志：

- 所有入口流量只经过 Envoy Gateway
- 仓库中不再存在 `edge-server` 作为默认入口实现

## 9. 风险与应对

### 9.1 Retry 语义差异

风险：

- Spring Gateway 与 Envoy 对重试条件、次数、超时叠加方式存在差异

应对：

- 不直接照抄原参数
- 用压测和故障注入重新校准

### 9.2 限流语义差异

风险：

- 当前实现包含 Redis 失败时的本地 fallback，Envoy 侧策略不一定完全等价

应对：

- 先定义目标策略：`fail-open` 还是 `fail-closed`
- 再设计限流规则与观测指标

### 9.3 失败语义变化

风险：

- 删除 `fallbackUri` 后，错误更直接暴露给调用方

应对：

- 明确这是预期的生产化行为
- 如需用户友好降级，未来交由 BFF 或前端处理

### 9.4 本地复杂度上升

风险：

- 如果 `envoy mode` 太重，团队会回到直连模式，入口能力无法被稳定验证

应对：

- 同时提供 `direct mode` 和 `envoy mode`
- 为 `envoy mode` 提供最小化启动脚本和清晰文档

### 9.5 观测断层

风险：

- 迁移后如果只验证“能通”，无法判断语义是否真实对齐

应对：

- 在迁移前后对齐错误码、延迟分布、trace 链路、限流命中率

## 10. 推荐决策

本项目推荐采用如下总策略：

1. 以 Kubernetes + Envoy Gateway 作为最终目标架构
2. 本地同时保留 `direct mode` 与 `envoy mode`
3. 将 `edge-server` 视为过渡组件，而非长期并存组件
4. 不迁移 `fallbackUri` 与 `SaveSession` 这类不适合长期保留在入口层的职责
5. 将来如需聚合接口，新增独立 BFF/聚合服务，而不是复活 Spring Gateway

## 11. 成功标准

满足以下条件时，可认为迁移成功：

- Envoy Gateway 成为唯一入口
- `edge-server` 被完全删除
- 路由、超时、重试、限流、认证等能力被声明式管理
- 本地开发不再依赖 Spring Gateway
- 观测、故障处理、部署方式更接近企业生产规范

