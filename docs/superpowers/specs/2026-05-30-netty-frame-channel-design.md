# Netty 帧交互通道重构设计

## 背景

当前后端已经使用 Netty 承载 WebSocket 连接，但业务交互仍集中在旧的 gateway action 模型中：前端发送 `type: "action"` 与 `action` 字段，后端通过单个聚合 handler 解析并调用不同业务逻辑；后端主动推送则通过类似发布器的方式组装 `message_delta`、`event` 等消息再写回前端。

新的设计目标是让 Netty 成为统一通信通道，使用明确的帧协议进行 WebSocket 交互：初始化连接使用 `8` 帧，用户交互使用 `9` 帧。入站通过用户维度的内存队列保证同一用户消息有序处理，出站使用 Netty 原生 outbound handler 封装响应消息，不再引入额外的 publish / gateway / writer 抽象。

## 目标

- 使用 Netty pipeline 作为唯一 WebSocket 通信通道。
- 使用 `MessageToMessageEncoder<UserFrame>` 封装出站消息。
- 定义统一 `UserFrame` 帧协议，初始化帧为 `8`，交互帧为 `9`。
- 每个用户维护一个活跃 `Channel`，用户数据附加到 `Channel.attr(...)`。
- 每个用户维护一个内存消息队列，同一用户消息串行处理，不同用户并行处理。
- 使用事件分发器按帧类型和事件类型分发到 handler。
- 保留 handler 扩展能力，后续新增事件只新增 handler，不修改 Netty pipeline 主流程。
- 舍弃旧 gateway action 设计，统一使用帧交互设计。
- 明确前端协议需要配套重构。
- 明确 Python 端不直接参与浏览器 WebSocket 帧协议，本轮不重构 Python 通信模型。

## 非目标

- 不把 Python 服务改造成 WebSocket 服务。
- 不引入消息中间件或分布式队列。
- 不保留 `WebSocketPublisher`、`publishEvent`、`publishMessageDelta` 这类发布语义作为主要出站路径。
- 不设计 `NettyChannelGateway`、`FrameResponseWriter` 这类额外出站封装层。
- 不在 Netty IO 线程中执行模型调用、数据库长耗时操作或 Python HTTP/SSE 调用。
- 不在本设计中改变工作流执行核心语义。

## 当前问题

### 后端问题

- 旧 gateway action handler 同时承担握手、解析、分发、业务调用、响应组装等职责，边界过重。
- 入站消息没有形成用户维度的有序队列，后续复杂交互容易出现并发状态问题。
- 出站消息存在 publish 语义，和“通过当前用户 Channel 按帧返回前端”的目标不一致。
- 新增事件类型时容易继续扩展聚合分支，不利于 handler 插件化。

### 前端问题

- 前端当前依赖 `type`、`action`、`ack`、`message_delta`、`event` 等旧 envelope。
- 表单提交、恢复执行、普通消息等交互通过不同旧语义混合处理。
- WebSocket 建连后初始化逻辑主要依赖 URL 参数或旧握手行为，没有显式初始化帧。

### Python 边界问题

- Python 当前是 Java 后端的执行引擎，通过 HTTP/SSE 与 Java 交互。
- Python 不直接面对浏览器 WebSocket，因此不应该感知 `frame = 8/9`。
- Java 需要把 Python SSE 事件稳定映射为前端 `UserFrame`。

## 总体架构

```text
Frontend WebSocket
    |
    v
Netty Pipeline
    |
    | inbound
    v
TextWebSocketFrame
 -> UserFrameDecoder
 -> UserFrameInboundHandler
 -> UserMessageMailbox
 -> UserEventDispatcher
 -> ConnectHandler / InteractiveHandler / 扩展 Handler
    |
    | outbound
    v
UserFrame
 -> UserFrameEncoder extends MessageToMessageEncoder<UserFrame>
 -> TextWebSocketFrame
 -> Frontend
```

核心原则：业务 handler 生成 `UserFrame`，直接通过 Netty `ChannelHandlerContext.writeAndFlush(userFrame)` 写出；出站编码由 `UserFrameEncoder` 完成。

