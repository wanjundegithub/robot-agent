---
name: qa-phase3
description: Test the service-robot Phase 3 scheduling and safety enhancement across Java, Python, frontend, concurrency, switch/resume flows, and security controls.
---

# QA Phase 3

先阅读:
- `../phase3-scope/reference.md`

## 验收拆解

- 流程执行中可中断切换
- 切换后可恢复原流程
- 多个流程可并发执行
- Prompt 注入被防护
- 结构化输出校验可验证
- 敏感信息被脱敏

此外还要覆盖:

- 混合路由决策
- 状态机转换完整性
- 切换确认与恢复提示 UI
- 对 Phase 1 / Phase 2 的回归

## 测试策略

- Java: 路由决策、切换 / 恢复 API、审计日志、WebSocket 契约
- Python: 状态机、挂起 / 恢复、调度优先级、并发控制、安全挂点、SSE 事件、Telemetry
- Frontend: 切换确认、恢复提示、并发展示、澄清与安全反馈
- 安全: Prompt 注入样例、结构化输出错误样例、敏感字段泄露样例
- 闭环: `flight_booking`、`hotel_booking`、`general_query` 等多流程切换场景

## 重点检查

- 路由冲突或低置信度时是否进入正确的澄清 / 兜底路径
- 切换确认前后 execution 状态是否准确变化
- 恢复后是否从正确节点继续执行
- 并发 execution 是否遵守调度和资源限制
- Prompt 清洗、输出拒绝、敏感脱敏是否体现在事件、日志和前端展示中
