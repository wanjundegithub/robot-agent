---
name: backend-phase4
description: Implement the Java Phase 4 aggregation layer: experiment configuration, replay/query APIs, cost and KPI aggregation, and dashboard-facing contracts.
---

# Backend Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下与 Phase 4 相关的 Spring Boot 实现
- 动态阈值、推荐、评测、成本、实验、回放相关聚合 API
- A/B 实验配置与结果查询接口
- 执行回放查询接口和必要持久化
- 业务指标与成本仪表盘接口
- 审计、Telemetry 和前端查询合同

## 实现约束

- Java 仍然是浏览器唯一接入层。
- 指标、成本、评测和回放都要有清晰数据来源，不能靠前端拼装。
- A/B 和回放都必须保留 execution / version / experiment 可追踪性。
- 聚合查询和仪表盘接口必须服从 Phase 3 安全基线与脱敏规则。
- 允许新增 DTO、表、事件和查询接口，但不能破坏 Phase 1 / 2 / 3 主合同。

## 重点检查

- 实验字段、成本字段和回放对象是否与 scope 一致
- Java 到 Python 的事件、Trace 字段和聚合字段是否一致
- 仪表盘与回放接口是否能支撑前端展示和运营分析
- 审计是否覆盖实验配置、回放请求、成本告警和评测触发
- 是否避免把 Phase 5 的运维能力提前塞进当前实现