## 包结构建议

```text
robot.agent.channel
├── config
│   └── ChannelProperties.java
├── core
│   ├── UserChannelContext.java
│   ├── UserConnectionManager.java
│   ├── UserMessage.java
│   └── UserMessageMailbox.java
├── dispatch
│   ├── UserEventDispatcher.java
│   └── UserEventHandlerRegistry.java
├── handler
│   ├── BusinessEventHandler.java
│   ├── FrameHandler.java
│   ├── ConnectHandler.java
│   ├── InteractiveHandler.java
│   ├── TextMessageHandler.java
│   ├── FormSubmitHandler.java
│   ├── ResumeExecutionHandler.java
│   ├── CancelExecutionHandler.java
│   └── HeartbeatHandler.java
├── netty
│   ├── UserChannelInitializer.java
│   ├── UserFrameDecoder.java
│   ├── UserFrameEncoder.java
│   └── UserFrameInboundHandler.java
├── protocol
│   ├── FrameType.java
│   ├── UserFrame.java
│   └── UserFrameError.java
└── server
    └── NettyWebSocketServer.java
```

说明：包名使用 `channel`，不继续使用旧 `gateway` 命名，避免和旧 gateway action 设计混淆。

## Netty Pipeline 设计

### Pipeline 组成

```java
pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(65536));
pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
pipeline.addLast(new UserFrameDecoder(objectMapper));
pipeline.addLast(new UserFrameEncoder(objectMapper));
pipeline.addLast(businessExecutorGroup, new UserFrameInboundHandler(connectionManager, dispatcher));
```

### 入站职责

- `HttpServerCodec`：HTTP 编解码。
- `HttpObjectAggregator`：聚合 HTTP 请求。
- `WebSocketServerProtocolHandler`：完成 WebSocket 握手。
- `UserFrameDecoder`：将 `TextWebSocketFrame` 解码为 `UserFrame`。
- `UserFrameInboundHandler`：校验上下文，将消息放入用户队列。

### 出站职责

- `UserFrameEncoder`：将 `UserFrame` 编码为 `TextWebSocketFrame`。
- 业务 handler 不直接创建 `TextWebSocketFrame`。
- 所有业务响应都通过 `ctx.writeAndFlush(userFrame)` 触发出站编码。

## 出站编码器设计

`UserFrameEncoder` 使用 Netty 原生 `MessageToMessageEncoder<UserFrame>`。

```java
public final class UserFrameEncoder extends MessageToMessageEncoder<UserFrame> {

    private final ObjectMapper objectMapper;

    public UserFrameEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void encode(ChannelHandlerContext context, UserFrame frame, List<Object> out) throws Exception {
        out.add(new TextWebSocketFrame(objectMapper.writeValueAsString(frame)));
    }
}
```

出站约束：

- handler 返回或写出的对象必须是 `UserFrame`。
- 编码器统一负责 JSON 序列化与 WebSocket 文本帧封装。
- 如果写出的对象不是 `UserFrame`，不应由业务 handler 手动包装，应在设计阶段避免该路径。

## 帧协议设计

### 基础结构

```json
{
  "frame": 9,
  "request_id": "req-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": "exec-001",
  "event_type": "message.text",
  "payload": {},
  "timestamp": "2026-05-30T10:00:00+08:00"
}
```

### 字段说明

- `frame`：协议帧类型，初始化为 `8`，交互为 `9`。
- `request_id`：前端请求 ID，用于响应关联和幂等排查。
- `user_id`：用户 ID。
- `session_id`：会话 ID。
- `execution_id`：执行 ID，可为空。
- `event_type`：业务事件类型。
- `payload`：业务负载。
- `timestamp`：后端生成的响应时间，入站可选。

### 帧类型

```java
public enum FrameType {
    CONNECT(8),
    INTERACTIVE(9);

    private final int code;
}
```

当前只实现：

- `8`：初始化连接帧。
- `9`：交互帧。

