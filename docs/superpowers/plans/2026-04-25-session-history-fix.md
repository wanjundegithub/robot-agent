# Session History Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix session history retention, soft deletion, and empty-session filtering so the chat UI keeps real history, hides empty sessions, and supports deleting sessions from the list.

**Architecture:** The backend becomes the source of truth for history semantics by filtering out `DELETED` and empty sessions. The frontend consolidates session state in `App.tsx`, converts `SessionReplayPanel` into a presentation-driven component, and wires create/switch/delete flows against that backend contract.

**Tech Stack:** Spring Boot 3 / JPA / JUnit 5 / React 18 / TypeScript / Vite / Playwright

---

### Task 1: Lock Backend History Semantics With Tests

**Files:**
- Modify: `java-backend/src/test/java/robot/agent/service/SessionServiceTest.java`
- Create: `java-backend/src/test/java/robot/agent/service/SessionServiceDeleteTest.java`
- Create: `java-backend/src/test/java/robot/agent/controller/SessionControllerTest.java`
- Check: `java-backend/src/main/java/robot/agent/service/SessionService.java`

- [ ] **Step 1: Write failing service tests for filtered history**

```java
@Test
void getSessionsByUserIdExcludesDeletedAndEmptySessions() {
    SessionRepository sessionRepository = mock(SessionRepository.class);
    ExecutionRepository executionRepository = mock(ExecutionRepository.class);

    Session deletedSession = session("deleted-session", "user-1", SessionStatus.DELETED, LocalDateTime.parse("2026-04-25T12:00:00"));
    Session emptySession = session("empty-session", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T11:00:00"));
    Session historicalSession = session("history-session", "user-1", SessionStatus.CLOSED, LocalDateTime.parse("2026-04-25T10:00:00"));

    when(sessionRepository.findByUserIdAndStatusNotOrderByLastActivityAtDesc("user-1", SessionStatus.DELETED))
            .thenReturn(List.of(deletedSession, emptySession, historicalSession));
    when(executionRepository.findBySessionIdOrderByCreatedAtAsc("empty-session")).thenReturn(List.of());
    when(executionRepository.findBySessionIdOrderByCreatedAtAsc("history-session"))
            .thenReturn(List.of(executionWithUserMessage("history-session", "咨询会员权益")));

    SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper());

    List<SessionResponse> sessions = sessionService.getSessionsByUserId("user-1");

    assertThat(sessions).extracting(SessionResponse::getId).containsExactly("history-session");
}
```

- [ ] **Step 2: Run backend tests to verify RED**

Run: `mvn -pl java-backend -Dtest=SessionServiceTest,SessionServiceDeleteTest,SessionControllerTest test`
Expected: FAIL because `SessionStatus.DELETED`, repository filter method, and delete semantics do not exist yet

- [ ] **Step 3: Write failing delete tests**

```java
@Test
void deleteSessionMarksSessionDeleted() {
    SessionRepository sessionRepository = mock(SessionRepository.class);
    ExecutionRepository executionRepository = mock(ExecutionRepository.class);
    Session session = session("session-1", "user-1", SessionStatus.ACTIVE, LocalDateTime.parse("2026-04-25T09:00:00"));

    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

    SessionService sessionService = new SessionService(sessionRepository, executionRepository, new ObjectMapper());
    sessionService.deleteSession("session-1");

    assertThat(session.getStatus()).isEqualTo(SessionStatus.DELETED);
    assertThat(session.getLastActivityAt()).isNotNull();
}
```

- [ ] **Step 4: Run backend tests to verify RED again**

Run: `mvn -pl java-backend -Dtest=SessionServiceTest,SessionServiceDeleteTest,SessionControllerTest test`
Expected: FAIL with missing delete method or wrong `CLOSED` behavior

- [ ] **Step 5: Commit the test-only red state**

```bash
git add java-backend/src/test/java/robot/agent/service/SessionServiceTest.java java-backend/src/test/java/robot/agent/service/SessionServiceDeleteTest.java java-backend/src/test/java/robot/agent/controller/SessionControllerTest.java
git commit -m "test: cover session history filtering semantics"
```

