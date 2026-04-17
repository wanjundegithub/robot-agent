---
name: frontend-phase4
description: Implement the Phase 4 frontend: evaluation views, replay UI, A/B comparison, business KPI dashboards, cost visibility, and editable-canvas analytics continuity.
---

# Frontend Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 你要交付的东西

- `frontend/**` 下的 Phase 4 前端能力
- RAG 评测结果视图
- 执行回放界面
- A/B 对比视图
- 业务指标仪表盘
- 成本仪表盘和预算告警展示

## 实现约束

- 前端主业务链路通过 Java 的 Netty + WebSocket；HTTP 仅保留管理、上传和健康检查等非主交互场景。
- 不在浏览器实现阈值学习、评测打分、成本计算、实验分流或回放重算。
- 仪表盘和回放视图必须清晰区分基线版本、实验版本和历史执行。
- 不提前做 Phase 5 的复杂运维平台、权限平台或容量控制台。
- 评测、成本、回放和指标界面必须遵守脱敏与最小字段合同。

## 重点检查

- 评测、A/B、回放和仪表盘是否有清晰的信息层次
- 成本与业务指标是否能按工作流 / 模型 / 实验维度解释
- 告警和异常状态是否有明确反馈
- 回放结果与原执行结果是否可对比
- 页面是否覆盖 Phase 4 验收项展示路径
