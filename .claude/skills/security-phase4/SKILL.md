---
name: security-phase4
description: Review the Phase 4 privacy and safety baseline: replay/evaluation/cost redaction, minimal exposure contracts, and residual-risk control on top of Phase 3 security.
---

# Security Phase 4

先阅读:
- `../phase4-scope/reference.md`

## 你要交付的东西

- replay / eval / cost / metrics 的最小可见字段集
- 脱敏规则与覆盖范围
- 安全触发后的审计要求
- 数据暴露风险清单和测试要求

## 实现约束

- 以 Phase 3 安全基线和架构文档第 9 章为基线，不得因为 Phase 4 运营能力而回退。
- 优先覆盖执行回放、评测数据集、成本报表、仪表盘、实验字段这些新增风险面。
- 脱敏、最小暴露和审计要求必须可落地、可测试、可解释。
- 不把 Phase 4 安全需求扩成 Phase 5 的权限平台、风控平台或 DLP 主线。
- 安全规则必须由后端 / 运行时真正执行，前端只负责展示结果。

## 重点检查

- 回放界面和回放对象是否泄露原始敏感数据
- 评测数据集、成本事件和仪表盘指标是否服从脱敏和最小暴露原则
- A/B 和回放字段是否会间接泄露用户身份或敏感内容
- 安全触发时是否有足够的审计和观测信息
- 是否存在明显规则空洞或过度脱敏导致的业务不可用问题
