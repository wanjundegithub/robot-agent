---
name: python-phase1
description: Implement the Phase 1 Python runtime: simple intent routing, scheduler, state machine, context, P0 nodes, and SSE execution event streaming.
---

# Python Phase 1

先阅读:
- `../phase1-scope/reference.md`

## 你要交付的东西

- `python-ai/**` 下的 FastAPI + LangGraph 工程
- 路由引擎
- 调度器
- 状态机
- 上下文管理
- `start`、`end`、`llm`、`condition`、`form` 节点
- 执行入口和表单恢复能力
- 向 Java 输出 SSE 流式事件

## 实现约束

- 路由优先按 `entry_rule` 做简单规则实现。
- `form` 必须支持挂起再恢复，不能只返回“请补充信息”的文本。
- 输出事件要能让 Java 持久化 `execution` 和 `execution_node_log`。
- `tool` 只做可扩展接口，不能拖慢 Phase 1。

## 重点检查

- 状态机转移是否覆盖 `RUNNING -> WAITING_USER -> RUNNING`
- `condition` 是否显式决定分支
- `llm` 输出是否能喂给后续节点
- SSE 是否持续输出 `execution.*`、`node.*`、`form.requested`、`message.delta`