后续如需要独立心跳或系统通知，可扩展：

- `10`：心跳帧。
- `11`：系统通知帧。
- `12`：文件元数据帧。

本轮不强制引入这些扩展帧，避免过度设计。

## 初始化帧

### 前端请求

```json
{
  "frame": 8,
  "request_id": "req-init-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": null,
  "event_type": "connection.init",
  "payload": {
    "workflow_code": "demo",
    "workflow_version": "1",
    "client_version": "web-1.0.0"
  }
}
```

### 后端响应

```json
{
  "frame": 8,
  "request_id": "req-init-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": null,
  "event_type": "connection.initialized",
  "payload": {
    "success": true
  },
  "timestamp": "2026-05-30T10:00:00+08:00"
}
```

### 初始化职责

`ConnectHandler` 负责：

- 校验 `user_id` 与 `session_id`。
- 创建或更新 `UserChannelContext`。
- 将用户上下文附加到 `Channel.attr(...)`。
- 在 `UserConnectionManager` 中绑定 `userId -> context`。
- 根据连接策略处理同用户旧连接。
- 写回 `connection.initialized`。
- 可触发欢迎 Bootstrap，但欢迎消息也必须通过 `UserFrame` 写回。

## 交互帧

### 普通消息请求

```json
{
  "frame": 9,
  "request_id": "req-msg-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": null,
  "event_type": "message.text",
  "payload": {
    "content": "用户输入内容",
    "workflow_code": "demo",
    "workflow_version": "1"
  }
}
```

### 普通消息响应

```json
{
  "frame": 9,
  "request_id": "req-msg-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": "exec-001",
  "event_type": "message.delta",
  "payload": {
    "content": "模型输出片段",
    "is_complete": false
  },
  "timestamp": "2026-05-30T10:00:01+08:00"
}
```

### 表单提交请求

```json
{
  "frame": 9,
  "request_id": "req-form-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": "exec-001",
  "event_type": "form.submit",
  "payload": {
    "submit_id": "submit-001",
    "form_data": {
      "field": "value"
    }
  }
}
```

### 恢复执行请求

```json
{
  "frame": 9,
  "request_id": "req-resume-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": "exec-001",
  "event_type": "execution.resume",
  "payload": {}
}
```

## 用户连接上下文

### UserChannelContext

```java
public final class UserChannelContext {
    private final String connectionId;
    private final Channel channel;
    private final UserMessageMailbox mailbox;
    private final Map<String, Object> attributes;
    private volatile String userId;
    private volatile String sessionId;
    private volatile String executionId;
    private volatile String workflowCode;
    private volatile String workflowVersion;
    private volatile OffsetDateTime connectedAt;
    private volatile OffsetDateTime lastActiveAt;
}
```

### Channel Attribute

```java
public final class ChannelAttributes {
    public static final AttributeKey<UserChannelContext> USER_CONTEXT = AttributeKey.valueOf("robot.user.context");
    public static final AttributeKey<String> USER_ID = AttributeKey.valueOf("robot.user.id");
    public static final AttributeKey<String> CONNECTION_ID = AttributeKey.valueOf("robot.connection.id");
}
```

### 连接策略

- 每个用户只维护一个活跃 `Channel`。
- 同一用户重复初始化时，默认新连接替换旧连接。
- 旧连接收到 `connection.replaced` 帧后关闭。
- `channelInactive` 时清理 `UserConnectionManager` 与用户队列。
- 未完成初始化的连接不能处理 `9` 帧。

## 用户消息队列

### 设计目标

- 同一用户消息按接收顺序处理。
- 不同用户消息可以并行处理。
- 避免业务逻辑阻塞 Netty IO 线程。
- 避免单用户无限堆积导致内存风险。

### Mailbox 结构

```java
public final class UserMessageMailbox {
    private final BlockingQueue<UserMessage> queue;
    private final AtomicBoolean draining;
}
```

建议默认容量：`1000`。

### 消费流程

