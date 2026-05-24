# 工作流欢迎 Bootstrap 设计

## 背景

当前聊天页支持两种执行方式：用户固定选择某个已发布工作流，或留空交给后端路由模型根据用户输入选择工作流。已有 WebSocket 通道只负责传输 `message_delta` 与执行事件；协调节点提示语中的欢迎语不会在页面打开时自动播报。

目标是在不破坏现有 `chat.send` 执行链路的前提下，新增一次“欢迎 Bootstrap”能力：当用户固定选择某个工作流时，WebSocket 建连后由大模型读取该工作流定义摘要，判断是否需要输出欢迎语；当用户选择“无固定工作流”时，先不欢迎，等用户输入命中流程后再隐式触发对应流程。

## 目标

- 聊天页默认必须让用户明确选择一种模式：固定工作流或无固定工作流。
- 固定工作流模式下，WebSocket URL 携带 `workflow_code` 与 `workflow_version`。
- 固定工作流模式下，后端在 WebSocket 握手成功后自动触发欢迎 Bootstrap。
- 欢迎 Bootstrap 由大模型读取绑定工作流的定义、描述和提示语摘要，返回是否欢迎与欢迎语文本。
- 欢迎语通过现有 `message_delta` 通道推送到前端。
- 无固定工作流模式下，页面打开只建连，不输出流程欢迎语；用户输入后由现有路由模型命中流程并启动。
- 所有新增链路增加日志埋点，便于排查建连、选择、模型判断和推送行为。

## 非目标

- 不把欢迎 Bootstrap 当成一次完整 workflow execution。
- 不执行任何 workflow 节点，不调用工具，不推进流程状态。
- 不把工作流提示词作为系统提示词执行，只把它作为被审阅的数据传给欢迎模型。
- 不删除“无固定工作流”选项。
- 不要求在未选择工作流模式时允许发送消息。

## 前端交互设计

### 选择状态

聊天页工作流选择下拉框保留三类状态：

1. 默认空值 `""`：显示“请选择工作流模式”，代表用户尚未明确选择。
2. 自动路由值 `"__AUTO_ROUTE__"`：显示“无固定工作流”，代表用户显式选择由模型根据输入意图路由。
3. 已发布工作流代码：代表固定执行该工作流。

### 发送限制

- 当选择值为 `""` 时，聊天输入禁用，提示用户先选择工作流模式。
- 当选择值为 `"__AUTO_ROUTE__"` 时，允许发送消息，但 `chat.send` 不带 `workflow_code` / `workflow_version`。
- 当选择值为具体工作流时，允许发送消息，`chat.send` 带 `workflow_code` / `workflow_version`。

### WebSocket URL

- 默认空值：可以保持基础 session WebSocket，但不触发欢迎。
- 无固定工作流：WebSocket 只带 `session_id`。
- 固定工作流：WebSocket 带 `session_id`、`workflow_code`、`workflow_version`。

前端在工作流选择变化时更新 WebSocket 绑定键，使固定工作流切换能够重建连接并触发后端握手逻辑。

## 后端 Java 设计

### 握手参数

`GatewayActionHandler` 在 WebSocket 握手成功后读取 URL 查询参数：

- `session_id`
- `execution_id`
- `workflow_code`
- `workflow_version`

若存在 `workflow_code` 与 `workflow_version`，注册连接后异步触发欢迎 Bootstrap。

### WelcomeBootstrapService

新增服务 `WelcomeBootstrapService`，职责：

1. 校验 `session_id`、`workflow_code`、`workflow_version` 是否完整。
2. 校验目标工作流版本已发布。
3. 读取工作流版本定义、配置、入口规则和描述。
4. 构造安全的 workflow 摘要。
5. 构造模型运行配置并调用 Python 欢迎决策接口。
6. 根据返回结果决定是否发布 `message_delta`。
7. 使用内存级幂等键避免同一 JVM 内同一 `session + workflow + version` 重复欢迎。

### 幂等键

固定工作流握手欢迎的幂等键：

```text
session_id + workflow_code + workflow_version + ws_bootstrap
```

