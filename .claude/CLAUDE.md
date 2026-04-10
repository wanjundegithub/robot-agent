# 服务机器人 Phase 3 - Claude Code 执行手册

当前仓库此轮交付目标是严格依照 `服务机器人架构设计.md` 完成 Phase 3：增强多流程调度 (4-6 周)。

Phase 1 与 Phase 2 已视为完成基线。任何 Phase 3 实现都必须建立在单流程闭环、版本管理、幂等、重试、知识检索、基础权限与审计稳定可用的前提上，不能通过重写既有链路来换取新能力。

如果用户请求、现有代码、或临时想法与架构设计文档冲突，以架构设计文档为准，并明确指出冲突点。

@./skills/phase3-scope/reference.md
@./skills/team-delivery/handoff.md

## 必须完成的 Phase 3 范围

- 以前两阶段闭环为基线，保持消息输入、版本绑定、执行日志、知识检索、表单挂起/恢复、前端实时展示持续稳定。
- Java 层完成意图路由入口，采用 `entry_rule` + 模型分类的混合路由，并对切换确认、恢复确认、审计写点和浏览器侧合同负责。
- Python 层完成完整会话状态机、显式中断/恢复、优先级调度、并发控制，以及与路由决策联动的多 execution 运行时。
- 前端完成流程切换确认 UI、恢复提示 UI，并能让用户看清当前运行流程、挂起流程和恢复动作。
- 安全能力必须覆盖 Prompt 注入防护、LLM 结构化输出校验、敏感字段脱敏，并体现在运行时、日志、事件或审计中。
- 至少交付能体现多流程切换与恢复的复杂场景，推荐围绕 `flight_booking`、`hotel_booking`、`general_query` 做串并行演示。

## 明确不做的内容

- 不做 Phase 4 及之后的能力: 动态阈值调整、智能子流程推荐、RAG 评测平台、成本追踪预警、A/B 测试、执行回放、业务仪表盘。
- 不做 Phase 5 的生产优化主线: 数据库索引优化、Redis 集群、向量库分片、熔断器、ABAC 主线、高风险操作二次确认。
- 不允许把显式切换/显式恢复偷换成“自动跨流程跳转”或“无限制多代理自由协作”。
- 不允许把前端实时链路改成直连 Python；浏览器仍然只连接 Java 的 HTTP / WebSocket。
- 不允许为了做多流程调度而破坏 Phase 1/2 既有消息、表单、版本、知识库、审计和追踪合同。

## 架构硬约束

- 前端技术栈: React + Vite + React Flow。
- Java 技术栈: Spring Boot 3.x，负责接入层、路由入口、审计聚合、会话与执行聚合、浏览器推送。
- Python 技术栈: Python 3.11+ + FastAPI + LangGraph，负责状态机、调度、节点执行、安全校验挂点。
- 数据与基础设施: MySQL 8.0+、Redis 7.x、pgvector、OpenTelemetry、Prometheus、Grafana。
- Frontend 到 Java: HTTP 请求 + WebSocket 推送。
- Java 到 Python: HTTP 请求 + SSE 流式事件。
- Phase 3 路由必须基于已发布工作流的 `entry_rule` 与固定阈值模型分类协同工作；动态阈值不属于当前阶段。
- 会话状态机必须至少覆盖 `IDLE`、`ROUTING`、`RUNNING`、`WAITING_USER`、`WAITING_TOOL`、`SUSPENDED`、`COMPLETED`、`FAILED`、`CANCELLED`。
- 中断和恢复必须走显式用户确认，并围绕 `suspended_stack` 或等价结构保存恢复所需上下文。
- 多 execution 可以并发执行，但每个 execution 仍必须固定绑定创建时的 `workflow_version`。
- Prompt 防护、输出校验、敏感脱敏必须对齐架构文档第 9 章示例，不得只停留在 UI 提示层。

## 默认团队编组

遇到需要实施、重构、联调、或验收的工作时，Claude Code 必须默认组建以下 team 并分工推进:

- `tech-leader`: 负责 Phase 3 拆解、接口冻结、范围控制、跨角色评审与最终验收。
- `backend-java`: 负责 `java-backend/**` 的混合路由入口、会话/执行聚合、审计、浏览器合同与持久化。
- `frontend`: 负责 `frontend/**` 的切换确认、恢复提示、并发执行可视化与实时交互。
- `python-dev`: 负责 `python-ai/**` 的状态机、调度器、中断恢复、安全挂点与执行事件。
- `security`: 负责 Prompt 防护、结构化输出校验、脱敏规则、攻击面检查与安全评审。
- `qa`: 负责复杂场景测试设计、自动化用例、联调验收、回归与安全验证。

## 协作顺序

1. 先让 `tech-leader` 冻结 `entry_rule`、混合路由决策合同、状态机口径、`suspended_stack` 结构、调度优先级/并发语义、切换/恢复事件和安全策略边界。
2. `backend-java` 与 `python-dev` 先对齐路由输入输出、execution 生命周期、挂起/恢复语义、并发控制口径、SSE / WebSocket 事件和追踪字段，再并行开发。
3. `security` 与 `python-dev`、`backend-java` 对齐 Prompt 清洗、输出校验失败合同、敏感字段名单、脱敏覆盖范围与审计写点。
4. `frontend` 基于冻结后的 HTTP / WebSocket 合同开发切换确认、恢复提示、并发执行视图和错误反馈，不自行发明调度逻辑。
5. `qa` 依据 Phase 3 验收标准和复杂场景先写测试矩阵，再补单测、集成测试、联调脚本和安全回归。
6. 最后由 `tech-leader` 做集成评审，确认实现既没有越过 Phase 3 边界，也没有回归 Phase 1/2。

## 角色边界

- `tech-leader` 主要产出设计决策、任务拆解、评审结论，不直接吞并所有实现任务。
- `backend-java` 不负责 Python 内部状态机、调度器细节和浏览器内交互编排。
- `frontend` 不直连 Python，也不在浏览器里重写路由决策、调度器、幂等、重试或安全策略引擎。
- `python-dev` 不负责 Java 侧路由入口聚合、审计落库界面语义或前端交互文案。
- `security` 负责安全规则与评审，不替代业务开发角色实现主流程，也不把 Phase 5 安全大项提前扩成主线工程。
- `qa` 可以补测试夹具、契约测试和攻击样例，但不替代开发角色实现主功能。

## 验收门禁

任何实现都必须最终支撑以下 Phase 3 门禁:

- 流程执行中可中断切换。
- 切换后可恢复原流程。
- 多个流程可并发执行。
- Prompt 注入被防护。
- 敏感信息被脱敏。

此外，规则+模型混合路由、完整状态机、结构化输出校验、切换确认 UI、恢复提示 UI 都属于 Phase 3 完成项，不得遗漏。
