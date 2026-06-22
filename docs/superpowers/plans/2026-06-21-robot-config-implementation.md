# Robot Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add robot configuration as the required chat entry point, with independent workflow and knowledge-space bindings.

**Architecture:** Java backend owns robot configuration, binding persistence, runtime context resolution, and chat-route enforcement. Existing workflow routing and knowledge streaming are reused, but route scope comes from `RobotRuntimeContext` instead of session/workflow knowledge bindings. Frontend adds a robot configuration workspace and sends `robot_code` in chat connect and message payloads.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5, Mockito, React, TypeScript, Vite.

---

## File Structure

- Create `java-backend/src/main/java/robot/agent/model/RobotConfig.java`: JPA entity for robot metadata.
- Create `java-backend/src/main/java/robot/agent/model/RobotStatus.java`: robot lifecycle enum.
- Create `java-backend/src/main/java/robot/agent/model/RobotBinding.java`: JPA entity for workflow and knowledge-space bindings.
- Create `java-backend/src/main/java/robot/agent/model/RobotBindingType.java`: binding enum.
- Create `java-backend/src/main/java/robot/agent/repository/RobotConfigRepository.java`: robot lookup.
- Create `java-backend/src/main/java/robot/agent/repository/RobotBindingRepository.java`: enabled binding lookup.
- Create `java-backend/src/main/java/robot/agent/dto/request/UpsertRobotConfigRequest.java`: create/update robot payload.
- Create `java-backend/src/main/java/robot/agent/dto/request/UpdateRobotBindingsRequest.java`: binding replacement payload.
- Create `java-backend/src/main/java/robot/agent/dto/response/RobotConfigResponse.java`: robot response with binding counts.
- Create `java-backend/src/main/java/robot/agent/dto/response/RobotBindingResponse.java`: binding response.
- Create `java-backend/src/main/java/robot/agent/service/robot/RobotRuntimeContext.java`: immutable runtime scope.
- Create `java-backend/src/main/java/robot/agent/service/robot/RobotConfigService.java`: robot CRUD, publish, binding replacement, runtime context.
- Create `java-backend/src/main/java/robot/agent/controller/RobotConfigController.java`: REST endpoints.
- Modify `java-backend/src/main/java/robot/agent/dto/request/SendMessageRequest.java`: add `robot_code`.
- Modify `java-backend/src/main/java/robot/agent/channel/core/UserChannelContext.java`: store `robotCode`.
- Modify `java-backend/src/main/java/robot/agent/channel/handler/ConnectHandler.java`: echo `robot_code`.
- Modify `java-backend/src/main/java/robot/agent/channel/handler/TextMessageHandler.java`: default request robot from channel context.
- Modify `java-backend/src/main/java/robot/agent/service/ExecutionService.java`: require robot context for normal chat routing.
- Modify `java-backend/src/main/java/robot/agent/service/WorkflowService.java`: route within robot-bound workflows and knowledge spaces.
- Modify `frontend/src/services/api.ts`: add robot configuration APIs and `robot_code` to chat request.
- Modify `frontend/src/services/frameProtocol.ts`: add `robot_code` to connect/message frames.
- Modify `frontend/src/App.tsx`: add robot configuration page state and pass selected robot to chat.
- Create `frontend/src/components/RobotConfigPanel.tsx`: robot configuration UI.
- Modify `frontend/src/types/index.ts`: add robot types.

## Task 1: Backend Robot Domain

