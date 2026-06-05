import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  deleteApiGroup,
  deleteApiItem,
  getApiGroups,
  getApisByGroup,
  getApiItem,
  getApiGroupAuthConfig,
  saveApiGroup,
  saveApiGroupAuthConfig,
  saveApiItem,
  testApiItem,
  validateApiItem,
  type ApiAuthConfigPayload,
  type ApiAuthMode,
  type ApiAuthType,
  type ApiItemPayload,
} from '../services/api'
import type { ApiGroupSummary, ApiItemSummary } from '../types'

interface ApiCenterPanelProps {
  currentUserId: string
}

type GroupFormState = {
  groupName: string
  description: string
  enabled: boolean
  authConfig: AuthConfigState
}

type ApiFormState = {
  id?: number
  apiName: string
  description: string
  enabled: boolean
  requestUrl: string
  requestMethod: string
  authMode: ApiAuthMode
  authConfig: AuthConfigState
  inheritedAuthPreview?: string
  headers: HeaderRow[]
  inputSchema: string
  outputSchema: string
  urlVariables: Record<string, string>
  body: string
}

type HeaderRow = {
  key: string
  value: string
  enabled: boolean
}

type ApiTestFormState = {
  body: string
  urlVariables: Record<string, string>
}

type AuthConfigState = {
  authType: ApiAuthType
  key: string
  value: string
  addTo: 'HEADER' | 'QUERY'
  token: string
  username: string
  password: string
  realm: string
  nonce: string
  algorithm: string
  qop: string
}

const draft07BodySchema = `{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "请求体参数",
  "properties": {},
  "additionalProperties": false
}`

const draft07OutputSchema = `{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "title": "返回结果",
  "properties": {},
  "additionalProperties": true
}`

const defaultAuthConfig = (): AuthConfigState => ({
  authType: 'NO_AUTH',
  key: '',
  value: '',
  addTo: 'HEADER',
  token: '',
  username: '',
  password: '',
  realm: '',
  nonce: '',
  algorithm: 'MD5',
  qop: 'auth',
})

const defaultGroupForm = (): GroupFormState => ({ groupName: '', description: '', enabled: true, authConfig: defaultAuthConfig() })
const GROUP_PAGE_SIZE = 8

const defaultApiForm = (): ApiFormState => ({
  apiName: '',
  description: '',
  enabled: true,
  requestUrl: '',
  requestMethod: 'GET',
  authMode: 'INHERIT',
  authConfig: defaultAuthConfig(),
  inheritedAuthPreview: 'No Auth',
  headers: [],
  inputSchema: draft07BodySchema,
  outputSchema: draft07OutputSchema,
  urlVariables: {},
  body: '{}',
})

const defaultApiTestForm = (): ApiTestFormState => ({ body: '{}', urlVariables: {} })

