---
name: platform-reliability-phase5
description: Define and operationalize the Phase 5 platform baseline: Netty/WebSocket capacity, index optimization, Redis cluster boundaries, vector sharding, circuit breakers, fine-grained throttling, and log lifecycle automation.
---

# Platform Reliability Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 你要交付的东西

- 数据库索引优化目标与验证方式
- Netty + WebSocket 连接容量、心跳、背压和故障恢复策略
- Redis 集群边界与缓存一致性策略
- 向量库分片策略
- 熔断、限流、降级和故障恢复口径
- 日志归档与清理自动化方案

## 实现约束

- 所有平台优化都必须映射到明确合同、配置、脚本、指标或测试，不做口号式“优化”。
- 方案必须与现有 execution、audit、replay、metrics、security 链路兼容。
- 不把优化扩成当前仓库无法承接的基础设施重建项目。
- 归档与清理必须服从脱敏和最小暴露原则。
- 性能和稳定性方案必须能被 QA 和 Security 验证。
- 不用 mock 服务替代真实容量、背压或故障恢复方案。

## 重点检查

- 索引、缓存和分片目标是否明确
- Netty + WebSocket 容量、心跳、背压和顺序投递目标是否明确
- 熔断、限流、降级策略是否具备触发条件和回退规则
- 日志分层、保留期、清理任务和恢复入口是否明确
- 平台方案是否和业务合同、评测、回放和审计兼容
- 是否存在明显的不可落地假设或超范围基础设施设计
