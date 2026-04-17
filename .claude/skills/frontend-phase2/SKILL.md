---
name: frontend-phase2
description: Implement the Phase 2 frontend: editable orchestrator validation, Netty/WebSocket auto-reconnect, version operations, new node configuration, and stable execution visibility.
---

# Frontend Phase 2

先阅读:
- `../phase2-scope/reference.md`

## 你要交付的东西

- `frontend/**` 下的 Phase 2 前端能力
- 编排器实时校验
- WebSocket 自动重连与状态提示
- Workflow 版本发布 / 回滚相关交互
- `knowledge` / `subflow` / 增强 `tool` 节点配置界面
- 3 个真实流程的可演示页面

## 实现约束

- 前端主业务链路通过 Java 的 Netty + WebSocket；HTTP 仅保留管理、上传和健康检查等非主交互场景。
- 重连只作用于 Java WebSocket，不把浏览器改成直连 Python。
- 校验规则必须基于冻结后的后端合同，不自行发明 DSL。
- 不在浏览器实现权限引擎、重试策略、幂等逻辑或向量检索逻辑。
- 不提前做 Phase 3 的流程切换 / 恢复面板或复杂调度 UI。

## 重点检查

- 编排器能否即时发现节点 / 连线 / 版本配置错误
- WebSocket 断开后是否能自动重连并恢复界面状态
- 发布 / 回滚交互是否清晰反映版本状态
- 新节点配置是否能映射到后端 / 运行时需要的结构
- 权限限制、审计相关反馈是否对用户可见
