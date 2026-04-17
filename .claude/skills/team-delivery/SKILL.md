---
name: team-delivery
description: Seven-role delivery playbook for tech-leader, backend-java, frontend, python-dev, platform-reliability, qa, and security to implement the service-robot Phase 5 production-optimization baseline.
---

# Team Delivery

先阅读 `handoff.md`。

## 何时使用

- 需要把需求拆给多个角色并行推进时
- 需要先冻结性能、稳定性、归档清理和权限确认合同再联调时
- 需要做 Phase 5 生产优化、稳定性增强与回归验收时

## 团队运行规则

- 先由 `tech-leader` 做任务拆解、接口冻结、风险识别，重点冻结 Netty + WebSocket action / ack / event、可编辑画布 schema、模型 Provider/Profile 合同、ABAC、二次确认、熔断、限流、降级、索引优化、集群边界和日志分层策略。
- `platform-reliability` 负责把性能、稳定性和可维护性目标落到明确的存储、缓存、分片、清理和故障恢复方案上。
- `backend-java` 与 `python-dev` 先定义权限决策、确认流程、熔断/限流/降级输入输出、模型配置解析、审计字段和回退策略，再并行开发。
- `security` 负责评审 ABAC、二次确认、日志归档与清理链路中的敏感暴露和审计边界。
- `frontend` 基于冻结后的契约开发确认弹层、权限反馈、降级提示、可编辑画布和运行状态展示，不自行发明后端控制逻辑。
- `qa` 以 Phase 5 目标为核心产出验证结果，不做空泛的测试建议，并明确验证无 Mock 主链。
- 每个角色只改自己的拥有范围；跨边界问题通过契约和评审解决。

## 完成定义

- 数据路径具备性能优化控制点。
- 稳定性保护机制可验证。
- 日志归档与清理自动化可验证。
- ABAC 与高风险确认可执行。
- Netty + WebSocket 主业务链路、可编辑画布和模型 Profile 配置可验证。
- 没有回归 Phase 1 / Phase 2 / Phase 3 / Phase 4。