const ApiCenterPanel: React.FC<ApiCenterPanelProps> = ({ currentUserId }) => {
  const [groups, setGroups] = useState<ApiGroupSummary[]>([])
  const [selectedGroupId, setSelectedGroupId] = useState<number | null>(null)
  const [items, setItems] = useState<ApiItemSummary[]>([])
  const [groupPage, setGroupPage] = useState(1)
  const [groupForm, setGroupForm] = useState<GroupFormState>(defaultGroupForm)
  const [apiForm, setApiForm] = useState<ApiFormState>(defaultApiForm)
  const [apiTestForm, setApiTestForm] = useState<ApiTestFormState>(defaultApiTestForm)
  const [editingGroupId, setEditingGroupId] = useState<number | null>(null)
  const [editingApiId, setEditingApiId] = useState<number | undefined>()
  const [testingApiId, setTestingApiId] = useState<number | null>(null)
  const [groupDialogOpen, setGroupDialogOpen] = useState(false)
  const [apiDialogOpen, setApiDialogOpen] = useState(false)
  const [apiTestDialogOpen, setApiTestDialogOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [validationIssues, setValidationIssues] = useState<Array<{ field?: string; message?: string }>>([])
  const [isLoading, setIsLoading] = useState(false)

  const urlVariables = useMemo(() => extractUrlVariables(apiForm.requestUrl), [apiForm.requestUrl])
  const testingApi = items.find((item) => item.id === testingApiId) ?? null
  const testingUrlVariables = useMemo(() => extractUrlVariables(testingApi?.requestUrl ?? ''), [testingApi?.requestUrl])

  const loadGroups = useCallback(async () => {
    setIsLoading(true)
    try {
      const loadedGroups = await getApiGroups()
      setGroups(loadedGroups)
      setSelectedGroupId((current) => current ?? loadedGroups[0]?.id ?? null)
      setError(null)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载API组失败。')
    } finally {
      setIsLoading(false)
    }
  }, [])

  const loadGroupDetail = useCallback(async (groupId: number | null) => {
    if (!groupId) {
      setItems([])
      return
    }
    try {
      const loadedItems = await getApisByGroup(groupId)
      setItems(loadedItems)
      setError(null)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载API组详情失败。')
    }
  }, [])

  useEffect(() => {
    void loadGroups()
  }, [loadGroups])

  useEffect(() => {
    void loadGroupDetail(selectedGroupId)
  }, [selectedGroupId, loadGroupDetail])

  useEffect(() => {
    setApiForm((current) => {
      const nextVariables = { ...current.urlVariables }
      urlVariables.forEach((variable) => {
        if (!(variable in nextVariables)) {
          nextVariables[variable] = ''
        }
      })
      Object.keys(nextVariables).forEach((variable) => {
        if (!urlVariables.includes(variable)) {
          delete nextVariables[variable]
        }
      })
      return { ...current, urlVariables: nextVariables }
    })
  }, [urlVariables])

  useEffect(() => {
    setApiTestForm((current) => {
      const nextVariables = { ...current.urlVariables }
      testingUrlVariables.forEach((variable) => {
        if (!(variable in nextVariables)) {
          nextVariables[variable] = ''
        }
      })
      Object.keys(nextVariables).forEach((variable) => {
        if (!testingUrlVariables.includes(variable)) {
          delete nextVariables[variable]
        }
      })
      return { ...current, urlVariables: nextVariables }
    })
  }, [testingUrlVariables])

  useEffect(() => {
    if ((!apiDialogOpen && !apiTestDialogOpen) || validationIssues.length === 0) return
    const timer = window.setTimeout(() => setValidationIssues([]), 3000)
    return () => window.clearTimeout(timer)
  }, [apiDialogOpen, apiTestDialogOpen, validationIssues])

  const submitGroup = async () => {
    try {
      const saved = await saveApiGroup({ groupName: groupForm.groupName, description: groupForm.description, enabled: groupForm.enabled }, currentUserId, editingGroupId ?? undefined)
      await saveApiGroupAuthConfig(saved.id, serializeAuthConfig(groupForm.authConfig), currentUserId)
      setStatusMessage(`已保存API组：${saved.groupName}`)
      setGroupDialogOpen(false)
      setSelectedGroupId(saved.id)
      await loadGroups()
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存API组失败。')
    }
  }

  const submitApi = async () => {
    if (!selectedGroupId) return
    try {
      const payload = buildApiPayload(apiForm)
      const validation = await validateApiItem(selectedGroupId, payload, currentUserId)
      setValidationIssues(validation.issues ?? [])
      if (!validation.valid) {
        return
      }
      const saved = await saveApiItem(selectedGroupId, editingApiId, payload, currentUserId)
      setStatusMessage(`已保存API：${saved.apiName}`)
      setApiDialogOpen(false)
      await loadGroupDetail(selectedGroupId)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存API失败。')
    }
  }

  const handleTestSavedApi = async () => {
    if (!selectedGroupId || !testingApi) return
    try {
      const detail = await getApiItem(selectedGroupId, testingApi.id)
      const result = await testApiItem(selectedGroupId, buildApiPayload({
        id: detail.id,
        apiName: detail.apiName,
        description: detail.description ?? '',
        enabled: detail.enabled ?? true,
        requestUrl: detail.requestUrl,
        requestMethod: detail.requestMethod,
        authMode: parseAuthMode(detail.authMode),
        authConfig: parseAuthConfig(detail.authConfig),
        inheritedAuthPreview: String(detail.authPreview ?? selectedGroup?.authPreview ?? 'No Auth'),
        headers: parseHeaderRows(detail.headers),
        inputSchema: detail.inputSchema ?? draft07BodySchema,
        outputSchema: detail.outputSchema ?? draft07OutputSchema,
        urlVariables: apiTestForm.urlVariables,
        body: apiTestForm.body,
      }), currentUserId)
      setStatusMessage(result.success ? '请求测试通过' : null)
      setValidationIssues(result.success ? [] : [{ message: formatRequestTestError(result.errorMessage) }])
      setError(null)
      if (result.success) {
        setApiTestDialogOpen(false)
      }
      await loadGroupDetail(selectedGroupId)
    } catch (testError) {
      setValidationIssues([{ message: formatRequestTestError(testError instanceof Error ? testError.message : undefined) }])
      setError(null)
    }
  }

  const openCreateGroup = () => {
    setEditingGroupId(null)
    setGroupForm(defaultGroupForm())
    setGroupDialogOpen(true)
  }

  const openEditGroup = async (group: ApiGroupSummary) => {
    setEditingGroupId(group.id)
    setGroupForm({ groupName: group.groupName, description: group.description ?? '', enabled: group.enabled ?? true, authConfig: defaultAuthConfig() })
    try {
      const authConfig = await getApiGroupAuthConfig(group.id)
      setGroupForm((current) => ({ ...current, authConfig: parseAuthConfig(authConfig) }))
    } catch {
      setGroupForm((current) => ({ ...current, authConfig: defaultAuthConfig() }))
    }
    setGroupDialogOpen(true)
  }

  const openCreateApi = () => {
    setEditingApiId(undefined)
    setApiForm({ ...defaultApiForm(), inheritedAuthPreview: selectedGroup?.authPreview ?? 'No Auth' })
    setValidationIssues([])
    setApiDialogOpen(true)
  }

  const openEditApi = async (item: ApiItemSummary) => {
    if (!selectedGroupId) return
    const detail = await getApiItem(selectedGroupId, item.id)
    setEditingApiId(detail.id)
    setApiForm({
      id: detail.id,
      apiName: detail.apiName,
      description: detail.description ?? '',
      enabled: detail.enabled ?? true,
      requestUrl: detail.requestUrl,
      requestMethod: detail.requestMethod,
      authMode: parseAuthMode(detail.authMode),
      authConfig: parseAuthConfig(detail.authConfig),
      inheritedAuthPreview: String(detail.authPreview ?? selectedGroup?.authPreview ?? 'No Auth'),
      headers: parseHeaderRows(detail.headers),
      inputSchema: detail.inputSchema ?? draft07BodySchema,
      outputSchema: detail.outputSchema ?? draft07OutputSchema,
      urlVariables: {},
      body: '{}',
    })
    setValidationIssues([])
    setApiDialogOpen(true)
  }

  const openTestApi = (item: ApiItemSummary) => {
    setTestingApiId(item.id)
    setApiTestForm(defaultApiTestForm())
    setValidationIssues([])
    setApiTestDialogOpen(true)
  }

  const selectedGroup = groups.find((group) => group.id === selectedGroupId)
  const totalGroupPages = Math.max(1, Math.ceil(groups.length / GROUP_PAGE_SIZE))
  const visibleGroups = groups.slice((groupPage - 1) * GROUP_PAGE_SIZE, groupPage * GROUP_PAGE_SIZE)

  useEffect(() => {
    setGroupPage((current) => Math.min(current, totalGroupPages))
  }, [totalGroupPages])

  return (
    <div className="panel-card api-center-panel" data-testid="api-center-panel">
      <div className="panel-header">
        <div>
          <div className="panel-title">API中心</div>
          <div className="text-xs text-slate-500">按 API 组治理接口、Schema 与请求测试</div>
        </div>
        <button className="rounded-lg bg-slate-900 px-3 py-2 text-xs font-semibold text-white" onClick={openCreateGroup} type="button">
          新增API组
        </button>
      </div>
      <div className="panel-body api-center-body">
        {error && <div className="rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">{error}</div>}
        {statusMessage && <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{statusMessage}</div>}
        <div className="api-center-layout">
          <aside className="api-group-sidebar">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-sm font-semibold text-slate-700">API组</div>
              <button className="text-xs text-slate-500" onClick={() => void loadGroups()} type="button">刷新</button>
            </div>
            {isLoading && <div className="text-sm text-slate-500">加载中...</div>}
            <div className="api-group-list">
              {visibleGroups.map((group) => (
                <button
                  key={group.id}
                  className={`w-full rounded-xl border px-3 py-3 text-left ${group.id === selectedGroupId ? 'border-sky-300 bg-sky-50' : 'border-slate-200 bg-white'}`}
                  onClick={() => setSelectedGroupId(group.id)}
                  type="button"
                >
                  <div className="font-medium text-slate-800">{group.groupName}</div>
                  <div className="mt-1 text-xs text-slate-500">API 数：{group.apiCount ?? 0}</div>
                </button>
              ))}
            </div>
            <div className="mt-auto flex items-center justify-between border-t border-slate-200 pt-3 text-xs text-slate-500">
              <button className="rounded-lg border border-slate-200 px-3 py-1 disabled:opacity-40" disabled={groupPage <= 1} onClick={() => setGroupPage((current) => Math.max(1, current - 1))} type="button">上一页</button>
              <span>第 {groupPage} / {totalGroupPages} 页</span>
              <button className="rounded-lg border border-slate-200 px-3 py-1 disabled:opacity-40" disabled={groupPage >= totalGroupPages} onClick={() => setGroupPage((current) => Math.min(totalGroupPages, current + 1))} type="button">下一页</button>
            </div>
          </aside>
          <section className="api-items-panel">
            <div className="flex min-h-0 flex-1 flex-col rounded-2xl border border-slate-200 bg-white/80 p-4">
              <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div>
                  <div className="text-sm font-semibold text-slate-800">{selectedGroup?.groupName ?? '请选择API组'}</div>
                  <div className="text-xs text-slate-500">Header 可选；输入 Schema 只描述请求体 Body。</div>
                </div>
                <div className="flex gap-2">
                  {selectedGroup && <button className="rounded-lg border border-slate-200 px-3 py-2 text-xs" onClick={() => void openEditGroup(selectedGroup)} type="button">编辑API组</button>}
                  {selectedGroup && <button className="rounded-lg border border-red-200 px-3 py-2 text-xs text-red-600" onClick={async () => {
                    if (window.confirm(`确认删除API组 ${selectedGroup.groupName} 吗？`)) {
                      await deleteApiGroup(selectedGroup.id, currentUserId)
                      setSelectedGroupId(null)
                      await loadGroups()
                    }
                  }} type="button">删除API组</button>}
                  <button className="rounded-lg bg-sky-600 px-3 py-2 text-xs font-semibold text-white disabled:opacity-50" disabled={!selectedGroupId} onClick={openCreateApi} type="button">新增API</button>
                </div>
              </div>
              <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
                {items.map((item) => (
                  <div
                    key={item.id}
                    className="rounded-xl border border-slate-200 bg-slate-50 p-4"
                    data-testid={`api-item-${item.id}`}
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <div className="font-medium text-slate-800">{item.apiName}</div>
                        <div className="mt-1 text-xs text-slate-500">{item.requestMethod} {item.requestUrl}</div>
                        <div className="mt-1 text-xs text-slate-400">测试状态：{displayTestStatus(item.lastTestStatus)} / {formatDateTime(item.lastTestTime)}</div>
                      </div>
                      <div className="flex items-center gap-2">
                        <button className="rounded-md border border-sky-200 px-2 py-1 text-xs text-sky-700 hover:border-sky-300" onClick={() => void openEditApi(item)} type="button">编辑</button>
                        <button className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:border-slate-400" onClick={() => openTestApi(item)} type="button">请求测试</button>
                        <button className="rounded-md border border-red-200 px-2 py-1 text-xs text-red-600 hover:border-red-300" onClick={async () => {
                          if (selectedGroupId && window.confirm(`确认删除API ${item.apiName} 吗？`)) {
                            await deleteApiItem(selectedGroupId, item.id, currentUserId)
                            await loadGroupDetail(selectedGroupId)
                          }
                        }} type="button">删除</button>
                      </div>
                    </div>
                  </div>
                ))}
                {items.length === 0 && <EmptyCard message="当前API组暂无API，请点击“新增API”。" />}
              </div>
            </div>
          </section>
        </div>
      </div>

      {groupDialogOpen && (
        <ModalShell title={editingGroupId ? '编辑API组' : '新增API组'} onClose={() => setGroupDialogOpen(false)} actions={(
          <>
            <button className="rounded-lg border border-slate-200 px-4 py-2 text-sm" onClick={() => setGroupDialogOpen(false)} type="button">取消</button>
            <button className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white" onClick={() => void submitGroup()} type="button">保存</button>
          </>
        )}>
          <div className="api-group-form">
            <Field label="API组名称" inputId="api-group-name"><input id="api-group-name" className="form-input" value={groupForm.groupName} onChange={(event) => setGroupForm((current) => ({ ...current, groupName: event.target.value }))} /></Field>
            <Field label="描述" inputId="api-group-description"><textarea id="api-group-description" className="form-textarea" value={groupForm.description} onChange={(event) => setGroupForm((current) => ({ ...current, description: event.target.value }))} /></Field>
            <div className="rounded-xl border border-slate-200 p-3">
              <div className="mb-3 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">鉴权中心</div>
              <AuthConfigFields value={groupForm.authConfig} onChange={(authConfig) => setGroupForm((current) => ({ ...current, authConfig }))} prefix="group-auth" />
            </div>
          </div>
        </ModalShell>
      )}

      {apiDialogOpen && (
        <ModalShell title={editingApiId ? '编辑API' : '新增API'} wide onClose={() => setApiDialogOpen(false)} actions={(
          <>
            <button className="rounded-lg border border-slate-200 px-4 py-2 text-sm" onClick={() => setApiDialogOpen(false)} type="button">取消</button>
            <button className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white" onClick={() => void submitApi()} type="button">保存</button>
          </>
        )}>
          <div className="api-form-grid">
            <Field label="API名称" inputId="api-name"><input id="api-name" className="form-input" value={apiForm.apiName} onChange={(event) => updateApiForm({ apiName: event.target.value })} /></Field>
            <Field label="请求方法" inputId="api-method"><select id="api-method" className="form-input" value={apiForm.requestMethod} onChange={(event) => updateApiForm({ requestMethod: event.target.value })}>{['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((method) => <option key={method} value={method}>{method}</option>)}</select></Field>
            <Field label="描述" inputId="api-description" className="md:col-span-2"><textarea id="api-description" className="form-textarea" value={apiForm.description} onChange={(event) => updateApiForm({ description: event.target.value })} /></Field>
            <Field label="请求URL" inputId="api-url" className="md:col-span-2"><input id="api-url" className="form-input" value={apiForm.requestUrl} onChange={(event) => updateApiForm({ requestUrl: event.target.value })} placeholder="https://example.com/users?userId={userId}" /></Field>
            {urlVariables.length > 0 && (
              <div className="md:col-span-2 rounded-xl border border-slate-200 p-3">
                <div className="mb-3 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">URL变量映射</div>
                <div className="grid gap-3">
                  {urlVariables.map((variable) => (
                    <Field key={variable} label={variable} inputId={`url-var-${variable}`}><input id={`url-var-${variable}`} className="form-input" value={apiForm.urlVariables[variable] || variable} onChange={(event) => updateApiForm({ urlVariables: { ...apiForm.urlVariables, [variable]: event.target.value } })} /></Field>
                  ))}
                </div>
              </div>
            )}
            <div className="md:col-span-2 rounded-xl border border-slate-200 p-3">
              <div className="mb-3 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">鉴权策略</div>
              <div className="grid gap-3 md:grid-cols-2">
                <Field label="策略" inputId="api-auth-mode"><select id="api-auth-mode" className="form-input" value={apiForm.authMode} onChange={(event) => updateApiForm({ authMode: event.target.value as ApiAuthMode })}>
                  <option value="INHERIT">继承API组鉴权</option>
                  <option value="NONE">不使用鉴权中心</option>
                  <option value="CUSTOM">自定义鉴权</option>
                </select></Field>
                <Field label="当前鉴权" inputId="api-auth-preview"><input id="api-auth-preview" className="form-input" value={apiForm.authMode === 'CUSTOM' ? previewAuthConfig(apiForm.authConfig) : apiForm.authMode === 'NONE' ? 'No Auth' : apiForm.inheritedAuthPreview ?? 'No Auth'} readOnly /></Field>
              </div>
              {apiForm.authMode === 'NONE' && <div className="mt-3 rounded-lg border border-dashed border-slate-200 px-3 py-3 text-xs text-slate-500">不使用鉴权中心时，Headers 仍可填写 Authorization、X-API-Key 等鉴权 Header。</div>}
              {apiForm.authMode === 'CUSTOM' && <div className="mt-3"><AuthConfigFields value={apiForm.authConfig} onChange={(authConfig) => updateApiForm({ authConfig })} prefix="api-auth" /></div>}
            </div>
            <div className="md:col-span-2 rounded-xl border border-slate-200 p-3">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">Headers</div>
                <button className="rounded-lg border border-slate-200 px-3 py-1 text-xs text-slate-600 hover:border-slate-400" onClick={addHeaderRow} type="button">新增Header</button>
              </div>
              <div className="mb-3 text-xs text-slate-500">可填写业务 Header，也可继续填写 Authorization、X-API-Key 等鉴权 Header；不会因鉴权中心配置而阻断保存。</div>
              <div className="api-header-row api-header-row-head">
                <span>启用</span>
                <span>Key</span>
                <span>Value</span>
                <span>操作</span>
              </div>
              <div className="grid gap-2">
                {apiForm.headers.map((header, index) => (
                  <div className="api-header-row" key={index}>
                    <input aria-label={`启用 Header ${index + 1}`} checked={header.enabled} onChange={(event) => updateHeaderRow(index, { enabled: event.target.checked })} type="checkbox" />
                    <input aria-label={`Header Key ${index + 1}`} className="form-input" placeholder="Authorization" value={header.key} onChange={(event) => updateHeaderRow(index, { key: event.target.value })} />
                    <input aria-label={`Header Value ${index + 1}`} className="form-input" placeholder="Bearer token" value={header.value} onChange={(event) => updateHeaderRow(index, { value: event.target.value })} />
                    <button className="rounded-lg border border-red-200 px-3 py-2 text-xs text-red-600 hover:border-red-300" onClick={() => removeHeaderRow(index)} type="button">删除</button>
                  </div>
                ))}
                {apiForm.headers.length === 0 && <div className="rounded-lg border border-dashed border-slate-200 px-3 py-3 text-xs text-slate-500">暂无 Header，点击“新增Header”添加。</div>}
              </div>
            </div>
            <Field label="输入Schema（仅Body，Draft-07）" inputId="input-schema" className="md:col-span-2"><textarea id="input-schema" className="form-textarea min-h-[180px] font-mono" value={apiForm.inputSchema} onChange={(event) => updateApiForm({ inputSchema: event.target.value })} /></Field>
            <Field label="输出Schema（Draft-07）" inputId="output-schema" className="md:col-span-2"><textarea id="output-schema" className="form-textarea min-h-[180px] font-mono" value={apiForm.outputSchema} onChange={(event) => updateApiForm({ outputSchema: event.target.value })} /></Field>
          </div>
        </ModalShell>
      )}

      {(apiDialogOpen || apiTestDialogOpen) && validationIssues.length > 0 && (
        <div className="api-validation-toast" data-testid="api-validation-toast">
          {validationIssues.map((issue, index) => <span key={`${issue.field}-${index}`}>{formatValidationIssue(issue)}</span>)}
        </div>
      )}

      {apiTestDialogOpen && testingApi && (
        <ModalShell title="请求测试" wide onClose={() => setApiTestDialogOpen(false)} actions={(
          <>
            <button className="rounded-lg border border-slate-200 px-4 py-2 text-sm" onClick={() => setApiTestDialogOpen(false)} type="button">取消</button>
            <button className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-semibold text-white" onClick={() => void handleTestSavedApi()} type="button">开始测试</button>
          </>
        )}>
          <div className="api-form-grid">
            <Field label="API名称" inputId="test-api-name"><input id="test-api-name" className="form-input" value={testingApi.apiName} readOnly /></Field>
            <Field label="请求方法" inputId="test-api-method"><input id="test-api-method" className="form-input" value={testingApi.requestMethod} readOnly /></Field>
            <Field label="当前鉴权" inputId="test-api-auth"><input id="test-api-auth" className="form-input" value={testingApi.authPreview ?? 'No Auth'} readOnly /></Field>
            <Field label="请求URL" inputId="test-api-url" className="md:col-span-2"><input id="test-api-url" className="form-input" value={testingApi.requestUrl} readOnly /></Field>
            {testingUrlVariables.length > 0 && (
              <div className="md:col-span-2 rounded-xl border border-slate-200 p-3">
                <div className="mb-3 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">URL路径/查询参数</div>
                <div className="grid gap-3 md:grid-cols-2">
                  {testingUrlVariables.map((variable) => (
                    <Field key={variable} label={variable} inputId={`test-url-var-${variable}`}><input id={`test-url-var-${variable}`} className="form-input" value={apiTestForm.urlVariables[variable] || variable} onChange={(event) => setApiTestForm((current) => ({ ...current, urlVariables: { ...current.urlVariables, [variable]: event.target.value } }))} /></Field>
                  ))}
                </div>
              </div>
            )}
            <Field label="请求体测试参数 JSON" inputId="test-body-json" className="md:col-span-2"><textarea id="test-body-json" className="form-textarea font-mono" value={apiTestForm.body} onChange={(event) => setApiTestForm((current) => ({ ...current, body: event.target.value }))} /></Field>
          </div>
        </ModalShell>
      )}
    </div>
  )

  function updateApiForm(patch: Partial<ApiFormState>) {
    setApiForm((current) => ({ ...current, ...patch }))
  }

  function addHeaderRow() {
    updateApiForm({ headers: [...apiForm.headers, { key: '', value: '', enabled: true }] })
  }

  function updateHeaderRow(index: number, patch: Partial<HeaderRow>) {
    updateApiForm({
      headers: apiForm.headers.map((header, currentIndex) => currentIndex === index ? { ...header, ...patch } : header),
    })
  }

  function removeHeaderRow(index: number) {
    updateApiForm({ headers: apiForm.headers.filter((_, currentIndex) => currentIndex !== index) })
  }
}

function buildApiPayload(form: ApiFormState): ApiItemPayload {
  return {
    id: form.id,
    apiName: form.apiName.trim(),
    description: form.description.trim() || undefined,
    enabled: form.enabled,
    requestUrl: form.requestUrl.trim(),
    requestMethod: form.requestMethod,
    authMode: form.authMode,
    authConfig: form.authMode === 'CUSTOM' ? serializeAuthConfig(form.authConfig) : undefined,
    headers: serializeHeaderRows(form.headers),
    inputSchema: form.inputSchema.trim(),
    outputSchema: form.outputSchema.trim(),
    urlVariables: form.urlVariables,
    body: parseJsonObject(form.body),
  }
}

function serializeAuthConfig(config: AuthConfigState): ApiAuthConfigPayload {
  return {
    authType: config.authType,
    key: config.key.trim() || undefined,
    value: config.value.trim() || undefined,
    addTo: config.addTo,
    token: config.token.trim() || undefined,
    username: config.username.trim() || undefined,
    password: config.password.trim() || undefined,
    realm: config.realm.trim() || undefined,
    nonce: config.nonce.trim() || undefined,
    algorithm: config.algorithm.trim() || 'MD5',
    qop: config.qop.trim() || 'auth',
  }
}

function parseAuthConfig(value?: unknown): AuthConfigState {
  const record = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
  return {
    ...defaultAuthConfig(),
    authType: parseAuthType(record.authType),
    key: String(record.key ?? ''),
    value: String(record.value ?? ''),
    addTo: String(record.addTo ?? 'HEADER').toUpperCase() === 'QUERY' ? 'QUERY' : 'HEADER',
    token: String(record.token ?? ''),
    username: String(record.username ?? ''),
    password: String(record.password ?? ''),
    realm: String(record.realm ?? ''),
    nonce: String(record.nonce ?? ''),
    algorithm: String(record.algorithm ?? 'MD5'),
    qop: String(record.qop ?? 'auth'),
  }
}

function parseAuthType(value?: unknown): ApiAuthType {
  const text = String(value ?? 'NO_AUTH').toUpperCase()
  return ['NO_AUTH', 'API_KEY', 'BEARER', 'BASIC', 'DIGEST'].includes(text) ? text as ApiAuthType : 'NO_AUTH'
}

function parseAuthMode(value?: unknown): ApiAuthMode {
  const text = String(value ?? 'INHERIT').toUpperCase()
  return ['INHERIT', 'NONE', 'CUSTOM'].includes(text) ? text as ApiAuthMode : 'INHERIT'
}

function previewAuthConfig(config: AuthConfigState) {
  switch (config.authType) {
    case 'API_KEY': return `API Key ${config.addTo.toLowerCase()}:${config.key || '--'}`
    case 'BEARER': return 'Bearer 已配置'
    case 'BASIC': return `Basic ${config.username || '--'}`
    case 'DIGEST': return `Digest ${config.username || '--'}`
    default: return 'No Auth'
  }
}

function extractUrlVariables(url: string) {
  const variables: string[] = []
  const pattern = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(url)) !== null) {
    if (!variables.includes(match[1])) {
      variables.push(match[1])
    }
  }
  return variables
}

function parseJsonObject(value: string) {
  try {
    const parsed = JSON.parse(value || '{}')
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch {
    return {}
  }
}

function parseHeaderRows(value?: unknown): HeaderRow[] {
  if (Array.isArray(value)) {
    return normalizeHeaderRows(value)
  }
  if (typeof value !== 'string' || !value.trim()) return []
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed)) {
      return normalizeHeaderRows(parsed)
    }
  } catch {
    return []
  }
  return []
}

