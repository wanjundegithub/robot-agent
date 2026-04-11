---
name: ops-analytics-phase4
description: Define and operationalize the Phase 4 analytics baseline: evaluation datasets, cost dimensions, budget alerts, A/B experiment design, replay requirements, and KPI definitions.
---

# Ops Analytics Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 你要交付的东西

- RAG 评测数据集、指标和基线版本定义
- 成本维度、预算阈值和预警规则
- A/B 实验设计、分组口径和结果归因规则
- 执行回放的运营侧需求和验收口径
- 业务指标和成本仪表盘的 KPI 定义

## 实现约束

- 所有运营口径都必须可映射到代码、接口、事件或数据表，不能停留在抽象目标。
- 评测、成本、实验和回放需求必须服从安全脱敏和最小数据暴露原则。
- 不把运营分析扩成无边界 BI 平台或 Phase 5 运维主线。
- 评测和实验口径必须支持版本对比与历史追踪。
- 告警阈值和 KPI 必须明确单位、窗口和触发条件。

## 重点检查

- 评测数据集和指标是否足够支撑 RAG 效果量化
- 成本维度和阈值是否足够支撑预算预警
- A/B 分组和归因是否会污染主链路数据
- 回放需求是否可由现有 execution / event / node log 体系承接
- KPI 是否能被仪表盘和验收标准直接消费
