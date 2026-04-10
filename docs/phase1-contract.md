# 服务机器人 Phase 1 跨服务合同

本文档用于冻结 Phase 1 单流程闭环的最小可实现合同。若与 `服务机器人架构设计.md` 冲突，以架构文档为准。

## 1. 目标

- 跑通单个业务流程的消息输入、路由、节点执行、表单挂起/恢复、结果输出。
- 前端看到实时执行轨迹。
- Java 负责浏览器接入、持久化和事件转发。
- Python 负责流程运行时和 SSE 事件输出。

## 2. 目录边界

- Java: `java-backend/**`
- Frontend: `frontend/**`
- Python: `python-ai/**`
- Test: `tests/**`、`frontend/tests/**`、`python-ai/tests/**`、`java-backend/src/test/**`

## 3. Frontend -> Java

### 3.1 发送消息

`POST /api/sessions/{sessionId}/messages`

```json
{
  "message_id": "msg_001",
  "content": "我要从北京到上海，明天出发",
  "attachments": []
}
```

响应:

```json
{
  "session_id": "sess_001",
  "execution_id": "exec_001",
  "workflow_code": "flight_booking",
  "workflow_version": "1.0.0",
  "status": "running"
}
```

### 3.2 提交表单

`POST /api/executions/{executionId}/form-submit`

```json
{
  "submit_id": "submit_001",
  "form_data": {
    "departure_city": "北京",
    "arrival_city": "上海",
    "departure_date": "2026-03-24",
    "passengers": 1
  }
}
```

响应:

```json
{
  "execution_id": "exec_001",
  "status": "running"
}
```

### 3.3 查询执行详情

`GET /api/executions/{executionId}`

响应至少包含:

```json
{
  "execution_id": "exec_001",
  "session_id": "sess_001",
  "workflow_code": "flight_booking",
  "workflow_version": "1.0.0",
  "status": "running",
  "current_node_id": "extract_slots",
  "variables": {},
  "error": null
}
```

## 4. Java -> Frontend WebSocket

WebSocket 路径建议统一为 `/ws/executions`。

事件包络:

```json
{
  "type": "event",
  "event_type": "node.started",
  "execution_id": "exec_001",
  "data": {
    "node_id": "extract_slots",
    "node_type": "llm"
  }
}
```

消息增量包络:

```json
{
  "type": "message_delta",
  "execution_id": "exec_001",
  "content": "已为您查询到以下航班：",
  "is_complete": false
}
```

Phase 1 必须支持的 `event_type`:

- `execution.started`
- `execution.completed`
- `execution.failed`
- `execution.suspended`
- `node.started`
- `node.completed`
- `node.failed`
- `form.requested`

## 5. Java -> Python

### 5.1 启动执行

`POST /api/execute`

请求头:

- `Content-Type: application/json`
- `Accept: text/event-stream`

请求体:

```json
{
  "session_id": "sess_001",
  "execution_id": "exec_001",
  "workflow_code": "flight_booking",
  "workflow_version": "1.0.0",
  "input_variables": {
    "user_message": "我要从北京到上海，明天出发"
  }
}
```

### 5.2 表单恢复

`POST /api/executions/{executionId}/form-submit`

请求体:

```json
{
  "submit_id": "submit_001",
  "form_data": {
    "departure_city": "北京",
    "arrival_city": "上海",
    "departure_date": "2026-03-24",
    "passengers": 1
  }
}
```

内部路径可以调整，但语义必须保持一致。

## 6. Python -> Java SSE 事件

SSE 事件类型:

- `execution.started`
- `execution.completed`
- `execution.failed`
- `execution.suspended`
- `node.started`
- `node.completed`
- `node.failed`
- `form.requested`
- `message.delta`

示例:

```text
id: 1
event: execution.started
data: {"execution_id":"exec_001","started_at":"2026-03-23T10:00:01Z"}

id: 2
event: node.started
data: {"execution_id":"exec_001","node_id":"extract_slots","node_type":"llm","started_at":"2026-03-23T10:00:02Z"}

id: 3
event: node.completed
data: {"execution_id":"exec_001","node_id":"extract_slots","node_type":"llm","status":"completed","output":{"departure_city":"北京"},"metrics":{"tokens":120,"duration_ms":800}}

id: 4
event: form.requested
data: {"execution_id":"exec_001","node_id":"collect_info","form_definition":{"title":"请补充您的出行信息","fields":[]}}
```

## 7. 数据模型最小约束

最小核心表:

- `workflow_definition`
- `workflow_version`
- `session`
- `execution`
- `execution_node_log`

必须保留的关键字段:

- `workflow_version.entry_rule`
- `session.current_execution_id`
- `session.suspended_stack`
- `execution.status`
- `execution.current_node_id`
- `execution.variables`
- `execution_node_log.node_id`
- `execution_node_log.node_type`
- `execution_node_log.input`
- `execution_node_log.output`
- `execution_node_log.metrics`

状态值目标口径:

- `pending`
- `running`
- `suspended`
- `completed`
- `failed`
- `cancelled`

如果某层当前仍使用大写枚举，只允许作为过渡实现，最终要统一到架构文档中的小写值。

## 8. Phase 1 推荐流程

推荐实现 `flight_booking` 演示流程:

1. `start`
2. `llm` 提取槽位
3. `condition` 检查信息完整性
4. 信息不足进入 `form`
5. 表单提交后恢复执行
6. `end` 输出结果

## 9. 非目标

- `knowledge`
- `subflow`
- Redis 幂等体系
- 复杂多流程切换
- 监控平台
- RBAC
- 版本回滚