function normalizeHeaderRows(value: unknown[]): HeaderRow[] {
  return value
    .filter((item) => item && typeof item === 'object' && !Array.isArray(item))
    .map((item) => {
      const record = item as Record<string, unknown>
      return {
        key: String(record.key ?? record.name ?? record.headerName ?? ''),
        value: String(record.value ?? record.headerValue ?? ''),
        enabled: record.enabled === false || record.checked === false || record.selected === false ? false : true,
      }
    })
    .filter((item) => item.key.trim() || item.value.trim())
}

function serializeHeaderRows(headers: HeaderRow[]) {
  return headers
    .map((header) => ({ key: header.key.trim(), value: header.value.trim(), enabled: header.enabled }))
    .filter((header) => header.key || header.value)
}

function displayTestStatus(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'SUCCESS': return '通过'
    case 'FAILED': return '失败'
    default: return '暂无记录'
  }
}

function formatValidationIssue(issue: { field?: string; message?: string }) {
  const message = issue.message?.trim() || '校验失败'
  return issue.field ? `${issue.field}: ${message}` : message
}

function formatRequestTestError(message?: string | null) {
  const detail = message?.trim()
  return detail ? `请求测试失败：${detail}` : '请求测试失败，请检查请求配置或响应 Schema。'
}

function formatDateTime(value?: string | null) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

