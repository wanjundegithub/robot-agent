---
name: qa-phase2
description: Test the service-robot Phase 2 platform enhancement across Java, Python, frontend, Redis, vector retrieval, and observability with acceptance-focused checks.
---

# QA Phase 2

先阅读:
- `../phase2-scope/reference.md`

## 验收拆解

- 工作流可发布新版本，并有回滚路径
- 旧会话 / 运行中 execution 不受新版本影响
- 表单提交幂等；消息与工具幂等策略可验证
- 工具调用支持重试
- 知识库检索可用
- 链路追踪完整

此外还要覆盖:

- 前端 WebSocket 自动重连
- 基础 RBAC
- 操作审计
- 3 个真实流程演示

## 测试策略

- Java: 版本管理、权限校验、审计日志、知识库接入层、WebSocket 契约
- Python: 幂等、重试、资源隔离、`knowledge` / `subflow` / `tool`、SSE 事件、Telemetry
- Frontend: 编排器校验、版本操作、重连、节点配置、权限反馈
- 数据层: Redis Key、MySQL 新表、向量检索结构
- 闭环: `flight_booking`、`hotel_booking`、`general_query`

## 重点检查

- 同一 `message_id` / `submit_id` / 工具输入重复提交时是否保持幂等
- 发布新版本后，运行中的 execution 是否仍绑定旧版本
- 工具重试是否只发生在可重试错误上
- `knowledge` 节点结果是否可追踪到知识版本
- Trace / Metric 是否能关联 execution、workflow、node
- 权限拒绝和关键操作是否写入审计日志
