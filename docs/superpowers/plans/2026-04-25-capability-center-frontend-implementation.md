# Capability Center Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a capability center UI for business-domain capability groups, capability items, auth configuration, validation/testing, and publishing, then integrate workflow “能力调用” node selection so users can pick a published group snapshot and a published API / Skill / MCP capability.

**Architecture:** Reuse existing app shell and panel styling, follow the `ModelConfigPanel` management pattern for CRUD + test interactions, and extend the existing `tool` node editor in `Orchestrator` instead of introducing a parallel workflow node system. Keep capability center state client-side with API-driven persistence and minimal local transformation.

**Tech Stack:** React 18, TypeScript, Vite, existing app CSS/Tailwind utility classes, Playwright.

---

## File Structure

- Create `frontend/src/components/CapabilityCenterPanel.tsx`: top-level capability center page.
- Create `frontend/src/components/capability/CapabilityGroupList.tsx`: group list and actions.
- Create `frontend/src/components/capability/CapabilityGroupDetail.tsx`: group detail tabs.
- Create `frontend/src/components/capability/CapabilityItemTable.tsx`: unified item list.
- Create `frontend/src/components/capability/CapabilityEditor.tsx`: API / Skill / MCP editor.
- Create `frontend/src/components/capability/AuthConfigEditor.tsx`: auth configuration editor.
- Create `frontend/src/components/capability/CapabilityTestPanel.tsx`: validation and test UI.
- Create `frontend/src/components/capability/GroupSnapshotPanel.tsx`: snapshot list and publish UI.
- Modify `frontend/src/App.tsx`: add capability center entry and mount panel.
- Modify `frontend/src/services/api.ts`: add capability center API methods.
- Modify `frontend/src/types/index.ts`: add group, capability, snapshot, auth, and test types.
- Modify `frontend/src/components/Orchestrator.tsx`: replace raw API / skill / mcp fields with capability selection path in the node editor and serializer.
- Create `frontend/tests/e2e/capability-center.spec.ts`: end-to-end flow for capability center management.

## Task 1: Add Frontend Types and API Client

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/services/api.ts`

- [ ] **Step 1: Add types for capability center**

Append interfaces like:

```ts
export type CapabilityType = 'API' | 'SKILL' | 'MCP'

