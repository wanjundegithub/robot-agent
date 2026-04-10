---
name: frontend-phase1
description: Implement the Phase 1 frontend: chat UI, React Flow orchestrator, WebSocket execution stream, form interaction, and closed-loop flow visibility.
---

# Frontend Phase 1

先阅读:
- `../phase1-scope/reference.md`

## 你要交付的东西

- `frontend/**` 下的 React + Vite 工程
- 基础聊天界面
- React Flow 编排器
- WebSocket 事件消费与执行轨迹展示
- `form` 节点弹窗或面板交互
- 对话闭环演示页

## 实现约束

- 前端只调用 Java 接口，只订阅 Java 推送的 WebSocket。
- 编排器只覆盖 Phase 1 节点，不要提前平台化 Phase 2 节点能力。
- UI 重点是让用户看清流程状态，不是做完整后台系统。
- 如果后端合同未冻结，先用 mock 合同开发，不要自行发明新协议。

## 重点检查

- 用户发送消息后是否能看到执行中的节点事件
- `form.requested` 是否能触发表单展示和提交
- 最终结果是否与执行过程一致
- 编排器定义是否能映射到后端/运行时需要的节点结构
