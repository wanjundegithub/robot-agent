# Phase 3 Reference

## 阶段目标

- Phase 3 名称: 增强多流程调度
- 周期: 4-6 周
- 目标: 在已完成的 Phase 1 / Phase 2 平台能力之上，补齐混合意图路由、完整状态机、显式中断恢复、优先级调度、并发控制和安全基线

## 基线前提

- Phase 1 的消息输入、工作流执行、`form` 挂起 / 恢复、前端实时展示、`execution` / `execution_node_log` 落库已可用
- Phase 2 的版本管理、RBAC、审计、知识库、幂等、重试、资源隔离和基础可观测性已可用
- Frontend -> Java -> Python 调用方向保持不变
- 运行中的 `execution` 继续固定绑定创建时的 `workflow_version`

## 阶段完成内容

- Java: 规则 + 模型混合路由、切换 / 恢复入口、相关审计与浏览器合同
- Python: 完整会话状态机、显式中断恢复、优先级调度、并发控制
- Frontend: 流程切换确认 UI、恢复提示 UI、并发执行可视化
- 安全: Prompt 注入防护、结构化输出校验、敏感字段脱敏
- 验收: 多流程切换与恢复复杂场景

## 技术与通信约束

- Frontend: React + Vite + React Flow
- Java: Spring Boot 3.x
- Python: Python 3.11+ + FastAPI + LangGraph
- 数据 / 基础设施: MySQL 8.0+、Redis 7.x、pgvector、OpenTelemetry、Prometheus、Grafana
- Frontend -> Java: HTTP + WebSocket
- Java -> Python: HTTP + SSE
- 浏览器实时链路只能来自 Java；不能直连 Python

## 路由合同

Phase 3 的路由必须同时利用工作流发布配置和模型分类结果:

- 规则层输入来自已发布工作流的 `entry_rule`
- `entry_rule` 至少包含: `intent_codes`、`keywords`、`priority`
- 模型层至少输出: `intent_code`、`confidence`、`reason`
- 最终路由决策至少输出: `decision`、`workflow_code`、`workflow_version`、`confidence`、`reason`、`candidate_workflows`

推荐冻结的决策类型:

- `start`: 直接启动新流程
- `switch_required`: 检测到新意图，但当前已有运行中流程，必须先确认切换
- `clarify`: 置信度不足或多个候选冲突，需要用户澄清
- `fallback`: 进入通用问答或默认兜底流程

固定阈值必须由 Phase 3 合同明确给出，不能把动态阈值调整提前做到当前阶段。

## 状态机约束

会话状态必须至少覆盖:

- `IDLE`
- `ROUTING`
- `RUNNING`
- `WAITING_USER`
- `WAITING_TOOL`
- `SUSPENDED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

必须支持的核心转换:

- `IDLE -> ROUTING -> RUNNING`
- `RUNNING -> WAITING_USER`
- `RUNNING -> WAITING_TOOL`
- `RUNNING -> SUSPENDED`
- `SUSPENDED -> RUNNING`
- `RUNNING -> COMPLETED`
- `RUNNING -> FAILED`
- `RUNNING -> CANCELLED`
- `FAILED -> IDLE`
- `CANCELLED -> IDLE`

其中:

- `WAITING_USER` 用于表单补充和路由澄清
- `WAITING_TOOL` 用于异步工具或外部结果回调
- `SUSPENDED` 用于显式切换后的原流程暂存

## 中断与恢复约束

Phase 3 只允许显式切换 / 显式恢复:

- 当存在运行中 execution 且检测到新意图时，必须进入切换确认流程
- 用户确认后，当前 execution 才能进入 `SUSPENDED`
- 被挂起的 execution 必须进入 `suspended_stack` 或等价结构
- 当前流程完成后，系统必须能提示用户恢复栈顶流程或显式放弃恢复

`suspended_stack` 的逻辑字段至少包括:

- `execution_id`
- `workflow_code`
- `workflow_version`
- `current_node_id`
- `state`
- `snapshot`
- `suspended_at`
- `reason`

## 调度与并发约束

Phase 3 必须冻结以下调度语义:

- 调度优先级来源至少包含工作流 `entry_rule.priority`
- 单个 session 可以存在多个 execution，但不能无限并发
- `WAITING_USER`、`WAITING_TOOL`、`SUSPENDED` execution 不应长期占用活动执行槽位
- 调度器必须定义冲突处理规则，例如同 session、新消息、恢复请求和工具回调同时到达时的优先级
- 并发控制必须可观测、可审计、可回放日志

推荐冻结的 Redis / 锁语义:

- `route_lock:{session_id}`
- `switch_request:{session_id}:{message_id}`
- `execution_snapshot:{execution_id}`
- `schedule:session:{session_id}`
- `schedule:workflow:{workflow_code}`

具体实现可调整，但 Key Pattern 和语义必须先冻结。

## 关键事件与合同增量

在 Phase 1 / Phase 2 事件基础上，Phase 3 需要重点冻结以下语义:

- `routing.decided`
- `execution.switch_requested`
- `execution.suspended`
- `execution.resumed`
- `execution.resume_offered`
- `security.prompt_sanitized`
- `security.output_rejected`

这些事件至少要能关联:

- `session_id`
- `execution_id`
- `workflow_code`
- `workflow_version`
- `node_id` 或 `message_id`
- `reason` / `confidence` / `masked_fields` 等补充字段

## 安全基线

Prompt 防护至少要覆盖架构文档中的注入样式:

- 诱导忽略历史指令
- 诱导暴露 system prompt
- 伪造角色或标记
- 代码块 / 特殊包裹格式注入

输出校验至少要求:

- `llm` 节点开启 `structured_output` 时必须做 schema 校验
- 校验失败必须有明确错误分类和回退策略
- 失败结果不能直接当作可信结构化数据流入后续节点

敏感脱敏至少要求:

- 对 `password`、`secret`、`token`、`api_key`、`phone`、`mobile`、`id_card` 等字段做统一识别
- 脱敏要覆盖日志、事件、审计和前端展示
- 对工具参数、表单数据、模型输出中的敏感字段保持一致口径

## 审计与观测

Phase 3 新增重点审计点:

- 路由决策
- 切换确认 / 取消切换
- execution 挂起 / 恢复 / 放弃恢复
- Prompt 清洗触发
- 输出校验失败

OpenTelemetry 至少继续覆盖:

- `execution.id`
- `workflow.code`
- `workflow.version`
- `session.id`
- `node.id`
- `node.type`
- `route.decision`

## Phase 3 验收标准

- 流程执行中可中断切换
- 切换后可恢复原流程
- 多个流程可并发执行
- Prompt 注入被防护
- 敏感信息被脱敏

补充完成项:

- 结构化输出校验可验证
- 切换确认 UI 和恢复提示 UI 可演示

## 非目标

- 动态阈值调整
- 智能子流程推荐
- 成本追踪与预警
- A/B 测试
- 执行回放
- 业务成本仪表盘
- Redis 集群、向量库分片、熔断器
- ABAC 作为主线能力
- 高风险操作二次确认作为主线能力
