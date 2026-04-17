# Phase 5 Reference

## 阶段目标

- Phase 5 名称: 生产优化
- 周期: 持续
- 目标: 在已完成的 Phase 1 / Phase 2 / Phase 3 / Phase 4 平台能力之上，补齐性能、稳定性、可维护性和安全深化的生产级控制点

## 基线前提

- Phase 1 的消息输入、工作流执行、`form` 挂起 / 恢复、前端实时展示、`execution` / `execution_node_log` 落库已可用
- Phase 2 的版本管理、RBAC、审计、知识库、幂等、重试、资源隔离和基础可观测性已可用
- Phase 3 的混合路由、完整状态机、显式切换恢复和安全基线已可用
- Phase 4 的动态阈值、推荐、评测、成本、实验、回放和仪表盘已可用
- Frontend -> Java -> Python 调用方向保持不变
- 浏览器仍然只通过 Java 接入，但主业务链路已升级为 Netty + WebSocket

## 阶段完成内容

- 通信: Frontend ↔ Java 主业务链路切换为 Netty + WebSocket
- 前端: React Flow 可编辑画布、草稿保存、发布校验
- AI 配置: Provider / Model Profile 配置中心，意图与知识链路按 Profile 执行
- 性能: 数据库索引优化、Redis 集群、向量库分片
- 稳定性: 优雅降级、熔断器、限流细化
- 可维护性: 日志归档、日志清理自动化
- 安全: 权限细化 (ABAC)、高风险操作二次确认

## 技术与通信约束

- Frontend: React + Vite + React Flow
- Java: Spring Boot 3.x + Netty
- Python: Python 3.11+ + FastAPI + LangGraph
- 数据 / 基础设施: MySQL 8.0+、Redis 7.x、pgvector、OpenTelemetry、Prometheus、Grafana
- Frontend -> Java: Netty + WebSocket
- Java -> Python: HTTP + SSE
- 浏览器实时链路只能来自 Java；不能直连 Python
- HTTP 仅保留后台管理、文件上传和健康检查等非主交互场景

## 新增硬约束

- 主执行链路禁止依赖 Mock / Demo / Stub 数据。
- 可编辑画布不是只读展示；至少要冻结节点/边 schema、属性面板字段、保存草稿和发布校验。
- 意图命中、知识查询改写、知识回答和通用 LLM 节点必须消费真实 Provider / Model Profile 配置。
- 配置缺失时必须明确失败，不得静默回退到写死模型或假结果。

## 性能约束

Phase 5 必须冻结以下性能要素:

- 数据库热点查询和对应索引目标
- Netty + WebSocket 的连接容量、心跳、背压和顺序投递策略
- Redis 集群边界、Key Pattern 和一致性要求
- 向量库分片边界、路由规则和回收策略
- 性能优化必须和现有 execution / audit / replay / metrics 数据模型兼容

## 稳定性约束

至少要冻结以下稳定性要素:

- 降级触发条件与降级结果
- 熔断器状态、触发阈值和恢复策略
- 限流粒度: 用户、会话、工作流、工具或其他明确维度
- 稳定性事件的审计和前端反馈方式
- WebSocket action / ack / event 的错误返回、重试和断线恢复口径

## 日志归档与清理约束

必须明确:

- 热 / 温 / 冷数据分层
- 保留期
- 归档目标存储
- 清理自动化任务
- 恢复或查询入口

归档和清理必须与:

- 审计
- 回放
- 评测
- 脱敏

保持一致，不得破坏可追踪性。

## ABAC 约束

- ABAC 必须建立在现有 RBAC、工具模型和用户属性之上
- 属性来源、表达式语言、求值超时、失败回退都要冻结
- ABAC 拒绝必须有明确口径和审计
- 不把 ABAC 扩成独立权限平台
- 画布编辑、模型配置和知识库配置也必须纳入权限边界

## 高风险确认约束

- 必须明确高风险工具或操作清单
- 必须明确确认对象、确认有效期、确认 ID 和取消行为
- 必须明确前端交互、后端审计和运行时阻断方式
- 不能让高风险确认绕过已有权限、安全和审计体系
- 高风险确认在 Netty + WebSocket 合同里要有明确 action / ack / event 语义

## 安全与隐私基线

- Phase 3 和 Phase 4 的 Prompt 防护、输出校验、敏感脱敏继续生效
- ABAC、确认、归档、清理链路不得泄露原始敏感数据
- 日志、事件、审计、归档和回放中的敏感字段保持统一脱敏口径
- Model Provider 的 API Key / Secret Ref 不得暴露给前端或回放链路

## Phase 5 目标

- 数据路径具备性能优化控制点
- 稳定性保护机制可验证
- 日志归档与清理自动化可验证
- 权限细化 (ABAC) 可执行
- 高风险操作二次确认可执行
- Netty + WebSocket 主业务合同可执行
- 主执行链路无 Mock / Demo / Stub 返回

补充完成项:

- 可编辑画布 schema 与发布校验明确
- 模型 Provider / Model Profile 边界明确
- Redis 集群边界明确
- 向量库分片边界明确
- 熔断器与限流细化可追踪
- 日志分层和清理策略明确

## 非目标

- 与当前仓库完全脱节的基础设施重建
- 无边界权限平台
- 无边界风控平台
- 破坏既有执行 / 审计 / 评测 / 回放合同的“优化”
- 用 mock 合同或写死模型偷换真实实现
