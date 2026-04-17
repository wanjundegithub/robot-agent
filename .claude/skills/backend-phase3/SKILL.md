---
name: backend-phase3
description: Implement the Java Phase 3 gateway layer: hybrid intent routing, switch/resume contracts, session/execution aggregation, audit logging, and stable browser-facing APIs.
---

# Backend Phase 3

先阅读:
- `../phase3-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下与 Phase 3 相关的 Spring Boot 实现
- 规则 + 模型混合路由入口
- 切换确认、恢复确认、恢复放弃等 Netty + WebSocket / HTTP 管理接口合同
- Session / Execution / `suspended_stack` 聚合与持久化协同
- 路由、切换、恢复、安全相关审计写入点
- Java -> Python 契约的 Phase 3 增量封装
- Java -> Frontend 稳定推送能力

## 实现约束

- Java 仍然是浏览器唯一接入层。
- 路由必须基于已发布工作流与固定阈值模型结果共同决策，不能做黑盒自动跳转。
- 切换必须先确认，再把当前 execution 置为挂起。
- 恢复必须显式触发，不能在后台静默续跑。
- 审计日志要围绕路由决策、切换确认、恢复、输出校验失败、安全触发等关键动作落点。
- 允许新增 DTO、表字段、Redis 依赖和事件，但不能破坏 Phase 1 / Phase 2 的 Netty + WebSocket 主合同。

## 重点检查

- 路由候选、阈值、决策结果是否与 scope 一致
- Session / Execution 聚合是否能支撑挂起栈和恢复顺序
- Java 到 Python 的事件、Trace 字段和状态值是否一致
- WebSocket 是否能支撑切换 / 恢复 / 并发场景下的稳定展示
- 审计是否覆盖路由、切换、恢复与安全拒绝
