# Frontend Chinese Localization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all visible English UI copy in the frontend with Chinese while preserving backend contracts and UTF-8 encoding integrity.

**Architecture:** Keep API payloads, TypeScript types, and backend enum values unchanged. Localize only the presentation layer by editing component copy directly and adding lightweight display mapping functions where backend values must render as Chinese. Fix existing mojibake in affected components as part of the same pass.

**Tech Stack:** React 18, TypeScript, Vite, Playwright, existing `check:text` integrity script

---

### Task 1: Lock the main chat page expectations in E2E

**Files:**
- Modify: `tests/e2e/chat-flow.spec.ts`
- Test: `tests/e2e/chat-flow.spec.ts`

- [ ] **Step 1: Update the visible-text expectations to Chinese**

```ts
await expect(page.getByRole('heading', { name: '机器人代理控制台' })).toBeVisible()
await expect(page.getByRole('button', { name: '聊天' })).toBeVisible()
await expect(page.getByRole('button', { name: '工作流' })).toBeVisible()
await expect(page.getByRole('button', { name: '执行' })).toBeVisible()
await expect(page.getByRole('button', { name: '模型' })).toBeVisible()
await expect(page.getByText('聊天控制台')).toBeVisible()
await expect(page.getByText('选择已发布工作流')).toBeVisible()
await expect(page.getByText('会话回放')).toBeVisible()
```

- [ ] **Step 2: Run the E2E test to verify it fails before implementation**

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: FAIL because the UI still renders English labels.

- [ ] **Step 3: Keep the updated test file as the regression target for the UI localization pass**

```ts
test.describe('chat and session replay', () => {
  test('creates a fresh session on load and keeps history replay available after creating another session', async ({ page }) => {
    // Chinese UI assertions stay in place while session ids and mocked payload values remain unchanged.
  })
})
```

- [ ] **Step 4: Re-run the same test after implementation**

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: PASS

### Task 2: Localize the app shell and chat page

**Files:**
- Modify: `src/App.tsx`
- Modify: `src/components/ChatInput.tsx`
- Modify: `src/components/MessageList.tsx`

- [ ] **Step 1: Replace visible English copy in the app shell and chat prompts**

```tsx
const createWelcomeMessage = (): Message => ({
  id: 'welcome',
  type: 'system',
  content: '欢迎使用。你可以直接开始对话，或选择已发布工作流进行定向测试。',
  timestamp: new Date().toISOString(),
})
```

- [ ] **Step 2: Convert shell labels, prompt cards, workflow picker, and user/session labels to Chinese**

```tsx
<h1 className="text-2xl font-semibold tracking-tight">机器人代理控制台</h1>
<div className="text-sm text-slate-500">多工作流编排与会话调试</div>
```

- [ ] **Step 3: Repair mojibake in chat input and message list, and render Chinese role labels**

```tsx
placeholder="请输入您的问题..."
{isLoading ? '发送中...' : '发送'}
```

- [ ] **Step 4: Keep backend values unchanged and translate only display values**

```tsx
const displayExecutionStatus = (value: string) => {
  switch ((value || '').toLowerCase()) {
    case 'running':
      return '执行中'
    case 'completed':
      return '已完成'
    default:
      return '空闲'
  }
}
```

- [ ] **Step 5: Build the app to verify the main page compiles**

Run: `npm run build`
Expected: PASS

### Task 3: Localize workflow, replay, execution, and governance panels

**Files:**
- Modify: `src/components/ExecutionPanel.tsx`
- Modify: `src/components/ReplayPanel.tsx`
- Modify: `src/components/SessionReplayPanel.tsx`
- Modify: `src/components/WorkflowPanel.tsx`
- Modify: `src/components/AnalyticsPanel.tsx`
- Modify: `src/components/GovernancePanel.tsx`
- Modify: `src/components/InsightsPanel.tsx`
- Modify: `src/components/FormDialog.tsx`

- [ ] **Step 1: Replace panel titles, empty states, loading states, and action labels with Chinese**

```tsx
<div className="panel-title">执行面板</div>
<div className="panel-title">会话回放</div>
<div className="panel-title">工作流版本</div>
```

- [ ] **Step 2: Translate event names, statuses, scopes, lifecycle tiers, and replay labels at render time**

```tsx
const labels: Record<string, string> = {
  'execution.failed': '执行失败',
  'execution.completed': '执行完成',
  'tool.called': '工具调用',
}
```

- [ ] **Step 3: Ensure session, execution, and node metadata stay functional while their labels become Chinese**

```tsx
<div className="text-sm text-slate-500">该会话暂无消息。</div>
<div className="text-sm text-slate-500">请选择一个执行记录查看回放数据。</div>
```

- [ ] **Step 4: Re-run the text-integrity guard to catch any remaining mojibake**

Run: `npm run check:text`
Expected: PASS with no suspicious mojibake-like text found.

### Task 4: Localize workflow designer and model configuration surfaces

**Files:**
- Modify: `src/components/Orchestrator.tsx`
- Modify: `src/components/ModelConfigPanel.tsx`

- [ ] **Step 1: Translate workflow designer labels, placeholders, node templates, and validation messages**

```tsx
const initialNodes: Node<CanvasNodeData>[] = [
  {
    id: 'start',
    data: {
      label: '开始节点',
      nodeType: 'start',
      config: {
        prompt: '接收用户输入并初始化工作流变量。',
      },
    },
  },
]
```

- [ ] **Step 2: Translate model configuration labels while keeping provider/profile identifiers unchanged**

```tsx
<div className="panel-title">模型配置</div>
<input placeholder="服务商名称，例如 豆包生产环境" />
<textarea placeholder="连通性测试请求体 JSON" />
```

- [ ] **Step 3: Keep technical ids and API field names unchanged where they are data, not UI copy**

```tsx
<option key={item.provider_code} value={item.provider_code}>
  {providerLabel(item.provider_name, item.provider_type, item.default_model_code)}
</option>
```

- [ ] **Step 4: Run a full production build after the larger component pass**

Run: `npm run build`
Expected: PASS

### Task 5: Final verification and completion

**Files:**
- Modify: `src/App.tsx`
- Modify: `src/components/*.tsx`
- Modify: `tests/e2e/chat-flow.spec.ts`

- [ ] **Step 1: Re-run all required verification commands fresh**

Run: `npm run check:text`
Expected: PASS

Run: `npm run build`
Expected: PASS

Run: `npm run test:e2e -- chat-flow.spec.ts`
Expected: PASS

- [ ] **Step 2: Manually inspect for leftover visible English strings in source**

Run: `rg -n "Workflow|Session Replay|Chat Console|Loading|Publish|Save Draft|Resume|Confirm|Cancel|New Session|User|Session" src/App.tsx src/components tests/e2e/chat-flow.spec.ts`
Expected: no remaining user-facing English UI copy except intentional technical data.

- [ ] **Step 3: Prepare the final summary with verification evidence**

```text
Localized the visible UI to Chinese, repaired existing mojibake, preserved API contracts, and updated the chat E2E assertions. Verification should cite the fresh outputs of check:text, build, and the targeted Playwright spec.
```
