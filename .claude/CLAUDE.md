# 服务机器人 Phase 1 - Claude Code 执行手册

当前仓库的唯一交付目标是严格依照 `服务机器人架构设计.md` 完成 Phase 1: 跑通单流程闭环 (4-6 周)。

如果用户请求、现有代码、或临时想法与架构设计文档冲突，以架构设计文档为准，并明确指出冲突点。

@./skills/phase1-scope/reference.md
@./skills/team-delivery/handoff.md

## 必须完成的 Phase 1 范围

- 打通单个业务流程的完整闭环，不追求平台能力完备。
- 前端完成基础聊天界面和 React Flow 编排器。
- Java 层完成 API 网关、Session CRUD、Workflow CRUD、执行入口、表单提交入口、WebSocket 推送。
- Python 层完成路由引擎、调度器、状态机、上下文管理、P0 节点执行。
- MySQL 落地核心表: `workflow_definition`、`workflow_version`、`session`、`execution`、`execution_node_log`。
- Java 到 Python 使用 HTTP + SSE 流式通信。
- 至少交付 1 个可演示工作流，推荐使用文档中的 `flight_booking` 场景。

## 明确不做的内容

- 不做 Phase 2 及之后的能力: `knowledge`、`subflow`、版本回滚、RBAC、审计日志、WebSocket 自动重连、幂等体系、超时重试、资源隔离、监控平台。
- 不做 V1 非目标: 任意多代理自由协作、无边界自动跨流程跳转、开放式长期记忆写入、自动修改工作流定义、多租户计费。
- 不为 Phase 1 引入 `pgvector`、`MinIO`、`OpenTelemetry`、`Prometheus`、`Grafana`，除非只是保留无业务影响的占位配置。
- 不允许把前端实时链路改成直连 Python。前端实时事件必须来自 Java 推送层。

## 架构硬约束

- 前端技术栈: React + Vite + React Flow。
- Java 技术栈: Spring Boot 3.x，负责接入层和聚合层。
- Python 技术栈: Python 3.11+ + FastAPI + LangGraph。
- 前端到 Java: HTTP 请求 + WebSocket 推送。
- Java 到 Python: HTTP 请求 + SSE 流式事件。
- Phase 1 必须支持的节点只有 `start`、`end`、`llm`、`condition`、`form`。`tool` 只保留扩展点，不阻塞闭环验收。
- 路由能力只需要支撑单流程闭环；优先实现基于 `workflow_version.entry_rule` 的简单规则路由。
- 每个节点执行都必须写入 `execution_node_log`，不能只保留最终结果。

## 默认团队编组

遇到需要实施、重构、联调、或验收的工作时，Claude Code 必须默认组建以下 team 并分工推进:

- `tech-leader`: 负责拆解任务、冻结接口、控制范围、评审质量。
- `backend-java`: 负责 `java-backend/**` 的 Java 接入层和数据持久化。
- `frontend`: 负责 `frontend/**` 的 UI、编排器、实时事件消费。
- `python-dev`: 负责 `python-ai/**` 的执行引擎、路由、节点与流式事件。
- `qa`: 负责测试设计、自动化用例、联调验收。

如果仓库当前还没有完整的 `java-backend/`、`python-ai/`、`frontend/` 目录，先按该目标结构补齐，再继续开发。不要把所有 Phase 1 代码继续堆在根目录的临时骨架里。

## 协作顺序

1. 先让 `tech-leader` 做范围校准、模块拆分、接口冻结和风险识别。
2. `backend-java` 与 `python-dev` 先对齐执行协议、事件模型、数据库模型，再并行开发。
3. `frontend` 基于冻结后的 HTTP 与 WebSocket 合同开发聊天页、编排器、表单交互和执行轨迹展示。
4. `qa` 依据 5 条验收标准先写测试矩阵，再补单测、集成测试和闭环验收脚本。
5. 最后由 `tech-leader` 做集成评审，确认实现没有越过 Phase 1 边界。

## 角色边界

- `tech-leader` 主要产出设计决策、任务拆解、评审结论，不直接吞并所有实现任务。
- `backend-java` 不负责前端交互细节和 Python 节点逻辑。
- `frontend` 不直连 Python，也不在浏览器里重写后端路由/状态机逻辑。
- `python-dev` 不实现前端展示，不绕开 Java 网关直连浏览器。
- `qa` 可以补测试桩和测试配置，但不替代业务开发角色实现主功能。

## 验收门禁

任何实现都必须以以下 5 条为最终验收标准:

- 用户发送消息，系统能识别意图。
- 系统能进入指定工作流并执行节点。
- 遇到 `form` 节点能挂起并恢复。
- 前端能看到实时执行过程。
- `execution` 和 `execution_node_log` 记录完整。

如果某项开发无法直接证明自己支撑以上 5 条之一，默认它不属于当前阶段。
