---
name: backend-phase5
description: Implement the Java Phase 5 production layer: ABAC, high-risk confirmation, resilience entrypoints, audit hardening, and production-facing contracts.
---

# Backend Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下与 Phase 5 相关的 Spring Boot 实现
- ABAC 权限决策入口
- 高风险操作二次确认接口与事件
- 熔断 / 限流 / 优雅降级入口
- 归档查询和清理可见性接口
- 审计与生产配置强化

## 实现约束

- Java 仍然是浏览器唯一接入层。
- ABAC 必须与现有 RBAC、执行模型和审计链路兼容。
- 高风险确认必须具备时效、确认 ID、取消行为和审计写点。
- 熔断、限流、降级都要有清晰策略名、状态和前端反馈。
- 允许新增 DTO、策略、事件和配置，但不能破坏 Phase 1 / 2 / 3 / 4 主合同。

## 重点检查

- ABAC 条件输入输出是否清晰
- 确认流程是否和工具/执行模型对齐
- 熔断 / 限流 / 降级事件、审计和查询字段是否一致
- 归档与清理接口是否兼容回放和审计
- 是否避免把平台优化变成脱离业务合同的重写
