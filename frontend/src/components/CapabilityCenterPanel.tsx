import React, { useCallback, useEffect, useMemo, useState } from 'react'
import {
  deleteCapability,
  deleteCapabilityGroup,
  getCapabilitiesByGroup,
  getCapabilityAuthConfigs,
  getCapabilityGroups,
  getCapabilityGroupSnapshots,
  getCapabilityVersions,
  publishCapabilityGroupSnapshot,
  publishCapability,
  saveCapabilityAuthConfig,
  saveCapabilityDraft,
  saveCapabilityGroup,
  testCapability,
  validateCapabilityDraft,
} from '../services/api'
import type {
  AuthConfigSummary,
  CapabilityGroupSnapshot,
  CapabilityGroupSummary,
  CapabilityItemSummary,
  CapabilityType,
  CapabilityVersionSummary,
} from '../types'

interface CapabilityCenterPanelProps {
  currentUserId: string
}

type CapabilityTab = 'items' | 'auth' | 'snapshots'
type GroupMode = 'create' | 'edit'
type CapabilityMode = 'create' | 'edit'
type AuthMode = 'create' | 'edit'

type GroupFormState = {
  groupName: string
  description: string
}

type CapabilityFormState = {
  capabilityCode: string
  capabilityType: CapabilityType
  capabilityName: string
  description: string
  inputSchema: string
  outputSchema: string
  authConfigId: string
  apiUrl: string
  apiMethod: string
  apiHeaders: string
  skillName: string
  skillSource: string
  executorType: string
  skillEndpoint: string
  allowedCapabilities: string
  mcpServerUrl: string
  mcpProtocol: string
  mcpToolName: string
}

type AuthFormState = {
  id?: number
  authName: string
  authType: string
  configJson: string
}

const authTypeOptions = [
  { value: 'OAUTH2', label: 'OAuth 2.0' },
  { value: 'JWT', label: 'JWT / Bearer Token' },
  { value: 'API_KEY', label: 'API Key' },
  { value: 'PASSWORD', label: '账号密码' },
  { value: 'BASIC', label: 'Basic Auth' },
  { value: 'CUSTOM', label: '自定义 Header' },
  { value: 'NONE', label: '匿名访问' },
]

const defaultGroupForm = (): GroupFormState => ({
  groupName: '',
  description: '',
})

const defaultCapabilityForm = (authConfigs: AuthConfigSummary[] = []): CapabilityFormState => ({
  capabilityCode: '',
  capabilityType: 'API',
  capabilityName: '',
  description: '',
  inputSchema: '',
  outputSchema: '',
  authConfigId: authConfigs[0] ? String(authConfigs[0].id) : '',
  apiUrl: '',
  apiMethod: 'GET',
  apiHeaders: '{}',
  skillName: '',
  skillSource: 'builtin',
  executorType: 'sync',
  skillEndpoint: '',
  allowedCapabilities: '',
  mcpServerUrl: '',
  mcpProtocol: 'sse',
  mcpToolName: '',
})

const defaultAuthForm = (): AuthFormState => ({
  authName: '',
  authType: 'API_KEY',
  configJson: '{}',
})

