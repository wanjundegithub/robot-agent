---
name: security-phase3
description: Implement the Phase 3 security baseline: prompt-injection defense, structured-output validation, sensitive-data masking, and cross-service safety contracts.
---

# Security Phase 3

先阅读:
- `../phase3-scope/reference.md`

## 你要交付的东西

- Prompt 注入检测 / 清洗规则
- 安全 Prompt 构建约束
- LLM 结构化输出 schema 校验策略
- 敏感字段识别与脱敏规则
- 安全触发后的事件、日志、审计和测试要求

## 实现约束

- 以架构文档第 9 章为基线，不随意发明与现有节点体系脱节的新安全框架。
- 优先覆盖 `llm`、`tool`、`form`、日志、事件、审计这些真实风险面。
- 校验失败和脱敏行为必须可追踪、可测试、可解释。
- 不把 Phase 3 安全基线扩成 Phase 5 的高风险确认、ABAC 或企业级 DLP 平台。
- 安全规则必须由后端 / 运行时真正执行，前端只负责展示结果。

## 重点检查

- Prompt 清洗是否覆盖架构文档列出的典型注入模式
- 结构化输出是否在 schema 校验失败时阻断脏数据继续流转
- 敏感字段是否在日志、事件、审计和前端展示中统一脱敏
- 安全触发时是否能留下足够的审计和观测信息
- 是否存在明显的规则空洞或过度脱敏导致的业务不可用问题