export interface CapabilityGroupSummary {
  id: number
  groupCode: string
  groupName: string
  domainCode: string
  description?: string
  status: string
  latestSnapshotVersion?: string | null
  capabilityCount?: number
}
```

and:

```ts
export interface CapabilityVersionSummary {
  id: number
  capabilityCode: string
  capabilityName: string
  capabilityType: CapabilityType
  version: string
  status: string
  inputSchema?: string
  outputSchema?: string
  publishedAt?: string | null
}
```

Also add types for `CapabilityGroupSnapshot`, `AuthConfigSummary`, and `CapabilityTestResult`.

- [ ] **Step 2: Add API methods**

Add methods like:

```ts
export async function getCapabilityGroups(): Promise<CapabilityGroupSummary[]> {
  const response = await fetch(`${API_BASE_URL}/capabilities/groups`)
  if (!response.ok) {
    await parseApiError(response)
  }
  return await response.json()
}
```

and:

```ts
export async function saveCapabilityDraft(
  groupCode: string,
  capabilityCode: string,
  payload: Record<string, unknown>,
  currentUserId: string
): Promise<CapabilityVersionSummary> { ... }
```

Add APIs for:

- groups list/create/update/delete
- capability list by group
- capability draft save
- capability version list
- capability publish
- auth save
- group snapshot publish/list
- validate capability
- test capability

- [ ] **Step 3: Run frontend build**

Run from `frontend`: `npm run build`

Expected: type definitions and service client compile cleanly.

## Task 2: Build Capability Center Main Panel

**Files:**
- Create: `frontend/src/components/CapabilityCenterPanel.tsx`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Create panel shell**

Create a shell component:

```tsx
export default function CapabilityCenterPanel({ currentUserId }: { currentUserId: string }) {
  const [groups, setGroups] = useState<CapabilityGroupSummary[]>([])
  const [selectedGroupCode, setSelectedGroupCode] = useState('')

  return (
    <div className="panel-card h-full">
      <div className="grid h-full gap-4 lg:grid-cols-[320px_minmax(0,1fr)]">
        <CapabilityGroupList ... />
        <CapabilityGroupDetail ... />
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Add navigation entry in `App.tsx`**

Follow the existing panel-switching pattern and add a capability center tab/button. The mount should look like:

```tsx
{activeWorkspaceTab === 'capability-center' && (
  <CapabilityCenterPanel currentUserId={currentUserId} />
)}
```

If the app uses explicit buttons instead of tab enums, extend the same enum/state source instead of introducing a second navigation state.

- [ ] **Step 3: Load and select groups**

Add an effect:

```ts
useEffect(() => {
  void loadGroups()
}, [])

const loadGroups = useCallback(async () => {
  const items = await getCapabilityGroups()
  setGroups(items)
  setSelectedGroupCode((current) => current || items[0]?.groupCode || '')
}, [])
```

- [ ] **Step 4: Run frontend build**

Run from `frontend`: `npm run build`

Expected: the main capability center shell renders without type errors.

## Task 3: Build Group List and Group Detail Tabs

**Files:**
- Create: `frontend/src/components/capability/CapabilityGroupList.tsx`
- Create: `frontend/src/components/capability/CapabilityGroupDetail.tsx`

- [ ] **Step 1: Build group list component**

Render rows like:

```tsx
<button
  type="button"
  className={`w-full rounded-xl border px-3 py-3 text-left ${selected ? 'border-slate-900 bg-slate-50' : 'border-slate-200 bg-white'}`}
  onClick={() => onSelect(group.groupCode)}
>
  <div className="text-sm font-semibold text-slate-900">{group.groupName}</div>
  <div className="text-xs text-slate-500">{group.groupCode} · {group.domainCode}</div>
</button>
```

Add list-level actions for create, edit, delete, and publish snapshot.

- [ ] **Step 2: Build detail tabs**

Use tab state:

```ts
type CapabilityGroupTab = 'items' | 'auth' | 'tests' | 'snapshots'
const [activeTab, setActiveTab] = useState<CapabilityGroupTab>('items')
```

Render tab buttons and switch the right-side content to the related subpanel.

- [ ] **Step 3: Add group form dialog flow**

Reuse the existing `FormDialog` pattern if it fits. The submit payload should look like:

```ts
{
  groupCode: form.groupCode.trim(),
  groupName: form.groupName.trim(),
  domainCode: form.domainCode.trim(),
  description: form.description.trim(),
  defaultAuthConfigId: form.defaultAuthConfigId || null,
}
```

- [ ] **Step 4: Run frontend build**

Run from `frontend`: `npm run build`

Expected: group selection, create/edit dialog, and tab layout compile.

## Task 4: Build Capability Item Table and Editor

**Files:**
- Create: `frontend/src/components/capability/CapabilityItemTable.tsx`
- Create: `frontend/src/components/capability/CapabilityEditor.tsx`

- [ ] **Step 1: Build unified capability table**

Render columns:

```tsx
<th>名称</th>
<th>编码</th>
<th>类型</th>
<th>草稿版本</th>
<th>发布版本</th>
<th>最近测试</th>
<th>操作</th>
```

Support type filtering with:

```ts
const [typeFilter, setTypeFilter] = useState<'ALL' | CapabilityType>('ALL')
```

- [ ] **Step 2: Build editor form state**

Use form state like:

```ts
const [form, setForm] = useState({
  capabilityCode: '',
  capabilityName: '',
  capabilityType: 'API' as CapabilityType,
  version: 'draft',
  definitionJson: '{\n  "url": "",\n  "method": "POST"\n}',
  inputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
  outputSchema: '{\n  "type": "object",\n  "properties": {}\n}',
  authBinding: '{}',
  environmentBinding: '{}',
})
```

- [ ] **Step 3: Add type-specific editor sections**

Render per-type forms, but store back into `definitionJson`. For example:

```tsx
{form.capabilityType === 'API' && (
  <>
    <input value={apiUrl} ... placeholder="接口地址" />
    <select value={apiMethod} ...>
      <option value="GET">GET</option>
      <option value="POST">POST</option>
    </select>
  </>
)}
```

Similarly render:

- Skill: source, skill name, executor type, endpoint, allowed tools
- MCP: server URL, protocol, discovery result summary

- [ ] **Step 4: Save draft and publish**

Wire buttons:

```tsx
<button className="prompt-secondary" onClick={() => void onValidate()}>校验配置</button>
<button className="prompt-secondary" onClick={() => void onTest()}>运行测试</button>
<button className="prompt-primary" onClick={() => void onSaveDraft()}>保存草稿</button>
<button className="prompt-primary" onClick={() => void onPublish()}>发布</button>
```

- [ ] **Step 5: Run frontend build**

Run from `frontend`: `npm run build`

Expected: table and editor compile and the form can switch between API / Skill / MCP.

## Task 5: Build Auth Config and Test Panels

**Files:**
- Create: `frontend/src/components/capability/AuthConfigEditor.tsx`
- Create: `frontend/src/components/capability/CapabilityTestPanel.tsx`

- [ ] **Step 1: Build auth config editor**

Use auth type options:

```ts
const authTypeOptions = [
  { value: 'oauth2', label: 'OAuth 2.0' },
  { value: 'jwt', label: 'JWT / Bearer Token' },
  { value: 'api_key', label: 'API Key' },
  { value: 'basic', label: 'Basic Auth' },
  { value: 'username_password', label: '账号 + 密码' },
  { value: 'custom_header', label: '自定义 Header' },
  { value: 'anonymous', label: '匿名' },
]
```

Render masked values only:

```tsx
<input value={maskedPreview} disabled className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm" />
```

- [ ] **Step 2: Build test panel state**

Use:

```ts
const [testPayload, setTestPayload] = useState('{\n  "input": {}\n}')
const [testResult, setTestResult] = useState<CapabilityTestResult | null>(null)
const [isTesting, setIsTesting] = useState(false)
```

- [ ] **Step 3: Render capability-specific test actions**

For API show:

```tsx
<button onClick={() => runTest('connection')}>测试连接</button>
<button onClick={() => runTest('request')}>发送测试请求</button>
```

For MCP show:

```tsx
<button onClick={() => runTest('connection')}>测试连接</button>
<button onClick={() => runTest('discovery')}>发现能力</button>
<button onClick={() => runTest('execute')}>执行测试</button>
```

For Skill show:

```tsx
<button onClick={() => runTest('validate')}>校验配置</button>
<button onClick={() => runTest('sample')}>运行测试</button>
<button onClick={() => runTest('context')}>上下文模拟</button>
```

- [ ] **Step 4: Render test result card**

Render:

```tsx
{testResult && (
  <div className="rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm">
    <div className="font-semibold">{testResult.success ? '测试通过' : '测试失败'}</div>
    <pre className="mt-2 overflow-auto text-xs">{JSON.stringify(testResult, null, 2)}</pre>
  </div>
)}
```

- [ ] **Step 5: Run frontend build**

Run from `frontend`: `npm run build`

Expected: auth editor and test panel compile and render.

## Task 6: Add Snapshot Panel and Integrate Workflow Node Editor

**Files:**
- Create: `frontend/src/components/capability/GroupSnapshotPanel.tsx`
- Modify: `frontend/src/components/Orchestrator.tsx`

- [ ] **Step 1: Build snapshot publish panel**

Render list rows like:

```tsx
<div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
  <div className="text-sm font-semibold text-slate-900">{snapshot.snapshotVersion}</div>
  <div className="text-xs text-slate-500">{snapshot.publishedAt || '未发布'}</div>
</div>
```

Add a publish action that creates a new snapshot version.

- [ ] **Step 2: Extend `tool` node form to support capability mode**

In `Orchestrator.tsx`, replace the current default tool template:

```ts
config: {
  invoke_type: 'api',
  url: '',
  method: 'POST',
  payload_mapping: {},
}
```

with:

```ts
config: {
  invoke_type: 'capability',
  group_code: '',
  group_snapshot_version: '',
  capability_type: 'API',
  capability_code: '',
  capability_version: '',
  payload_mapping: {},
}
```

- [ ] **Step 3: Add capability selectors in node editor**

In the tool-node editor branch, render:

```tsx
<select value={String(selectedNodeData.config.group_code || '')} ... />
<select value={String(selectedNodeData.config.group_snapshot_version || '')} ... />
<select value={String(selectedNodeData.config.capability_type || 'API')} ... />
<select value={String(selectedNodeData.config.capability_code || '')} ... />
```

Load selectable options from published group snapshots and capabilities returned by the new API.

- [ ] **Step 4: Update serializer and hydrator**

In `normalizeNodeConfig` add:

```ts
if (invokeType === 'capability') {
  base.group_code = String(config.group_code || '')
  base.group_snapshot_version = String(config.group_snapshot_version || '')
  base.capability_type = String(config.capability_type || 'API')
  base.capability_code = String(config.capability_code || '')
  base.capability_version = String(config.capability_version || '')
}
```

In `denormalizeNodeConfig` mirror the same fields.

- [ ] **Step 5: Run frontend build**

Run from `frontend`: `npm run build`

Expected: workflow tool node now supports capability selection without breaking other node types.

## Task 7: Add Frontend E2E Coverage

**Files:**
- Create: `frontend/tests/e2e/capability-center.spec.ts`

- [ ] **Step 1: Add mocked capability center routes**

Set up route mocks for:

- `/api/capabilities/groups`
- `/api/capabilities/groups/:groupCode/items`
- `/api/capabilities/groups/:groupCode/snapshots`
- `/api/capabilities/*/validate`
- `/api/capabilities/*/test`

- [ ] **Step 2: Add capability center CRUD flow test**

Write a scenario:

```ts
test('creates a capability group, edits a capability draft, tests it, and shows snapshots', async ({ page }) => {
  await page.goto('/')
  await page.getByText('能力中心').click()
  await expect(page.getByText('支付域')).toBeVisible()
})
```

Assert:

- group list visible
- capability item row visible
- editor switches between API / Skill / MCP
- test result card appears
- snapshot row appears

- [ ] **Step 3: Add orchestrator node selection assertion**

Mock published capability data, then assert in workflow editor:

```ts
await page.getByText('+ 工具节点').click()
await page.getByLabel('调用方式').selectOption('capability')
await page.getByLabel('集合组').selectOption('payment_domain')
await page.getByLabel('能力项').selectOption('payment_refund_apply')
```

Verify the form reflects the selected snapshot and capability.

- [ ] **Step 4: Run targeted E2E**

Run from `frontend`: `npm run test:e2e -- capability-center.spec.ts`

Expected: the new spec passes.

## Task 8: Final Verification and Scope Check

**Files:**
- Verify all frontend files touched by this plan.

- [ ] **Step 1: Run text integrity check**

Run from `frontend`: `npm run check:text`

Expected: no mojibake or suspicious text conversion warnings.

- [ ] **Step 2: Run frontend build**

Run from `frontend`: `npm run build`

Expected: Vite build passes.

- [ ] **Step 3: Run targeted E2E**

Run from `frontend`: `npm run test:e2e -- capability-center.spec.ts`

Expected: the capability center scenario passes.

- [ ] **Step 4: Inspect frontend diff**

Run:

```powershell
git diff -- frontend docs/superpowers/specs/2026-04-25-capability-center-design.md docs/superpowers/plans/2026-04-25-capability-center-frontend-implementation.md
```

Expected: changes are limited to capability center UI, workflow node integration, and tests.