const CapabilityCenterPanel: React.FC<CapabilityCenterPanelProps> = ({ currentUserId }) => {
  const [groups, setGroups] = useState<CapabilityGroupSummary[]>([])
  const [selectedGroupId, setSelectedGroupId] = useState<number | null>(null)
  const [items, setItems] = useState<CapabilityItemSummary[]>([])
  const [authConfigs, setAuthConfigs] = useState<AuthConfigSummary[]>([])
  const [snapshots, setSnapshots] = useState<CapabilityGroupSnapshot[]>([])
  const [activeTab, setActiveTab] = useState<CapabilityTab>('items')
  const [isLoadingGroups, setIsLoadingGroups] = useState(true)
  const [isLoadingDetail, setIsLoadingDetail] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)

  const [groupDialogOpen, setGroupDialogOpen] = useState(false)
  const [groupDialogMode, setGroupDialogMode] = useState<GroupMode>('create')
  const [groupForm, setGroupForm] = useState<GroupFormState>(defaultGroupForm)
  const [isSavingGroup, setIsSavingGroup] = useState(false)

  const [capabilityDialogOpen, setCapabilityDialogOpen] = useState(false)
  const [capabilityDialogMode, setCapabilityDialogMode] = useState<CapabilityMode>('create')
  const [capabilityForm, setCapabilityForm] = useState<CapabilityFormState>(defaultCapabilityForm)
  const [editingCapabilityCode, setEditingCapabilityCode] = useState<string | null>(null)
  const [isSavingCapability, setIsSavingCapability] = useState(false)
  const [validationIssues, setValidationIssues] = useState<Array<{ field?: string; message?: string }>>([])
  const [liveTestResult, setLiveTestResult] = useState<Record<string, unknown> | null>(null)
  const [isPublishingSnapshot, setIsPublishingSnapshot] = useState(false)
  const [snapshotDescription, setSnapshotDescription] = useState('')

  const [authDialogOpen, setAuthDialogOpen] = useState(false)
  const [authDialogMode, setAuthDialogMode] = useState<AuthMode>('create')
  const [authForm, setAuthForm] = useState<AuthFormState>(defaultAuthForm)
  const [isSavingAuth, setIsSavingAuth] = useState(false)

  const selectedGroup = useMemo(
    () => groups.find((group) => group.id === selectedGroupId) ?? null,
    [groups, selectedGroupId]
  )

  const loadGroups = useCallback(async () => {
    setIsLoadingGroups(true)
    setError(null)
    try {
      const loadedGroups = await getCapabilityGroups()
      setGroups(loadedGroups)
      setSelectedGroupId((current) => (loadedGroups.some((group) => group.id === current) ? current : (loadedGroups[0]?.id ?? null)))
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载能力组失败。')
      setGroups([])
      setSelectedGroupId(null)
    } finally {
      setIsLoadingGroups(false)
    }
  }, [])

  const loadGroupDetail = useCallback(async (groupId: number) => {
    setIsLoadingDetail(true)
    setError(null)
    try {
      const [nextItems, nextAuthConfigs, nextSnapshots] = await Promise.all([
        getCapabilitiesByGroup(groupId),
        getCapabilityAuthConfigs(groupId),
        getCapabilityGroupSnapshots(groupId),
      ])
      setItems(nextItems)
      setAuthConfigs(nextAuthConfigs)
      setSnapshots(nextSnapshots)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载能力组详情失败。')
      setItems([])
      setAuthConfigs([])
      setSnapshots([])
    } finally {
      setIsLoadingDetail(false)
    }
  }, [])

  useEffect(() => {
    void loadGroups()
  }, [loadGroups])

  useEffect(() => {
    if (selectedGroupId == null) {
      setItems([])
      setAuthConfigs([])
      setSnapshots([])
      return
    }
    void loadGroupDetail(selectedGroupId)
  }, [loadGroupDetail, selectedGroupId])

  const openCreateGroupDialog = () => {
    setGroupDialogMode('create')
    setGroupForm(defaultGroupForm())
    setGroupDialogOpen(true)
  }

  const openEditGroupDialog = () => {
    if (!selectedGroup) return
    setGroupDialogMode('edit')
    setGroupForm({
      groupName: selectedGroup.groupName,
      description: selectedGroup.description ?? '',
    })
    setGroupDialogOpen(true)
  }

  const submitGroupForm = async () => {
    setIsSavingGroup(true)
    setError(null)
    try {
      const saved = await saveCapabilityGroup(
        {
          groupName: groupForm.groupName.trim(),
          description: groupForm.description.trim() || undefined,
        },
        currentUserId,
        groupDialogMode === 'edit' ? selectedGroupId ?? undefined : undefined
      )
      setGroupDialogOpen(false)
      setStatusMessage(`已保存能力组：${saved.groupName}`)
      await loadGroups()
      setSelectedGroupId(saved.id)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存能力组失败。')
    } finally {
      setIsSavingGroup(false)
    }
  }

  const handleDeleteGroup = async () => {
    if (!selectedGroup || selectedGroupId == null) return
    if (!window.confirm(`确认删除能力组 ${selectedGroup.groupName} 吗？`)) return
    try {
      await deleteCapabilityGroup(selectedGroupId, currentUserId)
      setStatusMessage(`已删除能力组：${selectedGroup.groupName}`)
      await loadGroups()
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : '删除能力组失败。')
    }
  }

  const handlePublishSnapshot = async () => {
    if (selectedGroupId == null) return
    setIsPublishingSnapshot(true)
    setError(null)
    try {
      const snapshot = await publishCapabilityGroupSnapshot(
        selectedGroupId,
        { description: snapshotDescription.trim() || undefined },
        currentUserId
      )
      setStatusMessage(`已发布快照：${snapshot.snapshotVersion}`)
      setSnapshotDescription('')
      await loadGroupDetail(selectedGroupId)
    } catch (publishError) {
      setError(publishError instanceof Error ? publishError.message : '发布快照失败。')
    } finally {
      setIsPublishingSnapshot(false)
    }
  }

  const openCreateCapabilityDialog = () => {
    setCapabilityDialogMode('create')
    setEditingCapabilityCode(null)
    setCapabilityForm(defaultCapabilityForm(authConfigs))
    setValidationIssues([])
    setLiveTestResult(null)
    setCapabilityDialogOpen(true)
  }

  const openEditCapabilityDialog = async (item: CapabilityItemSummary) => {
    if (selectedGroupId == null) return
    setError(null)
    try {
      const versions = await getCapabilityVersions(selectedGroupId, item.capabilityCode)
      const targetVersion = versions.find((version) => version.status === 'DRAFT') ?? versions[0]
      setCapabilityDialogMode('edit')
      setEditingCapabilityCode(item.capabilityCode)
      setCapabilityForm(buildCapabilityFormFromVersion(targetVersion, item, authConfigs))
      setValidationIssues([])
      setLiveTestResult(null)
      setCapabilityDialogOpen(true)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '加载能力详情失败。')
    }
  }

  const submitCapabilityForm = async () => {
    if (selectedGroupId == null) return
    setIsSavingCapability(true)
    setError(null)
    try {
      const payload = buildCapabilityPayload(capabilityForm)
      await saveCapabilityDraft(
        selectedGroupId,
        capabilityDialogMode === 'edit' ? editingCapabilityCode ?? undefined : undefined,
        payload,
        currentUserId
      )
      setCapabilityDialogOpen(false)
      setStatusMessage(`已保存能力草稿：${payload.capabilityName}`)
      await loadGroupDetail(selectedGroupId)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存能力草稿失败。')
    } finally {
      setIsSavingCapability(false)
    }
  }

  const handleValidateCapability = async () => {
    if (selectedGroupId == null) return
    try {
      const result = await validateCapabilityDraft(selectedGroupId, buildCapabilityPayload(capabilityForm), currentUserId)
      setValidationIssues(Array.isArray(result.issues) ? result.issues : [])
      setStatusMessage(result.message)
    } catch (validateError) {
      setError(validateError instanceof Error ? validateError.message : '校验能力配置失败。')
    }
  }

  const handleRunCapabilityTest = async (capabilityCodeOverride?: string) => {
    if (selectedGroupId == null) return
    const targetCapabilityCode = capabilityCodeOverride ?? editingCapabilityCode
    if (!targetCapabilityCode) {
      setError('请先保存能力草稿后再执行测试。')
      return
    }
    try {
      const result = await testCapability(selectedGroupId, targetCapabilityCode, { testType: 'request' }, currentUserId)
      setLiveTestResult(result as unknown as Record<string, unknown>)
      setStatusMessage(result.success ? '能力测试通过。' : '能力测试失败。')
      await loadGroupDetail(selectedGroupId)
    } catch (testError) {
      setError(testError instanceof Error ? testError.message : '运行能力测试失败。')
    }
  }

  const handlePublishCapability = async (capabilityCode: string) => {
    if (selectedGroupId == null) return
    try {
      const item = items.find((current) => current.capabilityCode === capabilityCode)
      await publishCapability(selectedGroupId, capabilityCode, currentUserId)
      setStatusMessage(`已发布能力：${item?.capabilityName ?? capabilityCode}`)
      await loadGroupDetail(selectedGroupId)
    } catch (publishError) {
      setError(publishError instanceof Error ? publishError.message : '发布能力失败。')
    }
  }

  const handleDeleteCapability = async (capabilityCode: string) => {
    if (selectedGroupId == null) return
    const item = items.find((current) => current.capabilityCode === capabilityCode)
    if (!window.confirm(`确认删除能力 ${item?.capabilityName ?? capabilityCode} 吗？`)) return
    try {
      await deleteCapability(selectedGroupId, capabilityCode, currentUserId)
      setStatusMessage(`已删除能力：${item?.capabilityName ?? capabilityCode}`)
      await loadGroupDetail(selectedGroupId)
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : '删除能力失败。')
    }
  }

  const openCreateAuthDialog = () => {
    setAuthDialogMode('create')
    setAuthForm(defaultAuthForm())
    setAuthDialogOpen(true)
  }

  const openEditAuthDialog = (authConfig: AuthConfigSummary) => {
    setAuthDialogMode('edit')
    setAuthForm({
      id: authConfig.id,
      authName: authConfig.authName,
      authType: authConfig.authType,
      configJson: '',
    })
    setAuthDialogOpen(true)
  }

  const submitAuthForm = async () => {
    if (selectedGroupId == null) return
    setIsSavingAuth(true)
    setError(null)
    try {
      const payload: {
        id?: number
        authName: string
        authType: string
        config?: Record<string, unknown>
      } = {
        id: authForm.id,
        authName: authForm.authName.trim(),
        authType: authForm.authType,
      }
      if (authDialogMode === 'create' || authForm.configJson.trim()) {
        payload.config = parseJsonInput(authForm.configJson)
      }
      const saved = await saveCapabilityAuthConfig(
        selectedGroupId,
        payload,
        currentUserId
      )
      setAuthDialogOpen(false)
      setStatusMessage(`已保存认证配置：${saved.authName}`)
      await loadGroupDetail(selectedGroupId)
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存认证配置失败。')
    } finally {
      setIsSavingAuth(false)
    }
  }

  const authBindingHint =
    authConfigs.length === 0 ? '请先在当前能力组下创建认证配置。' : '每个能力都必须显式绑定一个已配置的认证。'
  const capabilityTestActionLabel =
    capabilityForm.capabilityType === 'API' ? '真实请求测试' : '配置校验'

  return (
    <section className="grid min-h-0 flex-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,7fr)]">
      <aside className="panel-card flex min-h-0 flex-col gap-4 p-4">
        <div className="flex items-center justify-between gap-3">
          <div>
            <div className="panel-title">能力组</div>
            <div className="text-sm text-slate-500">按业务场景维护 API 能力。</div>
          </div>
          <button className="prompt-primary" type="button" onClick={openCreateGroupDialog}>
            新增能力组
          </button>
        </div>
        {isLoadingGroups ? (
          <EmptyCard message="正在加载能力组..." />
        ) : groups.length === 0 ? (
          <EmptyCard message="暂无能力组。" />
        ) : (
          <div className="min-h-0 space-y-3 overflow-y-auto pr-1">
            {groups.map((group) => {
              const selected = group.id === selectedGroupId
              return (
                <button
                  key={group.id}
                  type="button"
                  onClick={() => setSelectedGroupId(group.id)}
                  className={`w-full rounded-2xl border px-4 py-4 text-left transition ${
                    selected ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-200 bg-white hover:border-slate-300'
                  }`}
                >
                  <div className="font-semibold">{group.groupName}</div>
                  {group.description && (
                    <div className={`mt-2 text-sm ${selected ? 'text-slate-200' : 'text-slate-500'}`}>{group.description}</div>
                  )}
                  <div className={`mt-3 text-xs ${selected ? 'text-slate-200' : 'text-slate-500'}`}>
                    {group.capabilityCount ?? 0} 项能力
                  </div>
                </button>
              )
            })}
          </div>
        )}
      </aside>

      <section className="panel-card flex min-h-0 flex-col gap-4 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="panel-title">能力中心</div>
            <div className="text-sm text-slate-500">
              {selectedGroup ? selectedGroup.groupName : '请选择能力组。'}
            </div>
            {selectedGroup?.description && <div className="mt-1 text-sm text-slate-500">{selectedGroup.description}</div>}
          </div>
          {selectedGroup && (
            <div className="flex flex-wrap gap-2">
              <button className="prompt-secondary" type="button" onClick={openEditGroupDialog}>
                编辑能力组
              </button>
              <button className="prompt-secondary" type="button" onClick={handleDeleteGroup}>
                删除能力组
              </button>
            </div>
          )}
        </div>

        {error && <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
        {statusMessage && <div className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">{statusMessage}</div>}

        {!selectedGroup ? (
          <EmptyCard message="请选择一个能力组后查看能力与认证配置。" />
        ) : (
          <>
            <div className="grid gap-3 md:grid-cols-3">
              <SummaryCard label="能力数量" value={items.length} />
              <SummaryCard label="认证配置" value={authConfigs.length} />
              <SummaryCard label="发布快照" value={snapshots.length} />
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className={`rounded-full border px-4 py-2 text-sm ${
                  activeTab === 'items' ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-200 bg-white text-slate-600'
                }`}
                onClick={() => setActiveTab('items')}
              >
                能力项
              </button>
              <button
                type="button"
                className={`rounded-full border px-4 py-2 text-sm ${
                  activeTab === 'auth' ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-200 bg-white text-slate-600'
                }`}
                onClick={() => setActiveTab('auth')}
              >
                认证配置
              </button>
              <button
                type="button"
                className={`rounded-full border px-4 py-2 text-sm ${
                  activeTab === 'snapshots' ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-200 bg-white text-slate-600'
                }`}
                onClick={() => setActiveTab('snapshots')}
              >
                发布快照
              </button>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto pr-1">
              {isLoadingDetail ? (
                <EmptyCard message="正在加载能力组详情..." />
              ) : activeTab === 'items' ? (
                <div className="space-y-4">
                  <div className="flex justify-between gap-3">
                    <div className="text-sm text-slate-500">{authBindingHint}</div>
                    <button className="prompt-primary" type="button" onClick={openCreateCapabilityDialog}>
                      新增能力
                    </button>
                  </div>
                  {items.length === 0 ? (
                    <EmptyCard message="当前能力组还没有能力。" />
                  ) : (
                    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
                      <table className="min-w-full divide-y divide-slate-200 text-sm text-slate-700">
                        <thead className="bg-slate-50 text-left text-xs uppercase tracking-[0.14em] text-slate-400">
                          <tr>
                            <th className="px-4 py-3">能力名称</th>
                            <th className="px-4 py-3">类型</th>
                            <th className="px-4 py-3">草稿版本</th>
                            <th className="px-4 py-3">发布版本</th>
                            <th className="px-4 py-3">最近测试</th>
                            <th className="px-4 py-3">操作</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                          {items.map((item) => (
                            <tr key={item.capabilityCode}>
                              <td className="px-4 py-4">
                                <div className="font-semibold text-slate-900">{item.capabilityName}</div>
                                {item.description && <div className="mt-1 text-xs text-slate-500">{item.description}</div>}
                              </td>
                              <td className="px-4 py-4">{item.capabilityType}</td>
                              <td className="px-4 py-4">{item.draftVersion ?? '未保存'}</td>
                              <td className="px-4 py-4">{item.publishedVersion ?? '未发布'}</td>
                              <td className="px-4 py-4">
                                <div>{displayTestStatus(item.lastTestStatus)}</div>
                                <div className="text-xs text-slate-500">{formatDateTime(item.lastTestTime ?? null)}</div>
                              </td>
                              <td className="px-4 py-4">
                                <div className="flex flex-wrap gap-2">
                                  <button className="prompt-secondary" type="button" onClick={() => void openEditCapabilityDialog(item)}>
                                    编辑
                                  </button>
                                  <button className="prompt-secondary" type="button" onClick={() => void handleRunCapabilityTest(item.capabilityCode)}>
                                    {getCapabilityTestActionLabel(item.capabilityType)}
                                  </button>
                                  <button className="prompt-secondary" type="button" onClick={() => void handlePublishCapability(item.capabilityCode)}>
                                    发布
                                  </button>
                                  <button className="prompt-secondary" type="button" onClick={() => void handleDeleteCapability(item.capabilityCode)}>
                                    删除
                                  </button>
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              ) : activeTab === 'auth' ? (
                <div className="space-y-4">
                  <div className="flex justify-end">
                    <button className="prompt-primary" type="button" onClick={openCreateAuthDialog}>
                      新增认证配置
                    </button>
                  </div>
                  {authConfigs.length === 0 ? (
                    <EmptyCard message="当前能力组还没有认证配置。" />
                  ) : (
                    <div className="grid gap-3 lg:grid-cols-2">
                      {authConfigs.map((authConfig) => (
                        <div key={authConfig.id} className="rounded-2xl border border-slate-200 bg-white p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className="font-semibold text-slate-900">{authConfig.authName}</div>
                              <div className="text-xs text-slate-500">{displayAuthType(authConfig.authType)}</div>
                              <div className="text-xs text-slate-500">{displayAuthScope(authConfig.scope)}</div>
                            </div>
                            <StatusPill label={displayAuthStatus(authConfig.status)} />
                          </div>
                          <div className="mt-3 rounded-xl border border-slate-100 bg-slate-50 px-3 py-3 text-xs text-slate-500">
                            {authConfig.maskedPreview ?? '已配置'}
                          </div>
                          <div className="mt-3 flex justify-end">
                            <button className="prompt-secondary" type="button" onClick={() => openEditAuthDialog(authConfig)}>
                              编辑认证
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <div className="space-y-4">
                  <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto]">
                    <textarea
                      id="snapshot-description"
                      value={snapshotDescription}
                      onChange={(event) => setSnapshotDescription(event.target.value)}
                      className="min-h-[96px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                    <button
                      className="prompt-primary self-start"
                      type="button"
                      onClick={() => void handlePublishSnapshot()}
                      disabled={isPublishingSnapshot}
                    >
                      {isPublishingSnapshot ? '发布中...' : '发布快照'}
                    </button>
                  </div>
                  {snapshots.length === 0 ? (
                    <EmptyCard message="当前能力组还没有发布快照。" />
                  ) : (
                    <div className="space-y-3">
                      {snapshots.map((snapshot) => (
                        <div key={`${snapshot.id}_${snapshot.snapshotVersion}`} className="rounded-2xl border border-slate-200 bg-white p-4">
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className="font-semibold text-slate-900">{snapshot.snapshotVersion}</div>
                              {snapshot.description && <div className="mt-1 text-xs text-slate-500">{snapshot.description}</div>}
                              <div className="mt-2 text-xs text-slate-500">{formatDateTime(snapshot.publishedAt ?? null)}</div>
                            </div>
                            <StatusPill label={snapshot.status || 'PUBLISHED'} />
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </section>

      {groupDialogOpen && (
        <ModalShell
          title={groupDialogMode === 'create' ? '新增能力组' : '编辑能力组'}
          onClose={() => setGroupDialogOpen(false)}
          actions={
            <>
              <button className="prompt-secondary" type="button" onClick={() => setGroupDialogOpen(false)}>
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void submitGroupForm()} disabled={isSavingGroup}>
                {isSavingGroup ? '保存中...' : '保存'}
              </button>
            </>
          }
        >
          <div className="grid gap-4">
            <Field label="能力组名称" inputId="group-name">
              <input
                id="group-name"
                value={groupForm.groupName}
                onChange={(event) => setGroupForm((current) => ({ ...current, groupName: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              />
            </Field>
            <Field label="描述" inputId="group-description">
              <textarea
                id="group-description"
                value={groupForm.description}
                onChange={(event) => setGroupForm((current) => ({ ...current, description: event.target.value }))}
                className="min-h-[88px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              />
            </Field>
          </div>
        </ModalShell>
      )}

      {capabilityDialogOpen && (
        <ModalShell
          title={capabilityDialogMode === 'create' ? '新增能力' : '编辑能力'}
          wide
          onClose={() => setCapabilityDialogOpen(false)}
          actions={
            <>
              <button className="prompt-secondary" type="button" onClick={() => void handleValidateCapability()}>
                校验配置
              </button>
              <button className="prompt-secondary" type="button" onClick={() => void handleRunCapabilityTest()}>
                {capabilityTestActionLabel}
              </button>
              <button className="prompt-secondary" type="button" onClick={() => setCapabilityDialogOpen(false)}>
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void submitCapabilityForm()} disabled={isSavingCapability}>
                {isSavingCapability ? '保存中...' : '保存草稿'}
              </button>
            </>
          }
        >
          <div className="grid gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
            <div className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                <Field label="能力编码" inputId="capability-code">
                  <input
                    id="capability-code"
                    value={capabilityForm.capabilityCode}
                    onChange={(event) => setCapabilityForm((current) => ({ ...current, capabilityCode: event.target.value }))}
                    disabled={capabilityDialogMode === 'edit'}
                    className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm disabled:bg-slate-50"
                  />
                </Field>
                <Field label="能力名称" inputId="capability-name">
                  <input
                    id="capability-name"
                    value={capabilityForm.capabilityName}
                    onChange={(event) => setCapabilityForm((current) => ({ ...current, capabilityName: event.target.value }))}
                    className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  />
                </Field>
                <Field label="能力类型" inputId="capability-type">
                  <select
                    id="capability-type"
                    value={capabilityForm.capabilityType}
                    onChange={(event) =>
                      setCapabilityForm((current) => ({
                        ...current,
                        capabilityType: event.target.value as CapabilityType,
                      }))
                    }
                    className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  >
                    <option value="API">API</option>
                    <option value="SKILL">SKILL</option>
                    <option value="MCP">MCP</option>
                  </select>
                </Field>
                <Field label="认证绑定" inputId="auth-binding" className="md:col-span-2">
                  <select
                    id="auth-binding"
                    value={capabilityForm.authConfigId}
                    onChange={(event) => setCapabilityForm((current) => ({ ...current, authConfigId: event.target.value }))}
                    className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  >
                    {authConfigs.map((authConfig) => (
                      <option key={authConfig.id} value={String(authConfig.id)}>
                        {authConfig.authName}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>

              {authConfigs.length === 0 && (
                <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700">
                  当前没有可用认证配置，请先在“认证配置”页签中创建。
                </div>
              )}

              <Field label="描述" inputId="capability-description">
                <textarea
                  id="capability-description"
                  value={capabilityForm.description}
                  onChange={(event) => setCapabilityForm((current) => ({ ...current, description: event.target.value }))}
                  className="min-h-[80px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                />
              </Field>

              {capabilityForm.capabilityType === 'API' ? (
                <div className="grid gap-4 md:grid-cols-2">
                  <Field label="URL" inputId="api-url">
                    <input
                      id="api-url"
                      value={capabilityForm.apiUrl}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, apiUrl: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="HTTP Method" inputId="api-method">
                    <select
                      id="api-method"
                      value={capabilityForm.apiMethod}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, apiMethod: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    >
                      <option value="GET">GET</option>
                      <option value="POST">POST</option>
                      <option value="PUT">PUT</option>
                      <option value="PATCH">PATCH</option>
                      <option value="DELETE">DELETE</option>
                    </select>
                  </Field>
                  <Field label="Headers" inputId="api-headers" className="md:col-span-2">
                    <textarea
                      id="api-headers"
                      value={capabilityForm.apiHeaders}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, apiHeaders: event.target.value }))}
                      className="min-h-[110px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                    />
                  </Field>
                </div>
              ) : capabilityForm.capabilityType === 'SKILL' ? (
                <div className="grid gap-4 md:grid-cols-2">
                  <Field label="Skill Name" inputId="skill-name">
                    <input
                      id="skill-name"
                      value={capabilityForm.skillName}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, skillName: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Skill Source" inputId="skill-source">
                    <input
                      id="skill-source"
                      value={capabilityForm.skillSource}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, skillSource: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Executor Type" inputId="executor-type">
                    <input
                      id="executor-type"
                      value={capabilityForm.executorType}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, executorType: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Endpoint" inputId="skill-endpoint">
                    <input
                      id="skill-endpoint"
                      value={capabilityForm.skillEndpoint}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, skillEndpoint: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Allowed Capabilities" inputId="allowed-capabilities" className="md:col-span-2">
                    <textarea
                      id="allowed-capabilities"
                      value={capabilityForm.allowedCapabilities}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, allowedCapabilities: event.target.value }))}
                      className="min-h-[110px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                    />
                  </Field>
                </div>
              ) : (
                <div className="grid gap-4 md:grid-cols-2">
                  <Field label="Server URL" inputId="mcp-server-url">
                    <input
                      id="mcp-server-url"
                      value={capabilityForm.mcpServerUrl}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, mcpServerUrl: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Protocol" inputId="mcp-protocol">
                    <input
                      id="mcp-protocol"
                      value={capabilityForm.mcpProtocol}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, mcpProtocol: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                  <Field label="Tool Name" inputId="mcp-tool-name" className="md:col-span-2">
                    <input
                      id="mcp-tool-name"
                      value={capabilityForm.mcpToolName}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, mcpToolName: event.target.value }))}
                      className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                    />
                  </Field>
                </div>
              )}
            </div>

            <div className="space-y-4">
              {capabilityForm.capabilityType === 'API' && (
                <>
                  <Field label="输入 Schema" inputId="input-schema">
                    <textarea
                      id="input-schema"
                      value={capabilityForm.inputSchema}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, inputSchema: event.target.value }))}
                      className="min-h-[160px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                    />
                  </Field>
                  <Field label="输出 Schema" inputId="output-schema">
                    <textarea
                      id="output-schema"
                      value={capabilityForm.outputSchema}
                      onChange={(event) => setCapabilityForm((current) => ({ ...current, outputSchema: event.target.value }))}
                      className="min-h-[160px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                    />
                  </Field>
                </>
              )}
              <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4">
                <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">校验结果</div>
                {validationIssues.length === 0 ? (
                  <div className="mt-3 text-sm text-slate-500">尚未执行校验，或当前配置已通过校验。</div>
                ) : (
                  <div className="mt-3 space-y-2">
                    {validationIssues.map((issue, index) => (
                      <div key={`${issue.field}_${index}`} className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-3 text-sm text-amber-700">
                        {(issue.field || '未知字段') + '：' + (issue.message || '未知错误')}
                      </div>
                    ))}
                  </div>
                )}
              </div>
              {liveTestResult && <JsonCard title="最近测试结果" value={JSON.stringify(liveTestResult, null, 2)} />}
            </div>
          </div>
        </ModalShell>
      )}

      {authDialogOpen && (
        <ModalShell
          title={authDialogMode === 'create' ? '新增认证配置' : '编辑认证配置'}
          onClose={() => setAuthDialogOpen(false)}
          actions={
            <>
              <button className="prompt-secondary" type="button" onClick={() => setAuthDialogOpen(false)}>
                取消
              </button>
              <button className="prompt-primary" type="button" onClick={() => void submitAuthForm()} disabled={isSavingAuth}>
                {isSavingAuth ? '保存中...' : '保存'}
              </button>
            </>
          }
        >
          <div className="space-y-4">
            <Field label="认证名称" inputId="auth-name">
              <input
                id="auth-name"
                value={authForm.authName}
                onChange={(event) => setAuthForm((current) => ({ ...current, authName: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              />
            </Field>
            <Field label="认证类型" inputId="auth-type">
              <select
                id="auth-type"
                value={authForm.authType}
                onChange={(event) => setAuthForm((current) => ({ ...current, authType: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              >
                {authTypeOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="配置 JSON" inputId="auth-config">
              {authDialogMode === 'edit' && (
                <div className="mb-2 text-xs text-slate-500">留空表示保留已有认证配置</div>
              )}
              <textarea
                id="auth-config"
                value={authForm.configJson}
                onChange={(event) => setAuthForm((current) => ({ ...current, configJson: event.target.value }))}
                className="min-h-[180px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
              />
            </Field>
          </div>
        </ModalShell>
      )}
    </section>
  )
}

function buildCapabilityFormFromVersion(
  version: CapabilityVersionSummary | undefined,
  item: CapabilityItemSummary,
  authConfigs: AuthConfigSummary[]
): CapabilityFormState {
  const definition = safeParseObject(version?.definitionJson)
  const selectedAuthId = version?.authConfigId ?? item.authConfigId ?? authConfigs[0]?.id
  return {
    capabilityCode: version?.capabilityCode ?? item.capabilityCode,
    capabilityType: version?.capabilityType ?? item.capabilityType,
    capabilityName: version?.capabilityName ?? item.capabilityName,
    description: version?.description ?? item.description ?? '',
    inputSchema: version?.inputSchema ?? '',
    outputSchema: version?.outputSchema ?? '',
    authConfigId: selectedAuthId ? String(selectedAuthId) : '',
    apiUrl: stringValue(definition.url),
    apiMethod: stringValue(definition.method) || 'GET',
    apiHeaders: JSON.stringify(asObject(definition.headers), null, 2),
    skillName: stringValue(definition.skill_name),
    skillSource: stringValue(definition.skill_source) || 'builtin',
    executorType: stringValue(definition.executor_type) || 'sync',
    skillEndpoint: stringValue(definition.endpoint),
    allowedCapabilities: stringArrayValue(definition.allowed_capabilities).join('\n'),
    mcpServerUrl: stringValue(definition.server_url),
    mcpProtocol: stringValue(definition.protocol) || 'sse',
    mcpToolName: stringValue(definition.tool_name),
  }
}

function buildCapabilityPayload(form: CapabilityFormState) {
  const definitionJson =
    form.capabilityType === 'API'
      ? JSON.stringify({
          url: form.apiUrl.trim(),
          method: form.apiMethod,
          headers: parseJsonInput(form.apiHeaders),
        })
      : form.capabilityType === 'SKILL'
        ? JSON.stringify({
            skill_name: form.skillName.trim(),
            skill_source: form.skillSource.trim() || 'builtin',
            executor_type: form.executorType.trim() || 'sync',
            endpoint: form.skillEndpoint.trim(),
            allowed_capabilities: parseStringList(form.allowedCapabilities),
          })
        : JSON.stringify({
            server_url: form.mcpServerUrl.trim(),
            protocol: form.mcpProtocol.trim() || 'sse',
            tool_name: form.mcpToolName.trim(),
          })
  return {
    capabilityCode: form.capabilityCode.trim() || undefined,
    capabilityName: form.capabilityName.trim(),
    capabilityType: form.capabilityType,
    description: form.description.trim() || undefined,
    inputSchema: form.capabilityType === 'API' ? form.inputSchema.trim() || undefined : undefined,
    outputSchema: form.capabilityType === 'API' ? form.outputSchema.trim() || undefined : undefined,
    definitionJson,
    authConfigId: form.authConfigId ? Number(form.authConfigId) : undefined,
  }
}

function parseJsonInput(value: string) {
  if (!value.trim()) {
    return {}
  }
  return JSON.parse(value)
}

function safeParseObject(value?: string | null) {
  if (!value) {
    return {}
  }
  try {
    return asObject(JSON.parse(value))
  } catch {
    return {}
  }
}

function asObject(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function stringArrayValue(value: unknown) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function parseStringList(value: string) {
  return value
    .split(/\r?\n|,/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function displayAuthType(type?: string | null) {
  const normalized = (type || '').toUpperCase()
  const option = authTypeOptions.find((item) => item.value === normalized)
  return option?.label ?? type ?? '未定义'
}

function displayAuthStatus(status?: string | null) {
  return (status || '').toUpperCase() === 'DISABLED' ? '已停用' : '生效中'
}

function displayAuthScope(scope?: string | null) {
  return (scope || '').toUpperCase() === 'CAPABILITY' ? '能力级' : '能力组级'
}

function getCapabilityTestActionLabel(capabilityType?: CapabilityType | string | null) {
  return (capabilityType || '').toUpperCase() === 'API' ? '真实请求测试' : '配置校验'
}

function displayTestStatus(status?: string | null) {
  switch ((status || '').toUpperCase()) {
    case 'SUCCESS':
      return '通过'
    case 'FAILED':
      return '失败'
    default:
      return '暂无记录'
  }
}

function formatDateTime(value?: string | null) {
  if (!value) return '--'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

const SummaryCard: React.FC<{ label: string; value: number }> = ({ label, value }) => (
  <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-4">
    <div className="text-[11px] uppercase tracking-[0.18em] text-slate-400">{label}</div>
    <div className="mt-2 text-2xl font-semibold text-slate-900">{value}</div>
  </div>
)

const EmptyCard: React.FC<{ message: string }> = ({ message }) => (
  <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-5 py-8 text-sm text-slate-500">{message}</div>
)

const StatusPill: React.FC<{ label: string }> = ({ label }) => (
  <div className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[11px] font-medium text-slate-500">{label}</div>
)

const ModalShell: React.FC<{
  title: string
  wide?: boolean
  onClose: () => void
  actions: React.ReactNode
  children: React.ReactNode
}> = ({ title, wide = false, onClose, actions, children }) => (
  <div className="form-overlay">
    <div className={`rounded-[24px] border border-slate-200 bg-white p-6 shadow-2xl ${wide ? 'w-[min(1120px,92vw)]' : 'w-[min(720px,92vw)]'}`}>
      <div className="mb-5 flex items-start justify-between gap-4">
        <div className="panel-title">{title}</div>
        <button type="button" className="text-sm text-slate-500 hover:text-slate-700" onClick={onClose}>
          关闭
        </button>
      </div>
      <div className="max-h-[70vh] overflow-y-auto pr-1">{children}</div>
      <div className="mt-5 flex flex-wrap justify-end gap-2">{actions}</div>
    </div>
  </div>
)

const Field: React.FC<{ label: string; inputId: string; children: React.ReactNode; className?: string }> = ({
  label,
  inputId,
  children,
  className,
}) => (
  <div className={className}>
    <label htmlFor={inputId} className="mb-2 block text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">
      {label}
    </label>
    {children}
  </div>
)

const JsonCard: React.FC<{ title: string; value?: string | null }> = ({ title, value }) => (
  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3">
    <div className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">{title}</div>
    <pre className="overflow-x-auto whitespace-pre-wrap break-all text-xs text-slate-600">{value || '{}'}</pre>
  </div>
)

export default CapabilityCenterPanel
