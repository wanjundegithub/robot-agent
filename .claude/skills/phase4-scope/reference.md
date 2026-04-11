# Phase 4 Reference

## 阶段目标

- Phase 4 名称: 高级智能化
- 周期: 6-8 周
- 目标: 在已完成的 Phase 1 / Phase 2 / Phase 3 平台能力之上，补齐路由优化、智能子流程复用、测试框架、RAG 评测、成本追踪预警、A/B 测试、执行回放、业务指标和成本仪表盘

## 基线前提

- Phase 1 的消息输入、工作流执行、`form` 挂起 / 恢复、前端实时展示、`execution` / `execution_node_log` 落库已可用
- Phase 2 的版本管理、RBAC、审计、知识库、幂等、重试、资源隔离和基础可观测性已可用
- Phase 3 的混合路由、完整状态机、显式切换恢复和安全基线已可用
- Frontend -> Java -> Python 调用方向保持不变
- 运行中的 `execution` 继续固定绑定创建时的 `workflow_version`

## 阶段完成内容

- AI: 路由优化、动态阈值调整
- AI: 子流程复用增强、智能子流程推荐
- 运营: 测试框架、RAG 评测、成本追踪、预算预警、A/B 测试、执行回放
- 监控: 业务指标、成本仪表盘

## 技术与通信约束

- Frontend: React + Vite + React Flow
- Java: Spring Boot 3.x
- Python: Python 3.11+ + FastAPI + LangGraph
- 数据 / 基础设施: MySQL 8.0+、Redis 7.x、pgvector、OpenTelemetry、Prometheus、Grafana
- Frontend -> Java: HTTP + WebSocket
- Java -> Python: HTTP + SSE
- 浏览器实时链路只能来自 Java；不能直连 Python

## 路由优化约束

Phase 4 的路由优化必须建立在 Phase 3 路由合同之上:

- 保留 `entry_rule` + 模型分类的混合路由结构
- 新增动态阈值时，必须明确阈值来源、适用范围、回退策略和可观测字段
- 动态阈值至少要可关联: `workflow_code`、`intent_code`、`version`、`threshold_source`
- 不能跳过版本发布、审计和实验字段

## 智能子流程推荐约束

- 推荐对象必须来自已发布子流程或已知可复用流程集合
- 推荐结果至少包含: `subflow_code`、`subflow_version`、`score`、`reason`
- 推荐只负责辅助决策或配置提示，不能自动改写工作流定义
- 推荐结果必须可追踪到具体模型 / 规则 / 评测版本

## 测试框架与评测约束

Phase 4 必须冻结以下评测要素:

- 测试分层: 单测、集成测试、联调测试、评测验证
- RAG 评测数据集结构
- 评测指标: 至少包含命中 / 相关性 / 可解释结果之一
- 基线版本、对比版本和结果存储格式
- 评测结果必须可复跑和可比较

## 成本追踪与预警约束

成本能力至少覆盖:

- 模型级成本
- 工作流级成本
- 用户级成本
- 全局成本

预算预警至少冻结:

- 统计窗口
- 告警阈值
- 告警触发字段
- 告警事件或通知出口

成本事件至少包含:

- `model`
- `workflow_code`
- `workflow_version`
- `input_tokens`
- `output_tokens`
- `cost`
- `user_id`
- `session_id`
- `execution_id`

## A/B 测试约束

- A/B 实验必须明确实验 ID、实验分组、实验对象、实验版本和归因指标
- 实验分组至少要能关联 execution 和最终结果指标
- 不能让实验流量和非实验流量混成不可区分的执行数据
- 实验结果必须能被仪表盘和回放能力消费

## 执行回放约束

- 执行回放必须以历史 execution 和事件日志为基础
- 回放输入、回放时间、回放版本和回放结果必须可追踪
- 回放不能覆盖或篡改原执行事实
- 回放产物至少要能服务于调试、对比和评审

推荐冻结的回放对象:

- `execution_id`
- `workflow_code`
- `workflow_version`
- `session_id`
- `input_variables`
- `event_stream`
- `node_logs`
- `final_output`

## 业务指标与仪表盘约束

至少要支持以下业务指标:

- `intent_accuracy`
- `task_completion_rate`
- `completion_time`
- `human_intervention_rate`

至少要支持以下成本指标:

- `llm_cost_total`
- `token_consumption_total`
- 按模型 / 工作流 / 用户分维度聚合

仪表盘必须明确:

- 聚合周期
- 数据刷新方式
- 指标命名和单位
- 趋势与对比口径

## 安全与隐私基线

- Phase 3 的 Prompt 防护、输出校验、敏感脱敏继续生效
- replay / eval / cost / metrics 链路不得泄露原始敏感数据
- 对日志、事件、审计、仪表盘和评测结果中的敏感字段保持统一脱敏口径

## Phase 4 验收标准

- 能运行自动化测试
- RAG 效果可量化评估
- 成本可追踪预警
- 业务指标可视化
- 支持 A/B 测试

补充完成项:

- 动态阈值调整具备最小可用闭环
- 智能子流程推荐具备可追踪输出
- 执行回放具备最小可用闭环
- 成本仪表盘可展示

## 非目标

- 数据库索引优化
- Redis 集群
- 向量库分片
- 熔断器
- ABAC 作为主线能力
- 高风险操作二次确认作为主线能力
- 完全自动自学习路由系统
- 自动修改工作流定义
