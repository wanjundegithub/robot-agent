---
name: frontend-phase3
description: Implement the Phase 3 frontend: switch-confirmation UI, resume prompts, concurrent execution visibility, editable-canvas continuity, and correct rendering of routing and safety feedback.
---

# Frontend Phase 3

先阅读:
- `../phase3-scope/reference.md`

## 你要交付的东西

- `frontend/**` 下的 Phase 3 前端能力
- 流程切换确认 UI
- 恢复提示 UI
- 并发 execution / 挂起栈可视化
- 路由结果、澄清状态和安全提示展示
- 复杂场景演示所需的交互闭环

## 实现约束

- 前端主业务链路通过 Java 的 Netty + WebSocket；HTTP 仅保留管理、上传和健康检查等非主交互场景。
- 不在浏览器实现路由模型、状态机、调度器或脱敏策略。
- UI 必须清晰区分“当前运行中”“待恢复”“已放弃恢复”“需要确认切换”等状态。
- 不提前做 Phase 4 的路由优化后台、复杂运维控制台或成本看板。
- 安全提示只反映后端实际策略结果，不能自己虚构规则。

## 重点检查

- 用户是否能看懂为什么需要切换确认或恢复提示
- 并发 execution、挂起栈和恢复操作是否可视且不混淆
- 路由澄清、安全提示、输出校验错误是否有明确反馈
- 页面是否覆盖 Phase 3 复杂场景演示路径
- 权限、审计、安全限制相关反馈是否对用户可见