```text
UserFrameInboundHandler 收到 UserFrame
 -> 获取 UserChannelContext
 -> 放入 UserMessageMailbox
 -> 如果 draining=false，提交消费任务到业务线程池
 -> 消费任务循环取消息
 -> 调用 UserEventDispatcher
 -> handler 写回 UserFrame
 -> 队列为空后释放 draining
```

### 队列满响应

```json
{
  "frame": 9,
  "request_id": "req-001",
  "event_type": "error.queue_overflow",
  "payload": {
    "code": "queue_overflow",
    "message": "用户消息队列已满，请稍后重试"
  }
}
```

## 事件分发设计

### FrameHandler

```java
public interface FrameHandler {
    FrameType frameType();

    void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext);
}
```

### BusinessEventHandler

```java
public interface BusinessEventHandler {
    String eventType();

    void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext);

    default int order() {
        return 0;
    }
}
```

### 分发流程

```text
UserEventDispatcher.dispatch(context, frame, nettyContext)
 -> 根据 frame 查找 FrameHandler
 -> CONNECT 帧进入 ConnectHandler
 -> INTERACTIVE 帧进入 InteractiveHandler
 -> InteractiveHandler 根据 event_type 查找 BusinessEventHandler
 -> 找到后执行具体业务 handler
 -> handler 调用 nettyContext.writeAndFlush(UserFrame)
```

### Registry 规则

- 所有 handler 使用 Spring Bean 注册。
- `UserEventHandlerRegistry` 构造时接收 `List<FrameHandler>` 与 `List<BusinessEventHandler>`。
- 同一个 `frameType` 只能有一个 `FrameHandler`。
- 同一个 `eventType` 只能有一个 `BusinessEventHandler`。
- 发现重复注册时启动失败。
- 找不到 handler 时返回 `error.unsupported_event`。

## Handler 设计

### ConnectHandler

处理 `frame = 8`。

职责：

- 初始化用户连接。
- 绑定 `Channel` 和用户上下文。
- 更新 `UserConnectionManager`。
- 返回 `connection.initialized`。
- 在固定工作流场景下触发欢迎 Bootstrap。

### InteractiveHandler

处理 `frame = 9`。

职责：

- 校验连接已初始化。
- 校验 `user_id`、`session_id` 与上下文一致。
- 根据 `event_type` 二次分发到业务 handler。
- 统一处理未识别事件。

### TextMessageHandler

处理 `event_type = message.text`。

职责：

- 构造 Java 执行请求。
- 调用现有执行服务。
- 将执行事件或 Python SSE 输出转换为 `UserFrame`。
- 通过 `nettyContext.writeAndFlush(UserFrame)` 输出 `message.delta`、`execution.started`、`execution.completed` 等事件。

### FormSubmitHandler

处理 `event_type = form.submit`。

职责：

- 校验 `execution_id`。
- 调用表单提交逻辑。
- 返回 `form.submitted` 或后续执行状态帧。

### ResumeExecutionHandler

处理 `event_type = execution.resume`。

职责：

- 校验 `execution_id`。
- 调用恢复执行逻辑。
- 返回 `execution.resumed`、`form.requested` 或执行状态帧。

### CancelExecutionHandler

处理 `event_type = execution.cancel`。

职责：

- 调用取消执行逻辑。
- 返回 `execution.cancelled`。

### HeartbeatHandler

处理 `event_type = heartbeat.ping`。

职责：

- 更新 `lastActiveAt`。
- 返回 `heartbeat.pong`。

## 扩展能力

新增事件时只需要新增 handler：

```java
@Component
public final class WorkflowSwitchHandler implements BusinessEventHandler {

    @Override
    public String eventType() {
        return "workflow.switch";
    }

    @Override
    public void handle(UserChannelContext context, UserFrame frame, ChannelHandlerContext nettyContext) {
        nettyContext.writeAndFlush(responseFrame);
    }
}
```

后续可扩展事件：

- `workflow.switch`
- `file.upload.meta`
- `tool.confirm`
- `intent.accept`
- `intent.reject`
- `slot.answer`
- `execution.pause`
- `execution.retry`
- `conversation.clear`
- `model.change`

