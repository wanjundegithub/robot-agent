# Phase 1 Reference

## 阶段目标

- Phase 1 名称: 跑通单流程闭环
- 周期: 4-6 周
- 目标: 打通完整链路，验证核心架构可行性

## 阶段完成内容

- 基础设施: 前后端项目初始化
- 数据库: 核心表创建
- 前端: 基础聊天 UI
- 前端: 拖拽节点与连线的编排器
- Java: Netty 网关、会话管理、工作流管理、SSE 消费与对前端推送
- Python: 执行引擎、路由引擎、状态机、上下文管理、P0 节点
- 通信: Java 和 Python 通过 HTTP + SSE
- 验收: 1 个完整流程跑通

## 必须支持的节点

- `start`
- `end`
- `llm`
- `condition`
- `form`

`tool` 是 P1，可留扩展点，但不能阻塞闭环交付。

## 核心技术选型

- Frontend: React + Vite + React Flow
- Java: Spring Boot 3.x
- Python: Python 3.11+ + FastAPI + LangGraph
- Database: MySQL 8.0+

## 通信约束

- Frontend -> Java: Netty + WebSocket
- Java -> Frontend: 同一 Netty + WebSocket 通道
- Java -> Python: HTTP + SSE
- 内部服务: HTTP REST

## 对外和跨服务关键接口

- `POST /api/sessions/{sessionId}/messages`
- `POST /api/executions/{executionId}/form-submit`
- `POST /api/execute` with `Accept: text/event-stream`

Java 到前端的 WebSocket 事件至少要能承载:
- `execution.started`
- `execution.completed`
- `execution.failed`
- `execution.suspended`
- `node.started`
- `node.completed`
- `node.failed`
- `form.requested`
- `message.delta`

## 关键表

- `workflow_definition`
- `workflow_version`
- `session`
- `execution`
- `execution_node_log`

关键字段要覆盖:
- `workflow_version.entry_rule`
- `session.current_execution_id`
- `session.suspended_stack`
- `execution.status`
- `execution.current_node_id`
- `execution.variables`
- `execution_node_log.input`
- `execution_node_log.output`
- `execution_node_log.metrics`

## 状态机重点

关键状态:
- `IDLE`
- `ROUTING`
- `RUNNING`
- `WAITING_USER`
- `WAITING_TOOL`
- `SUSPENDED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

Phase 1 重点转移:
- `IDLE -> ROUTING -> RUNNING`
- `RUNNING -> WAITING_USER`
- `WAITING_USER -> RUNNING`
- `RUNNING -> COMPLETED`
- `RUNNING -> FAILED`

## 路由实现建议

- 只要满足单流程闭环，优先使用 `workflow_version.entry_rule` 中的 `intent_codes`、`keywords`、`priority` 做简单规则路由。
- 不要提前实现 Phase 2 的规则 + 模型混合路由。

## 推荐演示流程

推荐使用文档示例 `flight_booking`:
- 用户发起航班查询
- `llm` 提取槽位
- `condition` 判断信息是否完整
- 不完整则进入 `form`
- 用户提交表单
- 恢复执行并输出结果

## 非目标

- `knowledge`
- `subflow`
- 复杂多流程切换与恢复
- 长期记忆写入
- 自动修改工作流定义
- Redis 幂等体系
- 资源隔离、超时重试、断线重连
- 向量库、对象存储、监控平台
