---
name: team-delivery
description: Six-role delivery playbook for tech-leader, backend-java, frontend, python-dev, security, and qa to implement the service-robot Phase 3 multi-workflow scheduling and safety baseline.
---

# Team Delivery

先阅读 `handoff.md`。

## 何时使用

- 需要把需求拆给多个角色并行推进时
- 需要先冻结路由、状态机、调度、安全合同再联调时
- 需要做 Phase 3 多流程调度、安全能力增强与回归验收时

## 团队运行规则

- 先由 `tech-leader` 做任务拆解、接口冻结、风险识别，重点冻结混合路由决策、状态机、切换/恢复语义、并发控制和安全规则。
- `backend-java` 与 `python-dev` 先定义路由输入输出、execution 生命周期、SSE / WebSocket 事件、调度依赖和追踪字段，再并行开发。
- `security` 负责对 Prompt 防护、输出校验、脱敏策略做统一约束，并评审 Java / Python / Frontend 的接入点。
- `frontend` 基于冻结后的契约开发，不自己发明路由策略、调度策略或安全 DSL。
- `qa` 以 Phase 3 验收项和复杂切换场景为核心产出测试，不做空泛的测试建议。
- 每个角色只改自己的拥有范围；跨边界问题通过契约和评审解决。

## 完成定义

- 流程可在执行中显式切换。
- 切换后原流程可恢复。
- 多个流程可并发执行且状态可观测。
- Prompt 注入、输出结构化校验、敏感脱敏可验证。
- 没有引入 Phase 4 / Phase 5 的大块功能，也没有回归 Phase 1 / Phase 2。
