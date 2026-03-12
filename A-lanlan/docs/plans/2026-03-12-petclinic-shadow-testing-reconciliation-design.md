# Petclinic 对账平台教学型设计文档

**日期：** 2026-03-12

## 1. 背景

当前仓库已经具备一部分云原生基础设施能力：

- Spring Boot 微服务
- Config Server
- Gateway / Envoy Gateway 迁移探索
- OpenTelemetry Operator 与 Collector 基础配置
- Kubernetes 部署清单

但当前业务域较轻，缺少一条足够完整的事务型主链路，不适合直接承载“生产级对账平台”的学习目标。

本设计文档的目标不是立刻实现一个完整平台，而是先定义一条清晰、可渐进演进、符合国际化企业生产思路的学习路线。

本次决定采用 `spring-petclinic-microservices` 作为训练底座，用它来学习：

- Shadow Testing / Traffic Mirroring
- Reconciliation System / Diffy-style Comparison
- 接口层、状态层、事件层的组合型对账
- 从零侵入观测到复杂事务规则的渐进演进

## 2. 设计目标

### 2.1 学习目标

- 用一个真实的微服务底座理解国际化企业的生产标准，而不是只完成一个玩具 demo
- 学会把“影子流量、可观测性、消息流、对账引擎、规则中心”拆成独立职责
- 学会为什么复杂业务不能只比 HTTP 响应，而必须看接口结果、数据库状态和事件输出
- 学会如何从“零侵入采集”逐步升级到“少量标准化适配”

### 2.2 工程目标

- 设计一套可插拔的云原生对账平台架构
- 默认采用低侵入甚至零侵入的接入方式
- 支持后续扩展到复杂事务、异步链路、补偿流程和最终一致性场景
- 保证采集链路与业务主链路解耦，不影响用户请求

### 2.3 结果目标

- 第一阶段跑通最小闭环：
  `Envoy Mirror -> OTel -> Collector -> Kafka -> Recon Service`
- 后续逐步升级到：
  `接口层 + 状态层 + 事件层` 的组合型对账
- 最终形成一套“可教学、可验证、可扩展”的平台蓝图

## 3. 非目标

- 不在第一阶段就实现完整 Saga 编排或完整分布式事务框架
- 不在第一阶段就支持完整响应体逐字节对比
- 不要求一开始就覆盖所有业务服务与所有数据库表
- 不把 OTel Collector 当成业务对账引擎
- 不把对账平台设计成和某个具体业务强耦合的脚本集合

## 4. 为什么选 Petclinic 作为底座

## 4.1 选择原则

学习平台型能力时，底座项目优先看：

- 微服务骨架是否成熟
- 是否与当前技术栈接近
- 是否便于逐步接入网关、Tracing、Collector、MQ、K8s
- 复杂度是否可控

## 4.2 Petclinic 的适配点

`spring-petclinic-microservices` 适合作为第一阶段底座，原因是：

- 它本身就是微服务系统，不是单体拆分题
- 它和当前仓库一样，处于 Spring Cloud / Spring Boot 主线上
- 它的复杂度比真实商城低一档，更适合先学平台能力
- 它保留了真实的服务边界、网关、配置中心、服务发现和可观测性训练价值

## 4.3 Petclinic 的不足与应对

Petclinic 不是电商域，也不天然具备支付、库存、履约等复杂事务场景。

因此本设计不把 Petclinic 当作最终业务模型，而把它当作：

`云原生微服务训练底座`

后续通过“重新解释业务链路”和“增量新增服务”的方式，把它演进成适合学习事务级对账的平台。

## 5. 问题本质与心智模型

## 5.1 问题本质

本项目要解决的不是“如何比较两个 HTTP 响应”，而是：

`如何把同一笔业务在 V1 和 V2 上的多源证据收集起来，并判断最终业务结果是否一致。`

## 5.2 心智模型

可以把这套系统理解成“稽核系统”：

- Envoy Gateway：负责复制同一份请求
- V1：正式版本，返回真实用户结果
- V2：影子版本，只执行，不影响用户
- OTel / CDC / MQ 消费：负责记录不同层面的事实
- Reconciliation Engine：负责聚合同一笔业务的证据
- Rule Registry：负责定义“什么叫一致”

所以它本质上是一个：

`evidence aggregation + rule-based judgment`

平台，而不是日志平台。

## 6. 设计原则

本平台遵循以下设计原则：

1. 采集与判定解耦
2. 主链路与旁路解耦
3. 默认零侵入，复杂场景少量标准适配
4. 先比较 canonical result，再比较原始格式
5. 优先以业务最终结果为准，而不是只看接口返回
6. 允许最终一致性，不能用同步思维误判异步系统
7. 对账规则声明式配置，避免散落在代码中的 if/else

## 7. 目标架构

目标平台拆分为六层。

### 7.1 Traffic Shadow Layer

入口层使用 `Envoy Gateway` 做流量镜像：