### Task 2: Implement Backend Filtering And Soft Delete

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/model/SessionStatus.java`
- Modify: `java-backend/src/main/java/robot/agent/repository/SessionRepository.java`
- Modify: `java-backend/src/main/java/robot/agent/service/SessionService.java`
- Modify: `java-backend/src/main/java/robot/agent/controller/SessionController.java`
- Check: `java-backend/src/main/java/robot/agent/service/ExecutionService.java`

- [ ] **Step 1: Add minimal backend code to satisfy tests**

```java
public enum SessionStatus {
    ACTIVE,
    CLOSED,
    EXPIRED,
    DELETED
}
```

```java
public interface SessionRepository extends JpaRepository<Session, String> {
    List<Session> findByUserIdAndStatusNotOrderByLastActivityAtDesc(String userId, SessionStatus status);
}
```

```java
public List<SessionResponse> getSessionsByUserId(String userId) {
    List<Session> sessions = sessionRepository.findByUserIdAndStatusNotOrderByLastActivityAtDesc(userId, SessionStatus.DELETED);
    return sessions.stream()
            .filter(this::hasUserInteraction)
            .map(SessionResponse::fromEntity)
            .toList();
}

public void deleteSession(String sessionId) {
    Session session = getSessionEntity(sessionId);
    session.setStatus(SessionStatus.DELETED);
    session.setLastActivityAt(LocalDateTime.now());
    sessionRepository.save(session);
}
```

- [ ] **Step 2: Add the minimal interaction predicate**

```java
private boolean hasUserInteraction(Session session) {
    return executionRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
            .anyMatch(execution -> {
                Map<String, Object> input = parseJson(execution.getInputVariables());
                String message = readPreferredText(input, "user_message", "message", "content", "question");
                return message != null && !message.isBlank();
            });
}
```

- [ ] **Step 3: Update controller delete endpoint to call the new service method**

```java
@DeleteMapping("/{id}")
public ResponseEntity<Void> deleteSession(@PathVariable String id) {
    sessionService.deleteSession(id);
    return ResponseEntity.ok().build();
}
```

- [ ] **Step 4: Run targeted backend tests to verify GREEN**

Run: `mvn -pl java-backend -Dtest=SessionServiceTest,SessionServiceDeleteTest,SessionControllerTest test`
Expected: PASS

- [ ] **Step 5: Refactor small naming or duplication issues and keep tests green**

Run: `mvn -pl java-backend -Dtest=SessionServiceTest,SessionServiceDeleteTest,SessionControllerTest test`
Expected: PASS

- [ ] **Step 6: Commit backend implementation**

```bash
git add java-backend/src/main/java/robot/agent/model/SessionStatus.java java-backend/src/main/java/robot/agent/repository/SessionRepository.java java-backend/src/main/java/robot/agent/service/SessionService.java java-backend/src/main/java/robot/agent/controller/SessionController.java java-backend/src/test/java/robot/agent/service/SessionServiceTest.java java-backend/src/test/java/robot/agent/service/SessionServiceDeleteTest.java java-backend/src/test/java/robot/agent/controller/SessionControllerTest.java
git commit -m "feat: filter empty sessions and soft delete history"
```

### Task 3: Lock Frontend History And Delete UX With E2E Tests

**Files:**
- Modify: `frontend/tests/e2e/chat-flow.spec.ts`
- Check: `frontend/src/App.tsx`
- Check: `frontend/src/components/SessionReplayPanel.tsx`

- [ ] **Step 1: Write failing E2E expectations for empty-session filtering and delete**

```typescript
test('keeps chatted sessions in history, hides empty sessions, and removes deleted sessions', async ({ page }) => {
  await page.goto('/')

  const panel = page.getByTestId('session-replay-panel')
  await expect(panel.getByTestId('session-history-item-session-current-1')).toHaveCount(0)

  await page.getByPlaceholder('输入消息').fill('咨询会员权益')
  await page.getByRole('button', { name: '发送' }).click()

  await page.getByTestId('chat-new-session').click()

  await expect(panel.getByTestId('session-history-item-session-current-1')).toHaveCount(1)

  await panel.getByTestId('session-history-delete-session-history-1').click()
  await expect(panel.getByTestId('session-history-item-session-history-1')).toHaveCount(0)
})
```

- [ ] **Step 2: Extend route mocks for `DELETE /api/sessions/:id` and updated list semantics**

```typescript
const deletedSessionIds = new Set<string>()

