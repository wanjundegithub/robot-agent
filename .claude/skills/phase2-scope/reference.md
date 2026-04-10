# Phase 2 Reference

## 阶段目标

- Phase 2 名称: 增强平台能力
- 周期: 6-8 周
- 目标: 在已完成的 Phase 1 闭环基础上，补齐版本管理、权限、审计、幂等、重试、知识检索、基础资源隔离与可观测性

## 基线前提

- Phase 1 的消息输入、工作流执行、`form` 挂起 / 恢复、前端实时展示、`execution` / `execution_node_log` 落库已可用
- Phase 2 不能通过替换基础通信链路来达成目标
- 现有 Frontend -> Java -> Python 调用方向保持不变

## 阶段完成内容

- 前端: 编排器实时校验、WebSocket 自动重连、版本操作配套界面、`knowledge` / `subflow` / 增强 `tool` 节点配置表达
- Java: Workflow 版本发布 / 回滚、基础 RBAC、操作审计、知识库相关接入层、运行中版本隔离
- Python: 消息 / 表单 / 工具三级幂等、超时与重试、基础资源隔离、`knowledge` 节点、增强 `tool` 节点、最小可用 `subflow`
- 数据: Redis 7.x、pgvector、`knowledge_base`、`knowledge_version`、`knowledge_document`、`audit_log`、RBAC 基础表
- 监控: OpenTelemetry、Prometheus、Grafana 基础链路
- 验收: 3 个真实流程演示，推荐 `flight_booking`、`hotel_booking`、`general_query`

## 必须支持的节点

- 继承 Phase 1: `start`、`end`、`llm`、`condition`、`form`
- Phase 2 完善: `tool`
- Phase 2 新增: `knowledge`、`subflow`

其中:

- `knowledge` 负责检索知识库内容，支持 `vector` / `keyword` / `hybrid` 模式配置
- `subflow` 负责最小可用的子流程调用；Phase 2 只要求同步、可控的子流程语义，不扩展成 Phase 3 的多流程调度体系

## 技术与通信约束

- Frontend: React + Vite + React Flow
- Java: Spring Boot 3.x
- Python: Python 3.11+ + FastAPI + LangGraph
- 数据 / 基础设施: MySQL 8.0+、Redis 7.x、pgvector、OpenTelemetry、Prometheus、Grafana
- Frontend -> Java: HTTP + WebSocket
- Java -> Python: HTTP + SSE
- 浏览器实时链路只能来自 Java；WebSocket 自动重连属于前端行为，不改变服务边界

## 关键事件与合同增量

在 Phase 1 的 `execution.*`、`node.*`、`form.requested`、`message.delta` 基础上，Phase 2 需要重点关注:

- `tool.called`
- `tool.returned`

以及与以下语义绑定的合同冻结:

- Workflow 发布 / 回滚
- 运行中 execution 的 `workflow_version` 固定
- `knowledge` 节点检索输入输出
- `subflow` 节点输入输出映射
- 幂等返回口径
- Trace / Metric 命名

## 关键数据模型

MySQL 重点表:

- 延续 Phase 1: `workflow_definition`、`workflow_version`、`session`、`execution`、`execution_node_log`
- Phase 2 新增: `knowledge_base`、`knowledge_version`、`knowledge_document`、`audit_log`
- RBAC 基础表: `role`、`tool_permission`、`role_permission`、`user_role`

关键字段重点:

- `workflow_definition.current_version`
- `workflow_version.status`
- `execution.workflow_version`
- `knowledge_version.status`
- `knowledge_document.status`
- `audit_log.workspace_id`
- `audit_log.user_id`
- `audit_log.resource_type`
- `audit_log.resource_id`
- `audit_log.action`

Redis Key Pattern:

- `session:{session_id}`
- `execution:{execution_id}`
- `msg:{session_id}:{message_id}`
- `form_submit:{execution_id}:{submit_id}`
- `tool:{tool_code}:{hash}`
- `rate_limit:{user_id}`
- `token_limit:{workflow_code}`
- `lock:{resource}`

向量库结构:

- Collection: `knowledge_chunks`
- 关键字段: `doc_id`、`chunk_id`、`kb_code`、`kb_version`、`content`、`embedding`、`metadata`

## 版本与隔离原则

- 发布新版本只影响后续新建 execution
- 回滚必须通过切换“当前发布版本”实现，不能篡改运行中 execution
- 旧会话 / 运行中 execution 继续沿用创建时绑定的 `workflow_version`

## 幂等与重试原则

- 消息幂等 Key: `msg:{session_id}:{message_id}`
- 表单幂等 Key: `form_submit:{execution_id}:{submit_id}`
- 工具幂等 Key: `tool:{tool_code}:{hash(input)}`
- 重试必须按错误类型分类，`validation_error`、`permission_denied` 等非可重试错误不能误重试

## 可观测性原则

- OpenTelemetry 至少覆盖 `execution.id`、`workflow.code`、`node.id`、`node.type`
- Prometheus 至少覆盖节点执行次数、节点耗时、任务完成率相关基础指标
- Phase 2 关注基础追踪与指标，不要求完整业务成本仪表盘

## Phase 2 验收标准

- 工作流可发布新版本
- 旧会话运行中不受新版本影响
- 表单提交幂等
- 工具调用支持重试
- 知识库检索可用
- 链路追踪完整

## 非目标

- 规则+模型混合路由
- 多流程显式切换 / 恢复
- 优先级调度与并发控制
- Prompt 注入防护
- 输出结构化校验
- 敏感信息脱敏
- 成本仪表盘
- 完整 ABAC / SpEL 引擎作为主线验收项
