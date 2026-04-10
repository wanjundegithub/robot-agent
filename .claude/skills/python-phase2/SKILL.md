---
name: python-phase2
description: Implement the Phase 2 Python runtime: idempotency, retry, resource isolation, knowledge/subflow/tool execution, and observability on top of the Phase 1 engine.
---

# Python Phase 2

先阅读:
- `../phase2-scope/reference.md`

## 你要交付的东西

- `python-ai/**` 下的 Phase 2 运行时能力
- 消息 / 表单 / 工具三级幂等
- 超时与重试策略
- 基础资源隔离与限流
- `knowledge` 节点
- 增强 `tool` 节点
- 最小可用 `subflow` 节点
- OpenTelemetry 与 Prometheus 基础埋点
- 向 Java 输出 Phase 2 所需 SSE 事件

## 实现约束

- 继续使用 Java -> Python 的 HTTP + SSE 协议，不改变浏览器接入路径。
- Phase 2 允许引入 Redis、向量检索与基础隔离，但不能顺手扩展成 Phase 3 的多流程调度框架。
- `subflow` 以同步、受控、可追踪为先，不做开放式多流程自由切换。
- 重试必须基于错误分类；权限、校验、业务硬失败不应被误重试。
- `knowledge` 默认使用最新发布版本，指定版本时必须可追踪。

## 重点检查

- 幂等 Key 是否与架构文档一致
- 工具重试策略是否按错误类型分层
- `knowledge` 输出是否可喂给后续节点
- `subflow` 是否保持父子 execution 边界清晰
- Trace / Metric / SSE 事件是否足够让 Java 持久化和前端展示
