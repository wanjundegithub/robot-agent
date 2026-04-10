---
name: phase1-scope
description: Shared scope guardrail for the service-robot Phase 1 single-flow closed loop. Use whenever work touches architecture, protocols, schema, workflow nodes, or acceptance.
---

# Phase 1 Scope

先阅读 `reference.md`，再开始任何跨服务实现、联调或评审。

## 这个技能负责什么

- 把工作约束在 Phase 1: 跑通单流程闭环。
- 固定 Phase 1 的技术栈、通信协议、数据模型、事件命名和节点范围。
- 防止把 Phase 2/3/4 内容提前塞进当前实现。

## 必须遵守的原则

- 目标是验证核心架构可行性，不是一次性做完整个平台。
- 一切以 `服务机器人架构设计.md` 为源头文档。
- 任何跨模块设计都要同时满足 Java、Python、Frontend 三方契约。
- 能用简单稳定方案满足 Phase 1，就不要引入生产级扩展特性。

## 常见误区

- 把前端实时链路做成浏览器直连 Python SSE。
- 直接实现 `knowledge`、`subflow`、RBAC、审计日志、监控平台。
- 为了“完整性”先做 Redis 幂等、超时重试、资源隔离。
- 只返回最终答案，不记录节点级执行日志。

## 交付判断

以下 5 条同时成立，才算完成当前阶段:

- 用户发送消息，系统能识别意图。
- 系统能进入指定工作流并执行节点。
- 遇到 `form` 节点能挂起并恢复。
- 前端能看到实时执行过程。
- `execution` 和 `execution_node_log` 记录完整。
