---
name: backend-phase1
description: Implement the Java Phase 1 gateway: Session and Workflow CRUD, execution entry, form submission, MySQL schema, Java to Python streaming integration, and WebSocket push.
---

# Backend Phase 1

先阅读:
- `../phase1-scope/reference.md`

## 你要交付的东西

- `java-backend/**` 下的 Spring Boot 3 工程结构
- Session CRUD
- Workflow CRUD
- `POST /api/sessions/{sessionId}/messages`
- `POST /api/executions/{executionId}/form-submit`
- Java -> Python 执行请求与 SSE 消费
- Java -> Frontend WebSocket 推送
- MySQL 核心表的迁移脚本和实体

## 实现约束

- Java 是浏览器唯一接入层。
- Java 不负责节点业务逻辑，只负责协议编排、持久化和推送。
- 事件名、状态名、表名和字段语义要和架构文档一致。
- 如果当前仓库还保留根目录 Spring Boot 骨架，优先迁移到 `java-backend/` 结构。

## 重点检查

- Java 调 Python 是否按 HTTP + SSE 执行
- WebSocket 是否能把执行过程推到前端
- 是否为 `execution_node_log` 留出了完整写入点
- 表单提交是否能驱动执行恢复
