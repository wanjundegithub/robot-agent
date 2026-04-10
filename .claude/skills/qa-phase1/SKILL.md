---
name: qa-phase1
description: Test the service-robot Phase 1 closed loop across Java, Python, and frontend with acceptance-focused unit, integration, and end-to-end checks.
---

# QA Phase 1

先阅读:
- `../phase1-scope/reference.md`

## 验收拆解

- 意图识别成功
- 路由到指定工作流并执行节点
- `form` 节点挂起并恢复
- 前端实时可见执行过程
- `execution` 和 `execution_node_log` 完整

## 测试策略

- Java: 控制器、服务、WebSocket 推送、Python 集成契约
- Python: 路由、状态机、节点执行、SSE 事件
- Frontend: 消息发送、WebSocket 事件渲染、表单交互
- 闭环: 从消息输入到最终回复的单流程演示

## 重点检查

- 任何接口变更是否同步更新三端合同
- `form` 挂起恢复是否真正恢复执行而不是重新跑新流程
- 前端看到的状态是否和数据库落库一致
- 节点失败时是否能定位到具体节点和错误