**Files:**
- Create: `java-backend/src/main/java/robot/agent/model/RobotConfig.java`
- Create: `java-backend/src/main/java/robot/agent/model/RobotStatus.java`
- Create: `java-backend/src/main/java/robot/agent/model/RobotBinding.java`
- Create: `java-backend/src/main/java/robot/agent/model/RobotBindingType.java`
- Create: `java-backend/src/main/java/robot/agent/repository/RobotConfigRepository.java`
- Create: `java-backend/src/main/java/robot/agent/repository/RobotBindingRepository.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpsertRobotConfigRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/request/UpdateRobotBindingsRequest.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/RobotConfigResponse.java`
- Create: `java-backend/src/main/java/robot/agent/dto/response/RobotBindingResponse.java`
- Create: `java-backend/src/main/java/robot/agent/service/robot/RobotRuntimeContext.java`
- Create: `java-backend/src/main/java/robot/agent/service/robot/RobotConfigService.java`
- Create: `java-backend/src/main/java/robot/agent/controller/RobotConfigController.java`
- Test: `java-backend/src/test/java/robot/agent/service/robot/RobotConfigServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Create tests that verify:

```java
@Test
void runtimeContextRequiresPublishedRobot() {
    RobotConfig disabled = robot("robot_after_sale", RobotStatus.DISABLED);
    when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(disabled));

    assertThatThrownBy(() -> service.resolveRuntimeContext("robot_after_sale"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Robot is not published");
}

@Test
void runtimeContextReturnsIndependentWorkflowAndKnowledgeBindings() {
    RobotConfig published = robot("robot_after_sale", RobotStatus.PUBLISHED);
    when(configRepository.findByRobotCode("robot_after_sale")).thenReturn(Optional.of(published));
    when(bindingRepository.findByRobotCodeAndEnabledTrueOrderByCreatedAtAsc("robot_after_sale"))
            .thenReturn(List.of(
                    binding(RobotBindingType.WORKFLOW, "after_sale_ticket", 3),
                    binding(RobotBindingType.KNOWLEDGE_SPACE, "kb_warranty_policy", 3)
            ));

    RobotRuntimeContext context = service.resolveRuntimeContext("robot_after_sale");

    assertThat(context.workflowCodes()).containsExactly("after_sale_ticket");
    assertThat(context.kbCodes()).containsExactly("kb_warranty_policy");
    assertThat(context.bindingVersion()).isEqualTo(3);
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `cd java-backend; mvn -Dtest=RobotConfigServiceTest test`

Expected: compilation fails because robot domain classes do not exist.

- [ ] **Step 3: Implement robot domain and service**

Add the entities, repositories, DTOs, service, and controller listed in this task. Keep implementation minimal: CRUD list/get/upsert, publish, get bindings, replace bindings, and `resolveRuntimeContext`.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `cd java-backend; mvn -Dtest=RobotConfigServiceTest test`

Expected: tests pass.

## Task 2: Chat Request Requires Robot Code

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/dto/request/SendMessageRequest.java`
- Modify: `java-backend/src/main/java/robot/agent/channel/core/UserChannelContext.java`
- Modify: `java-backend/src/main/java/robot/agent/channel/handler/ConnectHandler.java`
- Modify: `java-backend/src/main/java/robot/agent/channel/handler/TextMessageHandler.java`
- Modify: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`
- Test: `java-backend/src/test/java/robot/agent/service/ExecutionServiceFallbackTest.java`
- Test: `java-backend/src/test/java/robot/agent/channel/handler/TextMessageHandlerTest.java`

- [ ] **Step 1: Write failing tests**

Add tests that verify:

```java
@Test
void startExecutionRequiresRobotCodeForUnforcedChat() {
    SendMessageRequest request = new SendMessageRequest();
    request.setSessionId("session-1");
    request.setUserId("request-user");
    request.setContent("保修期多久");

    SendMessageResponse response = executionService.startExecution("session-1", request);

    assertThat(response.getStatus()).isEqualTo("robot_required");
    verify(workflowService, never()).routeMessage(any(), any(), any(), any());
}
```

and `TextMessageHandler` copies `robot_code` from channel context when the message payload does not include it.

- [ ] **Step 2: Run tests to verify RED**

Run: `cd java-backend; mvn -Dtest=ExecutionServiceFallbackTest,TextMessageHandlerTest test`

Expected: tests fail because `robot_code` is not modeled or enforced.

- [ ] **Step 3: Implement request and channel changes**

Add `robotCode` to `SendMessageRequest`, `UserChannelContext`, connect response payload, and `TextMessageHandler`.

- [ ] **Step 4: Implement execution gate**

Inject `RobotConfigService` into `ExecutionService`. For normal chat routing, return `robot_required` when `robotCode` is blank. For valid `robotCode`, load `RobotRuntimeContext` before calling workflow routing.

- [ ] **Step 5: Run tests to verify GREEN**

Run: `cd java-backend; mvn -Dtest=ExecutionServiceFallbackTest,TextMessageHandlerTest test`

Expected: tests pass.

## Task 3: Robot-Scoped Workflow and Knowledge Routing

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/service/WorkflowService.java`
- Test: `java-backend/src/test/java/robot/agent/service/WorkflowKnowledgeRouteServiceTest.java`

- [ ] **Step 1: Write failing routing tests**

Add tests that verify:

```java
@Test
void routeMessageSearchesOnlyRobotKnowledgeSpaces() {
    RobotRuntimeContext context = new RobotRuntimeContext(
            "robot_after_sale", 1L, 4,
            List.of("flight_booking"),
            List.of("kb_robot_only"),
            "PARALLEL_AGGREGATE"
    );

    RoutingDecision decision = workflowService.routeMessage(
            "保修期多久", null, "session_1", "demo-user", context
    );

    assertThat(decision.decision()).isEqualTo("knowledge_answer");
    verify(knowledgeService).searchKnowledge(eq("demo-user"), requestCaptor.capture());
    assertThat(requestCaptor.getValue().getKbCodes()).containsExactly("kb_robot_only");
}
```

Also add a test that unbound published workflows are not available as intent candidates.

- [ ] **Step 2: Run tests to verify RED**

Run: `cd java-backend; mvn -Dtest=WorkflowKnowledgeRouteServiceTest test`

Expected: compilation fails because `routeMessage` has no `RobotRuntimeContext` overload.

- [ ] **Step 3: Implement scoped route overload**

Add `routeMessage(String content, Execution activeExecution, String sessionId, String userId, RobotRuntimeContext robotContext)`. Filter workflow versions to `robotContext.workflowCodes()`. Replace `boundKnowledgeBaseCodes(...)` in robot-scoped routing with `robotContext.kbCodes()`.

- [ ] **Step 4: Run tests to verify GREEN**

Run: `cd java-backend; mvn -Dtest=WorkflowKnowledgeRouteServiceTest test`

Expected: tests pass.

## Task 4: Frontend Robot Configuration Page

**Files:**
- Create: `frontend/src/components/RobotConfigPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/tests/e2e/robot-config.spec.ts`

- [ ] **Step 1: Write failing e2e test**

Create a Playwright test that opens the app and verifies a robot configuration navigation item or panel can render, including text `机器人配置`, `工作流绑定`, and `知识空间绑定`.

- [ ] **Step 2: Run test to verify RED**

Run: `cd frontend; npm run test:e2e -- robot-config.spec.ts`

Expected: test fails because the page does not exist.

- [ ] **Step 3: Implement API and UI**

Add robot types, API helpers for `/api/robots`, and `RobotConfigPanel`. Add navigation in `App.tsx`. Keep the page close to the approved prototype: list, basic form, workflow binding list, knowledge-space binding list, route strategy, and preview panel.

- [ ] **Step 4: Run test to verify GREEN**

Run: `cd frontend; npm run test:e2e -- robot-config.spec.ts`

Expected: test passes.

## Task 5: Frontend Chat Sends Robot Code

**Files:**
- Modify: `frontend/src/services/frameProtocol.ts`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/tests/e2e/chat-flow.spec.ts`

- [ ] **Step 1: Write failing e2e or unit-level assertion**

Add coverage that the chat connect frame and message frame include `robot_code` when a robot is selected.

- [ ] **Step 2: Run test to verify RED**

Run: `cd frontend; npm run test:e2e -- chat-flow.spec.ts`

Expected: test fails because frames do not include `robot_code`.

- [ ] **Step 3: Implement selected robot in chat**

Load published robots, keep selected robot state, require a selected robot before chat send, and pass `robot_code` into connect and message payloads.

- [ ] **Step 4: Run test to verify GREEN**

Run: `cd frontend; npm run test:e2e -- chat-flow.spec.ts`

Expected: test passes.

## Task 6: Verification

**Files:**
- Modify only if tests expose failures in touched code.

- [ ] **Step 1: Run backend focused tests**

Run: `cd java-backend; mvn -Dtest=RobotConfigServiceTest,WorkflowKnowledgeRouteServiceTest,ExecutionServiceFallbackTest test`

Expected: pass.

- [ ] **Step 2: Run frontend typecheck/build**

Run: `cd frontend; npm run build`

Expected: pass.

- [ ] **Step 3: Inspect status**

Run: `git status --short`

Expected: only robot-config implementation files and intentional prototype/design artifacts are changed.

## Self-Review

- Spec coverage: robot config model, independent bindings, required `robot_code`, scoped intent, scoped knowledge retrieval, frontend configuration page, and stream reuse are covered.
- Placeholder scan: this plan contains no `TBD`, `TODO`, or unspecified implementation steps.
- Type consistency: `RobotRuntimeContext`, `robot_code`, `WORKFLOW`, and `KNOWLEDGE_SPACE` names match across tasks.

