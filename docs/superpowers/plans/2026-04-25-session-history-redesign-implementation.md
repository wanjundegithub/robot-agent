# Session History Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the execution replay panel with a focused session history experience that lists current plus historical sessions and shows chat messages for the selected session.

**Architecture:** The frontend owns session titles by deriving them from the first user message after messages are loaded, and the backend only broadens session listing to include all statuses. The frontend removes execution replay dependencies from the history panel while keeping existing chat, execution, and WebSocket flows intact.

**Tech Stack:** React 18, TypeScript, Vite, Playwright, Spring Boot Java, Maven, JPA repositories.

---

## File Structure

- Modify `frontend/src/components/SessionReplayPanel.tsx`: convert to a session history component without execution list or replay detail UI.
- Modify `frontend/src/App.tsx`: keep existing prop wiring compatible; remove unnecessary replay-facing props if the component API changes.
- Modify `frontend/tests/e2e/chat-flow.spec.ts`: update mocks and assertions for session history behavior.
- Modify `java-backend/src/main/java/robot/agent/repository/SessionRepository.java`: add or use a query that returns all sessions for a user ordered by recent activity.
- Modify `java-backend/src/main/java/robot/agent/service/SessionService.java`: make `getSessionsByUserId` return all statuses.
- Optionally modify backend tests if existing tests assert only active sessions.

## Task 1: Backend Session Listing

**Files:**
- Modify: `java-backend/src/main/java/robot/agent/repository/SessionRepository.java`
- Modify: `java-backend/src/main/java/robot/agent/service/SessionService.java`

- [ ] **Step 1: Inspect repository methods**

Run: `Get-Content -Raw java-backend\src\main\java\robot\agent\repository\SessionRepository.java`

Expected: identify the existing active-only method, likely `findByUserIdAndStatusOrderByLastActivityAtDesc`.

- [ ] **Step 2: Add all-status repository method**

In `SessionRepository.java`, ensure the repository includes this method:

```java
List<Session> findByUserIdOrderByLastActivityAtDesc(String userId);
```

Keep the active-only method if other code still uses it.

- [ ] **Step 3: Switch service listing to all statuses**

In `SessionService.getSessionsByUserId`, replace the active-only lookup:

```java
List<Session> sessions = sessionRepository.findByUserIdAndStatusOrderByLastActivityAtDesc(userId, SessionStatus.ACTIVE);
```

with:

```java
List<Session> sessions = sessionRepository.findByUserIdOrderByLastActivityAtDesc(userId);
```

Remove the `SessionStatus` import only if it becomes unused.

- [ ] **Step 4: Run backend compile/test**

Run from repo root: `mvn test`

Expected: Maven exits 0. If unrelated tests fail, capture exact failing test names and error messages without changing unrelated code.

## Task 2: Frontend Session History Component

**Files:**
- Modify: `frontend/src/components/SessionReplayPanel.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Remove replay data dependencies**

In `SessionReplayPanel.tsx`, remove imports for:

```ts
getExecutionReplay,
getSessionExecutions,
ExecutionDetail,
ReplayResponse,
displayEventType,
displayExecutionStatus,
displayNodeKind,
displayNodeRuntimeStatus,
displaySessionStatus
```

Keep imports for:

```ts
getSessionMessages,
getSessionsByUserId
Message,
SessionSummary
displayMessageType
```

- [ ] **Step 2: Simplify props**

Use this prop interface:

```ts
interface SessionReplayPanelProps {
  currentUserId: string
  activeSessionId: string
  connectedSessionId: string
  currentMessages: Message[]
}
```

Remove `currentExecutions` from the component signature.

- [ ] **Step 3: Add title helper functions**

Add these helpers near the top of `SessionReplayPanel.tsx`:

```ts
const MAX_SESSION_TITLE_LENGTH = 28

const normalizeTitleText = (value: string) => value.replace(/\s+/g, ' ').trim()

