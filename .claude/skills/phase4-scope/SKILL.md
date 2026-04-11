---
name: phase4-scope
description: Shared scope guardrail for the service-robot Phase 4 advanced intelligence, evaluation, cost analytics, experimentation, replay, and KPI visibility.
---

# Phase 4 Scope

先阅读 `reference.md`，再开始任何跨服务实现、联调或评审。

## 这个技能负责什么

- 把工作约束在 Phase 4：高级智能化。
- 固定 Phase 4 的技术栈、通信协议、评测合同、成本维度、实验语义、回放对象和指标基线。
- 防止把 Phase 5 内容提前塞进当前实现。

## 必须遵守的原则

- Phase 1、Phase 2、Phase 3 已经完成，Phase 4 的前提是“增强智能化与运营能力”，不是推翻既有平台。
- 一切以 `服务机器人架构设计.md` 为源头文档。
- 任何跨模块设计都要同时满足 Java、Python、Frontend、Ops、Security、Telemetry 和审计链路。
- 能用固定数据集、可追踪实验、可解释指标满足 Phase 4，就不要提前做 Phase 5 的平台化运维和集群主线。

## 常见误区

- 把动态阈值做成黑盒在线自学习，绕过现有路由合同。
- 把智能子流程推荐做成自动修改工作流定义。
- 只做仪表盘展示，不冻结指标公式、成本维度、实验字段和回放对象模型。
- 只做回放界面，不保存足够的执行事实和事件源。
- 只做评测“报告”，没有可运行的数据集、指标脚本或基线对比。
- 为了分析和回放暴露未脱敏的用户输入、工具参数或模型输出。

## 交付判断

以下内容同时成立，才算完成当前阶段:

- 自动化测试可运行。
- RAG 效果可量化评估。
- 成本可追踪预警。
- 业务指标可视化。
- A/B 测试可验证。
- 执行回放具备最小可用闭环。

动态阈值调整、智能子流程推荐、成本仪表盘也属于 Phase 4 完成项，必须覆盖。
