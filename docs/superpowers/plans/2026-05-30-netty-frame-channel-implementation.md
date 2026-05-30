# Netty Frame Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the old WebSocket gateway action model with Netty frame-based user channel interaction using `UserFrame` and `MessageToMessageEncoder<UserFrame>`.

**Architecture:** Java backend owns Netty pipeline, user channel context, user mailbox, frame dispatcher, and extensible handlers. Frontend owns the WebSocket frame client, initialization frame, interactive frame sending, and `frame + event_type` receive routing. Python remains HTTP/SSE behind Java and does not receive frame protocol changes.

**Tech Stack:** Java 21, Spring Boot 3.2, Netty, Jackson, React, TypeScript, Vite, Python FastAPI HTTP/SSE boundary.

---

## Reference Spec

- `docs/superpowers/specs/2026-05-30-netty-frame-channel-design.md`

## Work Split

- Backend worker owns all files under `java-backend/`.
- Frontend worker owns all files under `frontend/`.
- Main session owns `docs/` and final integration verification.

## Backend Tasks

### Task B1: Add frame protocol and Netty codec

**Files:**
- Create: `java-backend/src/main/java/robot/agent/channel/protocol/FrameType.java`
- Create: `java-backend/src/main/java/robot/agent/channel/protocol/UserFrame.java`
- Create: `java-backend/src/main/java/robot/agent/channel/netty/UserFrameDecoder.java`
- Create: `java-backend/src/main/java/robot/agent/channel/netty/UserFrameEncoder.java`
- Test: `java-backend/src/test/java/robot/agent/channel/netty/UserFrameCodecTest.java`

- [ ] Write codec tests for decoding JSON text frames into `UserFrame` and encoding `UserFrame` into `TextWebSocketFrame`.
- [ ] Implement `FrameType.CONNECT(8)` and `FrameType.INTERACTIVE(9)`.
- [ ] Implement `UserFrame` with Jackson snake_case mappings for `request_id`, `user_id`, `session_id`, `execution_id`, and `event_type`.
- [ ] Implement `UserFrameDecoder extends MessageToMessageDecoder<TextWebSocketFrame>`.
- [ ] Implement `UserFrameEncoder extends MessageToMessageEncoder<UserFrame>`.
- [ ] Run `mvn -pl java-backend -Dtest=UserFrameCodecTest test`.

### Task B2: Add user context, connection manager, and mailbox

**Files:**
- Create: `java-backend/src/main/java/robot/agent/channel/core/ChannelAttributes.java`
- Create: `java-backend/src/main/java/robot/agent/channel/core/UserChannelContext.java`
- Create: `java-backend/src/main/java/robot/agent/channel/core/UserConnectionManager.java`
- Create: `java-backend/src/main/java/robot/agent/channel/core/UserMessage.java`
- Create: `java-backend/src/main/java/robot/agent/channel/core/UserMessageMailbox.java`
- Test: `java-backend/src/test/java/robot/agent/channel/core/UserMessageMailboxTest.java`

- [ ] Write mailbox tests proving same-user messages drain in FIFO order.
- [ ] Implement channel attributes for user context, user id, and connection id.
- [ ] Implement one active context per user in `UserConnectionManager`.
- [ ] Implement bounded mailbox with overflow result.
- [ ] Run `mvn -pl java-backend -Dtest=UserMessageMailboxTest test`.

### Task B3: Add dispatcher and extensible handlers

**Files:**
- Create: `java-backend/src/main/java/robot/agent/channel/handler/FrameHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/BusinessEventHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/dispatch/UserEventHandlerRegistry.java`
- Create: `java-backend/src/main/java/robot/agent/channel/dispatch/UserEventDispatcher.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/ConnectHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/InteractiveHandler.java`
- Test: `java-backend/src/test/java/robot/agent/channel/dispatch/UserEventDispatcherTest.java`

