---
name: phase5-scope
description: Shared scope guardrail for the service-robot Phase 5 production optimization, resilience hardening, log lifecycle, and security deepening.
---

# Phase 5 Scope

先阅读 `reference.md`，再开始任何跨服务实现、联调或评审。

## 这个技能负责什么

- 把工作约束在 Phase 5：生产优化。
- 固定 Phase 5 的性能、稳定性、可维护性和安全深化边界。
- 固定 Netty + WebSocket 主业务通信、可编辑画布和模型 Profile 配置边界。
- 防止把超出当前仓库承载能力的基础设施重建提前塞进当前实现。

## 必须遵守的原则

- Phase 1、Phase 2、Phase 3、Phase 4 已经完成，Phase 5 的前提是“生产级优化与治理”，不是推翻既有平台。
- 一切以 `服务机器人架构设计.md` 为源头文档。
- 任何跨模块设计都要同时满足 Java、Python、Frontend、Platform、Security、Telemetry、审计和清理链路。
- 前端主业务链路以 Java 的 Netty + WebSocket 为准，HTTP 只保留管理 / 上传类场景。
- 主执行链路禁止返回 Mock / Demo / Stub 数据。
- 意图命中、知识改写、知识回答和通用 LLM 节点必须消费真实 Provider + Model Profile 配置。
- 能用可追踪、可回退、可测试的优化方案满足 Phase 5，就不要做脱离当前仓库的超大规模基础设施改造。

## 常见误区

- 把索引、集群、分片优化做成脱离业务合同的孤立基础设施工程。
- 只升级后端保护能力，却不冻结 Netty + WebSocket action / ack / event 契约和画布 schema。
- 只做限流/熔断配置，不定义触发条件、审计写点、回退口径和前端反馈。
- 只做 ABAC 模型，不定义属性来源、表达式边界和拒绝口径。
- 只做高风险确认弹窗，不定义确认对象、有效期、审计和取消行为。
- 只做日志归档，不做分层、保留期、清理任务和恢复入口。
- 继续允许主链路依赖 mock 合同、假知识命中或写死模型名。
- 为了生产优化保留未脱敏的原始敏感日志或回放数据。

## 交付判断

以下内容同时成立，才算完成当前阶段:

- 数据路径具备性能优化控制点。
- 稳定性保护机制可验证。
- 日志归档与清理自动化可验证。
- 权限细化 (ABAC) 可执行。
- 高风险操作二次确认可执行。
- Netty + WebSocket 主链、可编辑画布和模型 Profile 配置可验证。

Redis 集群、向量库分片、熔断器、限流细化、日志分层治理也属于 Phase 5 完成项，必须覆盖。
