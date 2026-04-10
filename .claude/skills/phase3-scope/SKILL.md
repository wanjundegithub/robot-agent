---
name: phase3-scope
description: Shared scope guardrail for the service-robot Phase 3 multi-workflow scheduling, explicit switch/resume, concurrency control, and safety baseline.
---

# Phase 3 Scope

先阅读 `reference.md`，再开始任何跨服务实现、联调或评审。

## 这个技能负责什么

- 把工作约束在 Phase 3：增强多流程调度。
- 固定 Phase 3 的技术栈、通信协议、路由合同、状态机、调度语义和安全基线。
- 防止把 Phase 4 / Phase 5 内容提前塞进当前实现。

## 必须遵守的原则

- Phase 1 与 Phase 2 已经完成，Phase 3 的前提是“增强调度与安全”，不是推翻既有平台。
- 一切以 `服务机器人架构设计.md` 为源头文档。
- 任何跨模块设计都要同时满足 Java、Python、Frontend、Redis、审计和观测链路。
- 能用固定阈值、显式切换、有限并发满足 Phase 3，就不要提前做动态路由优化和生产级平台化控制台。

## 常见误区

- 把混合路由做成纯模型黑盒，忽略已发布工作流的 `entry_rule`。
- 把显式切换 / 显式恢复做成自动跳转，绕过用户确认。
- 只做并发执行，不定义 `suspended_stack`、恢复顺序和冲突处理。
- 只在前端弹安全提示，不在运行时、日志或事件里真正做 Prompt 清洗、输出校验、脱敏。
- 顺手把动态阈值、智能推荐、成本分析、高风险操作确认、ABAC 提前塞进当前阶段。

## 交付判断

以下内容同时成立，才算完成当前阶段:

- 流程执行中可中断切换。
- 切换后原流程可恢复。
- 多个流程可并发执行。
- Prompt 注入被防护。
- 结构化输出校验可验证。
- 敏感信息被脱敏。

混合路由、完整状态机、切换确认 UI、恢复提示 UI 也属于 Phase 3 完成项，必须覆盖。
