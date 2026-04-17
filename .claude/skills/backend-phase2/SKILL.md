---
name: backend-phase2
description: Implement the Java Phase 2 gateway and platform layer: workflow version lifecycle, basic RBAC, audit logging, knowledge access layer, persistence, and stable browser-facing contracts.
---

# Backend Phase 2

先阅读:
- `../phase2-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下与 Phase 2 相关的 Spring Boot 实现
- Workflow 版本发布 / 回滚与 `current_version` 管理
- 运行中 execution 的版本绑定保护
- 知识库、知识版本、知识文档相关接入层与持久化
- 基础 RBAC 与权限校验入口
- 操作审计写入点与 `audit_log`
- Java -> Python 契约的 Phase 2 增量封装
- Java -> Frontend WebSocket 稳定推送能力

## 实现约束

- Java 仍然是浏览器唯一接入层。
- 发布 / 回滚只能影响后续 execution，不能改写运行中 execution 的 `workflow_version`。
- 权限优先做基础 RBAC；复杂 ABAC / SpEL 不得阻塞 Phase 2 主线。
- 审计日志要围绕发布、回滚、权限拒绝、知识库操作等关键动作落点。
- 允许引入 Redis、Telemetry、知识库表，但不能破坏 Phase 1 的 Netty + WebSocket / HTTP 管理接口合同。

## 重点检查

- Workflow 版本生命周期是否与架构文档一致
- 知识库 / 审计 / 权限表结构是否对齐
- Java 到 Python 的事件、Trace 字段和状态值是否一致
- WebSocket 是否能支撑前端自动重连后的继续消费
- 对 Redis / pgvector / Telemetry 的依赖是否隔离清晰