const EmptyCard: React.FC<{ message: string }> = ({ message }) => (
  <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-5 py-8 text-sm text-slate-500">{message}</div>
)

const ModalShell: React.FC<{ title: string; wide?: boolean; onClose: () => void; actions: React.ReactNode; children: React.ReactNode }> = ({ title, wide = false, onClose, actions, children }) => (
  <div className="form-overlay">
    <div data-testid="api-center-modal-card" className={`api-center-modal ${wide ? 'api-center-modal-wide' : ''}`}>
      <div className="mb-5 flex items-start justify-between gap-4">
        <div className="panel-title">{title}</div>
        <button type="button" className="text-sm text-slate-500 hover:text-slate-700" onClick={onClose}>关闭</button>
      </div>
      <div className="api-center-modal-body">{children}</div>
      <div className="mt-5 flex flex-wrap justify-end gap-2">{actions}</div>
    </div>
  </div>
)

const Field: React.FC<{ label: string; inputId: string; children: React.ReactNode; className?: string }> = ({ label, inputId, children, className }) => (
  <div className={className}>
    <label htmlFor={inputId} className="mb-2 block text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">{label}</label>
    {children}
  </div>
)

const AuthConfigFields: React.FC<{ value: AuthConfigState; onChange: (value: AuthConfigState) => void; prefix: string }> = ({ value, onChange, prefix }) => {
  const patch = (next: Partial<AuthConfigState>) => onChange({ ...value, ...next })
  return (
    <div className="grid gap-3 md:grid-cols-2">
      <Field label="鉴权类型" inputId={`${prefix}-type`}>
        <select id={`${prefix}-type`} className="form-input" value={value.authType} onChange={(event) => patch({ authType: event.target.value as ApiAuthType })}>
          <option value="NO_AUTH">No Auth</option>
          <option value="API_KEY">API Key</option>
          <option value="BEARER">Bearer Token</option>
          <option value="BASIC">Basic Auth</option>
          <option value="DIGEST">Digest Auth</option>
        </select>
      </Field>
      <Field label="配置摘要" inputId={`${prefix}-preview`}><input id={`${prefix}-preview`} className="form-input" value={previewAuthConfig(value)} readOnly /></Field>
      {value.authType === 'NO_AUTH' && <div className="md:col-span-2 rounded-lg border border-dashed border-slate-200 px-3 py-3 text-xs text-slate-500">不通过鉴权中心注入任何鉴权字段。</div>}
      {value.authType === 'API_KEY' && (
        <>
          <Field label="Key" inputId={`${prefix}-key`}><input id={`${prefix}-key`} className="form-input" value={value.key} onChange={(event) => patch({ key: event.target.value })} placeholder="X-API-Key" /></Field>
          <Field label="Value" inputId={`${prefix}-value`}><input id={`${prefix}-value`} className="form-input" type="password" value={value.value} onChange={(event) => patch({ value: event.target.value })} placeholder="API Key" /></Field>
          <Field label="添加到" inputId={`${prefix}-add-to`}><select id={`${prefix}-add-to`} className="form-input" value={value.addTo} onChange={(event) => patch({ addTo: event.target.value as 'HEADER' | 'QUERY' })}><option value="HEADER">Header</option><option value="QUERY">Query</option></select></Field>
        </>
      )}
      {value.authType === 'BEARER' && <Field label="Token" inputId={`${prefix}-token`} className="md:col-span-2"><input id={`${prefix}-token`} className="form-input" type="password" value={value.token} onChange={(event) => patch({ token: event.target.value })} placeholder="Bearer token" /></Field>}
      {value.authType === 'BASIC' && (
        <>
          <Field label="Username" inputId={`${prefix}-username`}><input id={`${prefix}-username`} className="form-input" value={value.username} onChange={(event) => patch({ username: event.target.value })} /></Field>
          <Field label="Password" inputId={`${prefix}-password`}><input id={`${prefix}-password`} className="form-input" type="password" value={value.password} onChange={(event) => patch({ password: event.target.value })} /></Field>
        </>
      )}
      {value.authType === 'DIGEST' && (
        <>
          <Field label="Username" inputId={`${prefix}-digest-username`}><input id={`${prefix}-digest-username`} className="form-input" value={value.username} onChange={(event) => patch({ username: event.target.value })} /></Field>
          <Field label="Password" inputId={`${prefix}-digest-password`}><input id={`${prefix}-digest-password`} className="form-input" type="password" value={value.password} onChange={(event) => patch({ password: event.target.value })} /></Field>
          <Field label="Realm（可选）" inputId={`${prefix}-realm`}><input id={`${prefix}-realm`} className="form-input" value={value.realm} onChange={(event) => patch({ realm: event.target.value })} /></Field>
          <Field label="Nonce（可选）" inputId={`${prefix}-nonce`}><input id={`${prefix}-nonce`} className="form-input" value={value.nonce} onChange={(event) => patch({ nonce: event.target.value })} /></Field>
          <Field label="Algorithm" inputId={`${prefix}-algorithm`}><input id={`${prefix}-algorithm`} className="form-input" value={value.algorithm} onChange={(event) => patch({ algorithm: event.target.value })} /></Field>
          <Field label="Qop" inputId={`${prefix}-qop`}><input id={`${prefix}-qop`} className="form-input" value={value.qop} onChange={(event) => patch({ qop: event.target.value })} /></Field>
        </>
      )}
    </div>
  )
}

export default ApiCenterPanel