const buildSessionTitle = (messages: Message[]) => {
  const firstUserMessage = messages.find((message) => message.type === 'user')
  const content = normalizeTitleText(firstUserMessage?.content ?? '')
  if (!content) return '新会话'
  return content.length > MAX_SESSION_TITLE_LENGTH
    ? `${content.slice(0, MAX_SESSION_TITLE_LENGTH)}…`
    : content
}
```

- [ ] **Step 4: Replace execution state with title cache**

Use these state values:

```ts
const [sessions, setSessions] = useState<SessionSummary[]>([])
const [selectedSessionId, setSelectedSessionId] = useState('')
const [sessionMessages, setSessionMessages] = useState<Message[]>([])
const [sessionTitles, setSessionTitles] = useState<Record<string, string>>({})
const [isLoadingSessions, setIsLoadingSessions] = useState(false)
const [isLoadingDetail, setIsLoadingDetail] = useState(false)
```

Remove state for `sessionExecutions`, `selectedExecutionId`, and `replay`.

- [ ] **Step 5: Build deduplicated session list**

Create a memoized list that includes the active session exactly once:

```ts
const mergedSessions = useMemo(() => {
  const byId = new Map<string, SessionSummary>()
  const activeSession = sessions.find((session) => session.id === activeSessionId)
  if (activeSession) {
    byId.set(activeSession.id, activeSession)
  } else if (activeSessionId) {
    byId.set(activeSessionId, {
      id: activeSessionId,
      workspaceId: 1,
      userId: currentUserId,
      status: 'active',
      currentExecutionId: connectedSessionId || null,
      createdAt: new Date().toISOString(),
      lastActivityAt: new Date().toISOString(),
    })
  }
  sessions.forEach((session) => byId.set(session.id, session))
  return Array.from(byId.values()).sort((left, right) => {
    const rightTime = new Date(right.lastActivityAt || right.createdAt).getTime()
    const leftTime = new Date(left.lastActivityAt || left.createdAt).getTime()
    return rightTime - leftTime
  })
}, [activeSessionId, connectedSessionId, currentUserId, sessions])
```

- [ ] **Step 6: Load sessions without preferring non-active sessions**

After `getSessionsByUserId(currentUserId)`, set sessions and keep the current selection if still present; otherwise select `activeSessionId` first, then first merged/backend item.

Use this selection logic inside the load effect:

```ts
setSessions(items)
setSelectedSessionId((current) => {
  if (current && (current === activeSessionId || items.some((item) => item.id === current))) {
    return current
  }
  return activeSessionId || items[0]?.id || ''
})
```

- [ ] **Step 7: Load selected session messages only**

Replace detail loading with:

```ts
const isActiveSession = selectedSessionId === activeSessionId
const messages = isActiveSession ? currentMessages : await getSessionMessages(selectedSessionId)
setSessionMessages(messages)
setSessionTitles((current) => ({
  ...current,
  [selectedSessionId]: buildSessionTitle(messages),
}))
```

When `selectedSessionId` is empty, clear only `sessionMessages` and loading state.

- [ ] **Step 8: Keep active title live**

Add an effect:

```ts
useEffect(() => {
  if (!activeSessionId) return
  setSessionTitles((current) => ({
    ...current,
    [activeSessionId]: buildSessionTitle(currentMessages),
  }))
}, [activeSessionId, currentMessages])
```

- [ ] **Step 9: Replace JSX with history-only UI**

Render a panel with `data-testid="session-replay-panel"` for compatibility, title text “会话历史”, session list items with `data-testid={\`session-history-item-${session.id}\`}`, and a message detail area.

Each list button should show:

```tsx
<span className="font-medium text-slate-900">
  {sessionTitles[session.id] ?? '新会话'}
</span>
<span className="text-xs text-slate-500">{formatTime(session.lastActivityAt || session.createdAt)}</span>
```

Do not render session status, session id, execution id, execution list, replay detail, node logs, or event stream.

- [ ] **Step 10: Render selected messages**

In the detail area, render messages with `displayMessageType(message.type)`, message content, and formatted timestamp. For no messages, render “暂无会话消息”。 For loading, render “正在加载会话消息…”。

- [ ] **Step 11: Update App props**

In `frontend/src/App.tsx`, change:

```tsx
<SessionReplayPanel
  currentUserId={currentUserId}
  activeSessionId={currentSession?.id ?? ''}
  connectedSessionId={connectedSessionId}
  currentMessages={messages}
  currentExecutions={executions}
/>
```

to:

```tsx
<SessionReplayPanel
  currentUserId={currentUserId}
  activeSessionId={currentSession?.id ?? ''}
  connectedSessionId={connectedSessionId}
  currentMessages={messages}
/>
```

- [ ] **Step 12: Run frontend build**

Run from `frontend`: `npm run build`

Expected: TypeScript and Vite build exit 0.

## Task 3: E2E Test Update

**Files:**
- Modify: `frontend/tests/e2e/chat-flow.spec.ts`

- [ ] **Step 1: Rename test intent**

Rename the test to:

```ts
test('lists current and historical sessions and opens chat records without replay details', async ({ page }) => {
```

- [ ] **Step 2: Remove execution/replay route expectations**

Keep route handlers for `/api/sessions`, `/api/sessions/{id}`, and `/api/sessions/{id}/messages`.

Remove assertions that require:

```ts
getSessionExecutions
getExecutionReplay
execution-history-item-exec-history-1
执行失败
工具超时
```

If leaving handlers in place is simpler, keep them, but the updated UI must not rely on them.

- [ ] **Step 3: Make mocked session messages readable UTF-8**

Use readable Chinese fixture content such as:

```ts
content: '查询我的订单进度'
content: '订单查询失败，请稍后重试'
content: '咨询会员权益'
content: '会员权益包括积分、优惠券和专属客服'
```

Ensure the file remains UTF-8 without BOM.

- [ ] **Step 4: Assert history-only layout**

After `await page.goto('/')`, assert:

```ts
await expect(page.getByTestId('session-replay-panel')).toBeVisible()
await expect(page.getByText('会话历史')).toBeVisible()
await expect(page.getByTestId('session-history-item-session-current-2')).toBeVisible()
await expect(page.getByTestId('session-history-item-session-history-1')).toBeVisible()
await expect(page.getByTestId('session-history-item-session-history-2')).toBeVisible()
await expect(page.getByText('执行记录')).toHaveCount(0)
await expect(page.getByText('回放详情')).toHaveCount(0)
```

- [ ] **Step 5: Assert one row per session**

Add assertions:

```ts
await expect(page.getByTestId('session-history-item-session-current-2')).toHaveCount(1)
await expect(page.getByTestId('session-history-item-session-history-1')).toHaveCount(1)
await expect(page.getByTestId('session-history-item-session-history-2')).toHaveCount(1)
```

- [ ] **Step 6: Assert click loads chat messages and title updates**

Click a history item and assert message content appears:

```ts
await page.getByTestId('session-history-item-session-history-1').click()
await expect(page.getByText('查询我的订单进度')).toBeVisible()
await expect(page.getByText('订单查询失败，请稍后重试')).toBeVisible()
await expect(page.getByTestId('session-history-item-session-history-1')).toContainText('查询我的订单进度')
```

- [ ] **Step 7: Assert session status and id are hidden**

Use scoped assertions inside the list item:

```ts
await expect(page.getByTestId('session-history-item-session-history-1')).not.toContainText('closed')
await expect(page.getByTestId('session-history-item-session-history-1')).not.toContainText('session-history-1')
```

- [ ] **Step 8: Add Playwright config if missing**

If `frontend/playwright.config.ts` does not exist, create it:

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  use: {
    baseURL: 'http://127.0.0.1:5173',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 120000,
  },
})
```

This makes `npm run test:e2e -- chat-flow.spec.ts` runnable without temporary config.

- [ ] **Step 9: Run targeted E2E**

Run from `frontend`: `npm run test:e2e -- chat-flow.spec.ts`

Expected: one spec passes with zero failures.

## Task 4: Final Verification and Integration

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run text integrity check**

Run from `frontend`: `npm run check:text`

Expected: no suspicious mojibake-like text found.

- [ ] **Step 2: Run frontend build**

Run from `frontend`: `npm run build`

Expected: TypeScript and Vite build exit 0.

- [ ] **Step 3: Run targeted frontend E2E**

Run from `frontend`: `npm run test:e2e -- chat-flow.spec.ts`

Expected: test exits 0.

- [ ] **Step 4: Run backend tests**

Run from repo root: `mvn test`

Expected: Maven exits 0.

- [ ] **Step 5: Inspect git diff for scope**

Run: `git diff -- frontend/src/components/SessionReplayPanel.tsx frontend/src/App.tsx frontend/tests/e2e/chat-flow.spec.ts frontend/playwright.config.ts java-backend/src/main/java/robot/agent/repository/SessionRepository.java java-backend/src/main/java/robot/agent/service/SessionService.java docs/superpowers/specs/2026-04-25-session-history-redesign.md docs/superpowers/plans/2026-04-25-session-history-redesign-implementation.md`

Expected: changes are limited to the session-history redesign and supporting test/config/docs.