---
name: qa-phase4
description: Test the service-robot Phase 4 intelligence and analytics enhancement across evaluation, cost tracking, experiments, replay, dashboards, and regression risk.
---

# QA Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 验收拆解

- 自动化测试可运行
- RAG 效果可量化评估
- 成本可追踪预警
- 业务指标可视化
- 支持 A/B 测试
- 执行回放具备最小可用闭环

此外还要覆盖:

- 动态阈值调整
- 智能子流程推荐
- 对 Phase 1 / Phase 2 / Phase 3 的回归

## 测试策略

- Java: 实验配置、回放查询、仪表盘接口、成本与指标聚合、审计
- Python: 阈值调整、推荐、评测执行、成本事件、实验字段、回放事件、Telemetry
- Frontend: 评测界面、回放界面、A/B 对比、成本仪表盘、业务指标仪表盘
- 安全: replay / eval / cost / metrics 中的脱敏和最小暴露验证
- 闭环: 真实工作流在基线版本与实验版本上的对比验证

## 重点检查

- 测试框架是否能稳定运行且可扩展
- RAG 评测结果是否可重复、可比较、可解释
- 成本和预算预警是否按预期触发
- A/B 实验分组和归因是否清晰
- 执行回放是否能重现关键节点和最终结果
