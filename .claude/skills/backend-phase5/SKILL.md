---
name: backend-phase5
description: Implement the Java Phase 5 production layer: Netty/WebSocket gateway contracts, editable-canvas and model-config APIs, ABAC, high-risk confirmation, resilience entrypoints, audit hardening, and production-facing contracts.
---

# Backend Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下与 Phase 5 相关的 Spring Boot 实现
- Netty + WebSocket action / ack / event 合同
- 可编辑画布保存 / 发布预检 / 版本草稿合同
- 模型 Provider / Model Profile / 知识配置接入接口
- ABAC 权限决策入口
- 高风险操作二次确认接口与事件
- 熔断 / 限流 / 优雅降级入口
- 归档查询和清理可见性接口
- 审计与生产配置强化

## 实现约束

- Java 仍然是浏览器唯一接入层。
- Java 负责前端主业务链路的 Netty + WebSocket 契约，不退回 HTTP 主交互。
- ABAC 必须与现有 RBAC、执行模型和审计链路兼容。
- 高风险确认必须具备时效、确认 ID、取消行为和审计写点。
- 熔断、限流、降级都要有清晰策略名、状态和前端反馈。
- 画布和模型配置接口不能依赖 mock 合同或假数据预检。
- 允许新增 DTO、策略、事件和配置，但不能破坏 Phase 1 / 2 / 3 / 4 主合同。

## 重点检查

- ABAC 条件输入输出是否清晰
- 确认流程是否和工具/执行模型对齐
- Netty + WebSocket 合同是否覆盖 send_message / submit_form / canvas_edit / ack / error 等核心语义
- 画布保存和模型配置接口是否与架构文档一致
- 熔断 / 限流 / 降级事件、审计和查询字段是否一致
- 归档与清理接口是否兼容回放和审计
- 是否避免把平台优化变成脱离业务合同的重写
