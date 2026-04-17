---
name: qa-phase5
description: Test the service-robot Phase 5 production optimization across Netty/WebSocket contracts, no-mock validation, editable-canvas flows, resilience, ABAC, high-risk confirmation, archive/cleanup automation, and regression safety.
---

# QA Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 验收拆解

- 数据路径具备性能优化控制点
- 稳定性保护机制可验证
- 日志归档与清理自动化可验证
- 权限细化 (ABAC) 可执行
- 高风险操作二次确认可执行
- Netty + WebSocket 主业务合同可执行
- 主执行链路无 Mock / Demo / Stub 返回

此外还要覆盖:

- Redis 集群边界
- 向量库分片边界
- 对 Phase 1 / Phase 2 / Phase 3 / Phase 4 的回归

## 测试策略

- Java: ABAC、确认接口、限流 / 熔断 / 降级入口、归档查询和审计
- Python: 限流、降级、确认、向量访问优化、模型 Profile 消费、保护事件
- Frontend: 确认弹层、权限反馈、降级和限流提示、可编辑画布
- WebSocket: action / ack / event / error、断线恢复、顺序投递和订阅边界
- Platform: 索引、缓存、分片、归档清理脚本和故障恢复验证
- 安全: ABAC / 确认 / 归档清理链路中的脱敏与最小暴露验证

## 重点检查

- 保护机制是否真的触发且可解释
- ABAC 和高风险确认是否阻断不当操作
- 主链路是否真的消费真实配置而不是 mock 合同或假数据
- 归档和清理任务是否按策略执行
- 降级、熔断、限流是否不破坏主链路可用性
- 是否存在明显的历史回归
