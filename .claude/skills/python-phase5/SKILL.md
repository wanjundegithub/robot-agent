---
name: python-phase5
description: Implement the Phase 5 Python runtime: throttling, graceful degradation, confirmation hooks, circuit-breaker integration, and vector-access optimization.
---

# Python Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 你要交付的东西

- `python-ai/**` 下的 Phase 5 运行时能力
- 更细粒度限流
- 优雅降级与熔断配合
- 高风险操作二次确认挂点
- 向量访问优化 / 分片挂点
- 运行时状态和保护事件

## 实现约束

- 继续使用 Java -> Python 的 HTTP + SSE 协议，不改变浏览器接入路径。
- 限流、降级、确认和熔断必须建立在现有执行模型之上。
- 不能绕开既有安全、审计、评测和回放体系。
- 向量访问优化和缓存策略必须可追踪、可回退、可测试。
- 不把 Phase 5 优化扩成与当前仓库脱节的平行运行时。

## 重点检查

- 限流粒度和触发条件是否清晰
- 降级与熔断是否有明确回退路径
- 高风险确认是否能阻断未确认操作
- 向量访问优化是否保留正确性与观测性
- 运行时事件是否足够让 Java、Frontend 和 QA 理解系统保护状态