## 错误响应

统一错误帧：

```json
{
  "frame": 9,
  "request_id": "req-001",
  "user_id": "u-001",
  "session_id": "s-001",
  "execution_id": null,
  "event_type": "error.unsupported_event",
  "payload": {
    "code": "unsupported_event",
    "message": "不支持的事件类型"
  },
  "timestamp": "2026-05-30T10:00:00+08:00"
}
```

错误类型：

- `error.invalid_json`
- `error.invalid_frame`
- `error.missing_user`
- `error.connection_not_initialized`
- `error.unsupported_frame`
- `error.unsupported_event`
- `error.queue_overflow`
- `error.handler_failed`
- `error.channel_not_writable`

## 前端重构设计

### 是否需要重构

前端必须重构 WebSocket 协议适配层。

原因：当前前端依赖旧协议字段：

- `type = action`
- `action`
- `type = message_delta`
- `type = event`
- `ack`
- `error`

新协议统一使用：

- `frame`
- `request_id`
- `event_type`
- `payload`

### 前端保留部分

- 聊天 UI。
- 消息列表展示。
- 表单弹窗。
- 执行状态展示。
- 会话和工作流选择逻辑。

### 前端必须修改部分

- `sendGatewayAction` 改为 `sendInteractiveFrame`。
- WebSocket open 后显式发送初始化帧 `8`。
- `onmessage` 改为根据 `frame + event_type` 分发。
- `message_delta` 改为 `event_type = message.delta`。
- `event` 改为具体业务 `event_type`。
- `ack` 改为 `event_type = request.ack` 或由具体业务响应替代。
- `GatewayAckEnvelope`、`GatewayErrorEnvelope`、`MessageDeltaEnvelope` 合并为 `UserFrameEnvelope`。

### 前端发送示例

```ts
function sendInteractiveFrame(eventType: string, payload: Record<string, unknown>) {
  socket.send(JSON.stringify({
    frame: 9,
    request_id: createId('req'),
    user_id: currentUserId,
    session_id: sessionId,
    execution_id: executionId,
    event_type: eventType,
    payload,
  }))
}
```

### 前端接收示例

```ts
socket.onmessage = (event) => {
  const frame = JSON.parse(event.data) as UserFrameEnvelope

  if (frame.frame === 8) {
    handleConnectFrame(frame)
    return
  }

  if (frame.frame === 9) {
    handleInteractiveFrame(frame)
    return
  }

  handleUnknownFrame(frame)
}
```

## Python 端重构判断

### 是否需要重构

Python 端本轮不建议重构通信协议。

原因：

- Python 不直接面对浏览器 WebSocket。
- Java 是浏览器和 Python 之间的协议适配层。
- Python 当前通过 HTTP/SSE 向 Java 返回执行事件，Java 可以映射为 `UserFrame`。
- 如果把 Python 同时改成 WebSocket，会扩大范围，并破坏“Netty 作为统一前端通信通道”的边界。

### Python 保持现状

继续保留：

- `/api/execute`
- `/api/executions/{execution_id}/form-submit`
- `/api/executions/{execution_id}/resume`
- `/api/executions/{execution_id}/status`
- SSE 输出模式

### Python 可轻量规范

为了让 Java 更容易映射 `UserFrame`，建议规范 Python SSE 事件语义：

- `message.delta`
- `execution.started`
- `execution.completed`
- `execution.failed`
- `execution.waiting_user`
- `form.requested`
- `tool.calling`
- `tool.completed`

Java 映射关系：

```text
Python SSE event: message.delta
 -> Java TextMessageHandler
 -> UserFrame(frame=9, event_type="message.delta")
 -> UserFrameEncoder
 -> TextWebSocketFrame
 -> Frontend
```

## Java 服务层调整

### 去 publish 化

服务层不再通过 `WebSocketPublisher` 主动发布消息。

推荐方向：