if (pathname.match(/^\/api\/sessions\/([^/]+)$/) && request.method() === 'DELETE') {
  deletedSessionIds.add(sessionId)
  await route.fulfill({ status: 200, body: '' })
  return
}
```

- [ ] **Step 3: Run E2E spec to verify RED**

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: FAIL because the current UI still loads history inside `SessionReplayPanel`, shows stale sessions, and has no delete action

- [ ] **Step 4: Commit frontend red tests**

```bash
git add frontend/tests/e2e/chat-flow.spec.ts
git commit -m "test: cover session history retention and delete flow"
```

### Task 4: Implement Frontend Session-State Consolidation

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/SessionReplayPanel.tsx`
- Modify: `frontend/src/services/api.ts`
- Modify: `frontend/src/types/index.ts`

- [ ] **Step 1: Add API helper for deletion**

```typescript
export async function deleteSession(sessionId: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  })
  if (!response.ok) {
    await parseApiError(response)
  }
}
```

- [ ] **Step 2: Move history ownership into `App.tsx`**

```typescript
const [historicalSessions, setHistoricalSessions] = useState<SessionSummary[]>([])

const refreshHistoricalSessions = useCallback(async (userId: string) => {
  const items = await getSessionsByUserId(userId)
  setHistoricalSessions(items)
}, [])
```

```typescript
const handleCreateNewSession = useCallback(async () => {
  disconnectSocket()
  resetSessionView()
  setCurrentSession(null)
  setSessionId('')
  navigateToPage('chat')
  const created = await createAndSelectSession(currentUserId)
  await refreshHistoricalSessions(currentUserId)
  return created
}, [createAndSelectSession, currentUserId, disconnectSocket, refreshHistoricalSessions, resetSessionView])
```

- [ ] **Step 3: Turn `SessionReplayPanel` into a controlled component**

```typescript
interface SessionReplayPanelProps {
  sessions: SessionSummary[]
  activeSessionId: string
  currentMessages: Message[]
  onSelectSession: (sessionId: string) => void
  onDeleteSession: (sessionId: string) => Promise<void>
}
```

```tsx
<SessionReplayPanel
  sessions={historicalSessions}
  activeSessionId={sessionId}
  connectedSessionId={socketState === 'connected' ? sessionId : ''}
  currentMessages={messages}
  onSelectSession={handleSessionChange}
  onDeleteSession={handleDeleteSession}
/>
```

- [ ] **Step 4: Implement delete behavior with current-session fallback**

```typescript
const handleDeleteSession = useCallback(async (targetSessionId: string) => {
  await deleteSession(targetSessionId)
  setHistoricalSessions((prev) => prev.filter((session) => session.id !== targetSessionId))

  if (targetSessionId !== sessionId) {
    return
  }

  disconnectSocket()
  resetSessionView()
  setCurrentSession(null)
  setSessionId('')
  const created = await createAndSelectSession(currentUserId)
  await refreshHistoricalSessions(currentUserId)
  setCurrentSession(created)
}, [createAndSelectSession, currentUserId, disconnectSocket, refreshHistoricalSessions, resetSessionView, sessionId])
```

- [ ] **Step 5: Run frontend E2E to verify GREEN**

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: PASS

- [ ] **Step 6: Run frontend build as regression coverage**

Run: `npm run build`
Expected: PASS

- [ ] **Step 7: Commit frontend implementation**

```bash
git add frontend/src/App.tsx frontend/src/components/SessionReplayPanel.tsx frontend/src/services/api.ts frontend/src/types/index.ts frontend/tests/e2e/chat-flow.spec.ts
git commit -m "feat: preserve session history and support soft delete"
```

### Task 5: Full Verification And Integration Pass

**Files:**
- Check: `java-backend/src/main/java/robot/agent/service/SessionService.java`
- Check: `frontend/src/App.tsx`
- Check: `frontend/src/components/SessionReplayPanel.tsx`
- Check: `frontend/tests/e2e/chat-flow.spec.ts`

- [ ] **Step 1: Run backend verification suite**

Run: `mvn -pl java-backend test`
Expected: PASS

- [ ] **Step 2: Run frontend verification suite**

Run: `npm run build`
Expected: PASS

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: PASS

- [ ] **Step 3: Run end-to-end local stack smoke test**

Run: `./start-all.ps1`
Expected: frontend, backend, and python services start without session-history regressions

- [ ] **Step 4: Perform manual flow verification**

```text
1. Open chat page
2. Confirm initial empty current session does not appear in history
3. Send one user message
4. Create a new session
5. Confirm previous session appears in history
6. Delete a historical session
7. Delete the current session and confirm a fresh empty session is created
```

- [ ] **Step 5: Commit final integration fixes if needed**

```bash
git add .
git commit -m "test: verify session history integration flow"
```
