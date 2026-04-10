---
name: python-phase3
description: Implement the Phase 3 Python runtime: full state machine, explicit interrupt/resume, priority scheduling, concurrency control, and runtime security hooks.
---

# Python Phase 3

先阅读:
- `../phase3-scope/reference.md`

## 你要交付的东西

- `python-ai/**` 下的 Phase 3 运行时能力
- 完整会话状态机
- 显式中断 / 恢复
- 优先级调度与并发控制
- 与混合路由决策联动的 execution 生命周期
- Prompt 清洗、结构化输出校验、敏感字段脱敏挂点
- OpenTelemetry / Prometheus 的 Phase 3 增量埋点
- 向 Java 输出 Phase 3 所需 SSE 事件

## 实现约束

- 继续使用 Java -> Python 的 HTTP + SSE 协议，不改变浏览器接入路径。
- Phase 3 允许多 execution 调度，但不能扩成开放式自治多代理平台。
- 切换 / 恢复必须符合显式确认语义，并围绕 `suspended_stack` 或等价结构实现。
- 并发控制必须定义优先级、活动槽位和等待状态的资源占用规则。
- Prompt / 输出 / 脱敏规则必须和 `security-phase3` 对齐，不能各写一套。

## 重点检查

- 状态机转换是否与架构文档一致
- `suspended_stack` 快照和恢复逻辑是否可追踪
- 调度优先级与并发限制是否清晰且可观测
- `llm` / `tool` / `form` / `knowledge` 事件是否经过安全挂点
- SSE 事件是否足够让 Java 持久化和前端展示切换 / 恢复 / 并发状态