- 执行服务返回事件流，handler 负责写回 `UserFrame`。
- 或执行服务接收一个事件回调，但命名不能体现 publish 语义。
- 更推荐返回事件流，保持服务层不感知 WebSocket。

### 推荐调用关系

```text
TextMessageHandler
 -> ExecutionService
 -> PythonClient.execute
 -> Flux<ExecutionEvent>
 -> TextMessageHandler 映射为 UserFrame
 -> nettyContext.writeAndFlush(UserFrame)
```

## 并发与线程模型

- `bossGroup`：接收连接，线程数建议为 `1`。
- `workerGroup`：处理 WebSocket IO。
- `businessExecutorGroup`：处理用户消息队列和业务 handler。
- 同一用户通过 mailbox 串行处理。
- 不同用户通过业务线程池并行处理。
- 业务 handler 不阻塞 Netty IO 线程。

## 可观测性

关键日志字段：

- `connection_id`
- `user_id`
- `session_id`
- `execution_id`
- `request_id`
- `frame`
- `event_type`
- `queue_size`
- `duration_ms`

关键日志事件：

- `channel.connected`
- `channel.initialized`
- `channel.replaced`
- `frame.inbound`
- `frame.outbound`
- `handler.started`
- `handler.completed`
- `handler.failed`
- `mailbox.overflow`
- `channel.closed`

## 测试策略

### 后端单元测试

- `UserFrameDecoder` 正确解析 `8` / `9` 帧。
- `UserFrameEncoder` 正确输出 `TextWebSocketFrame`。
- `UserEventHandlerRegistry` 重复事件注册时失败。
- `UserEventDispatcher` 正确分发到 `ConnectHandler` / `InteractiveHandler`。
- `UserMessageMailbox` 保证同用户串行消费。
- 队列满时返回 `error.queue_overflow`。

### 后端集成测试

- WebSocket 建连后发送 `8` 帧，收到 `connection.initialized`。
- 未初始化直接发送 `9` 帧，收到 `error.connection_not_initialized`。
- 发送 `message.text`，收到 `message.delta`。
- 发送 `form.submit`，收到表单提交结果或执行状态帧。
- 同一用户重复连接时旧连接被替换。

### 前端测试

- WebSocket open 后自动发送初始化帧。
- `frame = 8` 响应更新连接状态。
- `frame = 9` 且 `event_type = message.delta` 更新流式消息。
- `event_type = form.requested` 打开表单弹窗。
- `event_type = error.*` 显示错误提示。

### Python 边界测试

- Java 能把 Python SSE `message.delta` 映射为 `UserFrame`。
- Java 能把 Python SSE `form.requested` 映射为前端表单事件。
- Python 接口不需要知道 `frame` 字段。

## 迁移顺序

1. 新增 `UserFrame`、`FrameType`、`UserFrameDecoder`、`UserFrameEncoder`。
2. 新增 `UserChannelContext`、`UserConnectionManager`、`UserMessageMailbox`。
3. 新增 `FrameHandler`、`BusinessEventHandler`、`UserEventDispatcher`、`UserEventHandlerRegistry`。
4. 实现 `ConnectHandler` 与 `InteractiveHandler`。
5. 迁移普通消息、表单提交、恢复执行、取消执行 handler。
6. 调整执行服务出站路径，移除 `WebSocketPublisher` 依赖。
7. 前端改为发送 `8` / `9` 帧。
8. 前端改为按 `frame + event_type` 分发消息。
9. Java 将 Python SSE 事件映射为 `UserFrame`。
10. 删除旧 gateway action 协议和旧聚合 handler。

## 评审关注点

- 是否确认同一用户只保留一个活跃 `Channel`，新连接替换旧连接。
- 是否确认 Python 本轮只保持 HTTP/SSE，不改 WebSocket。
- 是否确认前端必须一次性切换到 `frame/event_type/payload` 协议。
- 是否确认 `event_type` 作为业务扩展点，新增事件只新增 handler。
- 是否确认所有出站消息都通过 `MessageToMessageEncoder<UserFrame>` 编码，不再使用 publish 模型。