- 用户请求进入 Envoy Gateway
- 正常请求转发到 `V1`
- 影子请求复制到 `V2`
- 保留 `traceparent` 等关联请求头

目标：

- 用户只感知 V1
- V2 执行不影响线上结果
- V1 与 V2 拥有天然可关联的入口证据

### 7.2 Observation Layer

观测层负责自动收集事实，主要包含：

- OTel Java Agent：自动采集 HTTP server/client span
- DB CDC：采集数据库最终状态变化
- MQ Consumer / Broker Sink：采集事件输出

这一层只回答“发生了什么”，不回答“对不对”。

### 7.3 Normalization Layer

不同来源的证据格式差异很大：

- HTTP span
- CDC 记录
- Kafka / RocketMQ 事件

因此需要先归一化成统一模型，例如：

`ReconciliationEnvelope`

建议至少包含：

- `businessType`
- `sourceType`
- `sourceVersion`
- `traceId`
- `businessKey`
- `stage`
- `canonicalPayload`
- `observedAt`

### 7.4 Reconciliation Engine

对账引擎负责：

- 按关联键聚合同一笔业务
- 维护对账会话状态
- 等待证据收齐
- 依据规则比较
- 输出结果与差异

典型结果：

- `MATCHED`
- `MISMATCHED`
- `TIMED_OUT`
- `INCONCLUSIVE`

### 7.5 Rule Registry

规则中心负责定义：

- 某类业务的入口接口
- business key 如何提取
- 该业务需要哪些证据才算完整
- 哪些字段严格一致
- 哪些字段允许忽略或容差

### 7.6 Audit & Alert Layer

结果层负责：

- 审计查询
- 差异样本保存
- 指标聚合
- 告警触发

至少应包括：

- Metrics
- Audit Store
- Alerting

## 8. 为什么不能只用 Trace ID

`Trace ID` 很适合入口层的请求配对，但不能作为复杂事务的唯一真相键。

原因：

- 异步消费可能脱离原始 HTTP trace
- 重试和补偿可能产生新的 trace
- 一笔业务可能跨多个服务和多个本地事务

因此平台建议使用三层关联键：

1. `trace_id`
2. `business_key`
3. `reconciliation_session_id`

三者职责如下：

- `trace_id`：入口链路关联
- `business_key`：业务真主键
- `reconciliation_session_id`：平台内部的对账会话容器

## 9. 为什么要比较 Canonical Result

生产系统中，原始数据形态往往不稳定：

- HTTP body 字段顺序不同
- 非关键字段新增或缺失
- 时间戳格式不同
- DB 字段名与接口字段名不同
- 事件 payload 含有调试字段或系统字段

因此不能直接比较原始 payload，而应比较：

`canonical result`

也就是经过业务归一化后、只保留关键业务事实的视图。

例如“创建预约”这类业务，真正应该比较的可能是：

- `visitId`
- `ownerId`
- `petId`
- `scheduledDate`
- `status`
- `fee`
- `notificationEmitted`

而不是整段原始 JSON。

## 10. 接入模式：为什么采用分层模式

平台接入分三种可能路线：

### 10.1 纯零侵入

只通过网关、OTel、CDC、MQ 等基础设施采集数据。

优点：

- 接入快
- 适合大规模铺开

缺点：

- 复杂事务判定不够准

### 10.2 纯业务显式接入

每个服务通过 SDK 或注解显式上报对账信息。

优点：

- 精度高

缺点：

- 侵入大
- 不利于平台化推广

### 10.3 分层混合模式

默认走零侵入：

- Envoy Mirror
- OTel Agent
- Collector
- CDC
- MQ

复杂业务再引入少量标准适配：

- business key extractor
- canonical snapshot provider
- transaction stage marker

本设计选择第三种。

原因：

- 既保留了平台化的低接入成本
- 又给复杂事务留下了精度提升通道

## 11. Petclinic 上的业务映射策略

第一阶段不急于重构整个领域，而是先重新解释现有服务角色。

建议映射如下：

- `customers-service`：客户主数据域
- `vets-service`：资源供给域
- `visits-service`：预约 / 订单域
- `api-gateway`：统一入口
- `config-server` / `discovery-server`：平台基础设施

在这个语义下：

`Create Visit`

可以作为第一条训练型交易链，等价于：

`Create Order`

它具备很好的学习价值：

- 有入口请求
- 有业务服务处理
- 有数据库写入
- 可以逐步扩展事件和下游副作用

## 12. 第一条业务链的设计

## 12.1 第一阶段：接口层闭环

目标：

- 对同一个 `create visit` 请求做 V1/V2 比对
- 先只比较接口层

最小闭环如下：

```text
Client
  -> Envoy Gateway
       -> visits-service-v1
       -> visits-service-v2 (shadow)

visits-service-v1 / v2
  -> OTel Java Agent
  -> OTel Collector
  -> Kafka topic
  -> Recon Service
  -> Redis pairing state
  -> PostgreSQL audit result
```

