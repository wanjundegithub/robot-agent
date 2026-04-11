---
name: frontend-phase5
description: Implement the Phase 5 frontend: high-risk confirmation UI, ABAC feedback, degradation states, and production observability surfaces.
---

# Frontend Phase 5

先阅读:
- `../phase5-scope/reference.md`

## 你要交付的东西

- `frontend/**` 下的 Phase 5 前端能力
- 高风险操作确认界面
- ABAC 权限拒绝 / 限制反馈
- 限流 / 熔断 / 降级提示
- 归档 / 清理状态可见性

## 实现约束

- 前端只调用 Java 接口，只订阅 Java 推送的 WebSocket。
- 不在浏览器实现权限引擎、熔断判定、限流逻辑或清理逻辑。
- 确认、权限和降级视图必须清晰区分“拒绝”“等待确认”“降级执行”“已限流”。
- 不把生产状态展示扩成无边界运维平台。
- 所有界面都必须遵守脱敏与最小字段合同。

## 重点检查

- 用户是否能理解为何被拒绝、限流或要求确认
- 高风险确认是否有清晰的确认 / 取消路径
- 降级和熔断状态是否有明确反馈
- 日志归档 / 清理结果是否有必要可见性
- 页面是否覆盖 Phase 5 目标展示路径
