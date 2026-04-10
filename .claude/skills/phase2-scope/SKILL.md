---
name: phase2-scope
description: Shared scope guardrail for the service-robot Phase 2 platform enhancement. Use whenever work touches versioning, permissions, audit, idempotency, retry, knowledge, subflow, Redis, vector store, or observability.
---

# Phase 2 Scope

先阅读 `reference.md`，再开始任何跨服务实现、联调或评审。

## 这个技能负责什么

- 把工作约束在 Phase 2: 增强平台能力。
- 固定 Phase 2 的技术栈、通信协议、数据模型、节点范围、幂等 / 重试口径和观测命名。
- 防止把 Phase 3/4 内容提前塞进当前实现。

## 必须遵守的原则

- Phase 1 闭环已经完成，Phase 2 的前提是“增强”而不是“推倒重来”。
- 一切以 `服务机器人架构设计.md` 为源头文档。
- 任何跨模块设计都要同时满足 Java、Python、Frontend、Redis、向量库和可观测性链路。
- 能用简单稳定方案满足 Phase 2，就不要提前做 Phase 3 的复杂调度和安全框架。

## 常见误区

- 发布 / 回滚直接影响运行中 execution，破坏版本隔离。
- 把前端重连做成浏览器直连 Python SSE。
- 为了做权限直接上完整 ABAC / SpEL，引入过重策略引擎。
- 把 `knowledge`、`subflow` 做成开放式多流程平台，提前踏入 Phase 3。
- 只做业务功能，不冻结 Redis Key、重试策略、审计字段、Trace / Metric 命名。

## 交付判断

以下内容同时成立，才算完成当前阶段:

- 工作流可发布新版本，并有明确回滚路径。
- 旧会话 / 运行中 execution 不受新版本影响。
- 表单提交幂等；消息与工具幂等策略可验证。
- 工具调用支持重试，且错误分类清晰。
- `knowledge` 节点可用，并能把检索结果传给后续节点。
- 链路追踪与基础指标完整。

前端断线重连、基础 RBAC、操作审计、3 个真实流程演示属于 Phase 2 完成项，也必须覆盖。