- [ ] Write registry tests for duplicate frame handlers and duplicate business event handlers.
- [ ] Write dispatcher tests for frame `8`, frame `9`, and unsupported events.
- [ ] Implement handler registry from Spring-injected handler lists.
- [ ] Implement `ConnectHandler` for `connection.init` and initialization response.
- [ ] Implement `InteractiveHandler` for second-level `event_type` dispatch.
- [ ] Run `mvn -pl java-backend -Dtest=UserEventDispatcherTest test`.

### Task B4: Replace Netty channel initializer and old gateway path

**Files:**
- Create: `java-backend/src/main/java/robot/agent/channel/netty/UserChannelInitializer.java`
- Create: `java-backend/src/main/java/robot/agent/channel/netty/UserFrameInboundHandler.java`
- Modify: `java-backend/src/main/java/robot/agent/service/NettyGatewayServer.java`
- Existing old files may remain temporarily unused if removal causes broad compile fallout.

- [ ] Wire pipeline with HTTP codec, WebSocket protocol, `UserFrameDecoder`, `UserFrameEncoder`, and `UserFrameInboundHandler`.
- [ ] Ensure `UserFrameInboundHandler` enqueues messages by user context and dispatches on business executor group.
- [ ] Update `NettyGatewayServer` constructor to depend on `UserChannelInitializer` instead of old `GatewayActionHandler`.
- [ ] Run `mvn -pl java-backend -DskipTests compile`.

### Task B5: Add core business event handlers and remove publish path from active flow

**Files:**
- Create: `java-backend/src/main/java/robot/agent/channel/handler/TextMessageHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/FormSubmitHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/ResumeExecutionHandler.java`
- Create: `java-backend/src/main/java/robot/agent/channel/handler/HeartbeatHandler.java`
- Modify existing services only as needed under `java-backend/src/main/java/robot/agent/service/`.

- [ ] Implement `message.text` event using existing execution command flow or execution service.
- [ ] Implement `form.submit` event using existing form submission service path.
- [ ] Implement `execution.resume` event using existing resume path.
- [ ] Implement `heartbeat.ping` event returning `heartbeat.pong`.
- [ ] Avoid adding any new publisher abstraction.
- [ ] Run `mvn -pl java-backend -DskipTests compile`.

## Frontend Tasks

### Task F1: Add frame protocol types and helpers

**Files:**
- Modify: `frontend/src/types/index.ts`
- Create: `frontend/src/services/frameProtocol.ts`

- [ ] Define `FrameType`, `UserFrameEnvelope`, and event type string constants.
- [ ] Implement `createInitFrame` and `createInteractiveFrame` helpers.
- [ ] Ensure helpers use snake_case fields matching backend JSON.
- [ ] Run `npm --prefix frontend run build` after dependent tasks are complete.

### Task F2: Replace old gateway action sending

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] Rename or replace `sendGatewayAction` with `sendInteractiveFrame`.
- [ ] Send initialization frame `8` after WebSocket open.
- [ ] Convert `form.submit`, `execution.resume`, and message send paths to frame `9`.
- [ ] Preserve request promise tracking by `request_id`.

### Task F3: Replace WebSocket receive routing

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] Parse inbound messages as `UserFrameEnvelope`.
- [ ] Route `frame=8` to connection initialization handling.
- [ ] Route `frame=9` by `event_type`.
- [ ] Replace old `type=message_delta`, `type=event`, `ack`, and `error` branches.
- [ ] Preserve existing chat UI updates, form UI, resume offer, process steps, and error display.

### Task F4: Update frontend tests or smoke build

**Files:**
- Modify tests under `frontend/tests/` if existing assertions depend on old envelope names.

- [ ] Update E2E expectations only where protocol envelope changed.
- [ ] Run `npm --prefix frontend run build`.

## Python Tasks

No Python code changes are planned in this implementation. Java maps Python HTTP/SSE events to `UserFrame` and sends them to the frontend through Netty.

## Final Verification

- [ ] Run backend focused tests.
- [ ] Run backend compile or test suite.
- [ ] Run frontend build.
- [ ] Inspect `git diff --check`.
- [ ] Confirm no active path still uses `WebSocketPublisher` as the WebSocket return mechanism.