第一阶段建议比较：

- HTTP method
- route/path
- status code
- latency
- 关键返回摘要

第一阶段不急于比较：

- 完整 body
- 数据库最终状态
- 事件输出

## 12.2 第二阶段：状态层闭环

目标：

- 对比 `visits` 相关表的最终状态
- 理解“接口一致 != 状态一致”

状态层可先用训练版实现，再演进到 CDC。

训练版可接受：

- Recon Service 在窗口结束后主动查询目标表

生产版目标：

- 使用 CDC 自动采集状态变更

## 12.3 第三阶段：事件层闭环

目标：

- 让 `create visit` 产生一个业务事件
- 对比 V1 / V2 是否都发出正确事件

建议事件样例：

- `visit-created`

## 12.4 第四阶段：跨服务事务

目标：

- 新增 `billing-service` 或等价服务
- 让 `create visit` 引发第二个服务的状态变化

这时一笔业务需要同时比较：

- 接口层结果
- visits 状态
- billing 状态
- 事件输出

这才开始接近“事务级对账平台”。

## 13. 对账会话模型

对账引擎不应只把两条消息拿出来直接比较，而应维护一个：

`Business Session`

一个会话中会聚合三类证据：

- Interface Evidence
- State Evidence
- Event Evidence

建议最小状态机：

- `OPEN`
- `COLLECTING`
- `READY_TO_COMPARE`
- `MATCHED`
- `MISMATCHED`
- `TIMED_OUT`
- `INCONCLUSIVE`

这套状态机的意义是：

- 允许最终一致性
- 允许部分证据延迟到达
- 允许补偿流程尚未结束时先不下结论

## 14. Rule Registry 的分层设计

规则中心建议分三层：

### 14.1 L0：默认规则

平台内置开箱即用规则：

- 读取 trace id
- 读取 HTTP path / method / status
- 读取 Kafka message key
- 读取 CDC 表主键
- 默认忽略时间戳、字段顺序等噪音

### 14.2 L1：配置型规则

通过 YAML、数据库配置或未来的 CRD 声明：

- 某个接口属于哪类业务
- business key 从哪里提取
- 关注哪些表和 topic
- 关注哪些字段

### 14.3 L2：适配型规则

给复杂事务使用的扩展点：

- canonical snapshot provider
- stage marker
- reconciliation marker event

这三层设计保证：

- 80% 场景依赖 L0 + L1
- 少数复杂场景再进入 L2

## 15. 生产化思维与常见误区

## 15.1 正确的生产化思维

- Collector 是采集管道，不是业务脑子
- Redis 更适合存临时配对状态，不适合做最终审计库
- 审计结果必须持久化到关系型数据库或可查询存储
- 复杂系统对账必须接受“窗口”和“最终态”的概念
- 对账平台要比业务系统更强调可观察、可追踪、可回放

## 15.2 常见误区

- 误以为对账就是比日志
- 误以为 Trace ID 足够覆盖事务全生命周期
- 误以为零侵入就能精确处理所有复杂业务
- 误以为全字段逐字节比较才叫严格
- 误以为第一阶段就要支持 CDC、Saga、补偿和完整事件拓扑

## 16. 分阶段学习路线

建议按以下顺序推进：

### Phase 1：理解 Petclinic 骨架

目标：

- 看懂网关、服务边界、配置和部署方式

### Phase 2：最小接口层对账

目标：

- 跑通 `Envoy Mirror -> OTel -> Kafka -> Recon`

### Phase 3：状态层对账

目标：

- 引入数据库最终状态比对

### Phase 4：事件层对账

目标：

- 引入业务事件输出与比较

### Phase 5：跨服务事务对账

目标：

- 引入第二个参与者服务
- 形成真正的事务链路

### Phase 6：规则中心与平台化

目标：

- 从单业务 hard-code 演进到可配置规则

## 17. 第一阶段成功标准

当以下条件同时成立时，可以认为第一阶段完成：

- Envoy 能将 `create visit` 流量镜像到 V2
- V1 返回用户结果，V2 不影响用户
- OTel 能自动采集 V1 / V2 HTTP span
- Collector 能将目标 span 转发到 Kafka
- Recon Service 能按 `trace_id` 配对 V1 / V2
- 对账结果能输出为 `MATCHED / MISMATCHED / TIMED_OUT`
- 结果可被查询和回放

## 18. 后续实现文档建议

本设计文档确认后，后续实现建议再拆出以下文档：

1. `Petclinic 对账平台实现计划`
2. `第一阶段最小闭环任务拆分`
3. `Kafka / Collector / Recon Service 数据契约`
4. `Rule Registry 初版配置模型`
5. `状态层与事件层升级设计`

## 19. 一句话复盘

本设计把 Petclinic 定义为一个“云原生对账平台训练底座”，先从接口层最小闭环起步，再逐步升级到接口、状态、事件三层合一的事务级对账平台。