该设计先采用进程内幂等，避免重连或刷新重复欢迎。后续如需跨节点部署，可迁移到数据库或 Redis。

### Java 日志埋点

新增或增强以下日志：

- `gateway.channel.handshake`：记录 session、workflow 参数是否存在。
- `welcome.bootstrap.request`：开始欢迎判断。
- `welcome.bootstrap.skip`：缺少参数、已欢迎、未发布等跳过原因。
- `welcome.bootstrap.model.result`：记录 `should_greet`、消息长度、原因。
- `welcome.bootstrap.publish`：发布欢迎语。
- `welcome.bootstrap.failed`：模型或数据异常。

## Python AI 设计

### 新增接口

新增接口：

```http
POST /api/phase5/workflow-welcome/decide
```

请求体包含：

```json
{
  "session_id": "session-1",
  "workflow_code": "hotel_booking",
  "workflow_version": "1.0.0",
  "workflow_summary": {
    "name": "酒店预订助手",
    "description": "帮助用户查询和预订酒店",
    "entry_rule": {},
    "coordinator_prompts": [],
    "opening_messages": []
  },
  "session_context": {
    "trigger": "ws_bootstrap",
    "has_user_message": false
  },
  "provider_configs": [],
  "model_records": [],
  "routing_model_code": "general-chat-v1"
}
```

响应体：

```json
{
  "should_greet": true,
  "message": "您好，我是酒店预订助手，可以帮您查询城市、日期和房型信息。",
  "reason": "固定工作流首次打开，适合欢迎用户"
}
```

### 模型提示原则

- 系统提示词固定为欢迎决策器角色，只允许 JSON 输出。
- 工作流提示词作为 JSON 数据放入用户提示，不得提升为系统指令。
- 要求模型只基于给定 workflow 摘要判断是否欢迎，不得发明未给出的能力。
- 输出 `message` 为空或 `should_greet=false` 时，Java 不推送任何文本。

### Python 日志埋点

- `welcome.decision.request`：记录 session、workflow、摘要节点数量。
- `welcome.decision.model_result`：记录 `should_greet`、消息长度、原因。
- `welcome.decision.failed`：记录模型输出解析或调用异常。

## 无固定工作流模式

当用户选择 `无固定工作流`：

1. WebSocket 不携带 workflow 参数。
2. 后端握手不触发欢迎 Bootstrap。
3. 用户首条消息通过现有 `chat.send` 进入 `ExecutionService.startExecution`。
4. 因请求不含 `workflow_code/workflow_version`，后端调用 `WorkflowService.routeMessage`。
5. 路由模型命中流程后，现有执行链路隐式启动对应 workflow。

首条消息命中流程后的“流程相关开场语”可作为后续增强；本次实现重点覆盖固定工作流握手欢迎。

## 测试策略

### 前端

- 验证默认空值显示“请选择工作流模式”。
- 验证未选择时输入框禁用或提交被阻止。
- 验证选择“无固定工作流”时，发送请求不带 `workflow_code/workflow_version`。
- 验证选择具体工作流时，WebSocket URL 与 `chat.send` 请求携带 workflow 信息。

### Java

测试文件必须放在 `java-backend/src/test` 下。

- 测试 WebSocket 握手参数能解析 `workflow_code/workflow_version`。
- 测试 `WelcomeBootstrapService` 对缺失参数、重复幂等、模型返回不欢迎、模型返回欢迎的处理。
- 测试欢迎语通过 `WebSocketPublisher.publishMessageDelta` 发送。

### Python

- 测试欢迎决策模型输出合法 JSON 时返回标准结构。
- 测试 `should_greet=false` 和空消息的处理。
- 测试模型输出非法 JSON 时返回安全的不欢迎结果或明确错误。

## 风险与约束

- 如果模型配置缺失，欢迎 Bootstrap 应跳过或降级，不应阻塞 WebSocket 建连。
- 进程内幂等无法覆盖多实例部署，后续需要持久化幂等。
- 工作流提示语可能包含不可信内容，必须作为数据处理，不能作为系统提示词。
- 固定工作流切换建议通过新 session 或重建连接完成，避免同一连接语义不一致。

