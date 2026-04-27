import React, { useEffect, useMemo, useState } from 'react'
import {
  deleteModelProvider,
  deleteModelRecord,
  getModelProviders,
  getModelRecords,
  saveModelProvider,
  saveModelRecord,
  validateModelProviderDraft,
} from '../services/api'
import type { ModelProviderConfig, ModelRecordConfig } from '../types'

interface ModelConfigPanelProps {
  currentUserId: string
}

type ProviderFormState = {
  provider_code: string
  provider_name: string
  provider_type: string
  base_url: string
  api_key_secret_ref: string
  enabled: boolean
}

type ModelRecordFormState = {
  model_code: string
  model_name: string
  provider_code: string
  upstream_model_code: string
  capabilities_text: string
  default_system_prompt: string
  default_options_text: string
  enabled: boolean
}

type ModelEditorState = {
  mode: 'create' | 'edit'
  originalModelCode?: string
  form: ModelRecordFormState
}

type ModelFilters = {
  keyword: string
  providerCode: string
  enabled?: boolean
}

const providerTypeOptions = [
  'openai',
  'openai_compatible',
  'doubao',
  'gemini',
  'claude',
  'qwen',
  'deepseek',
  'custom',
]

const createProviderCode = (providerType: string) => `${providerType || 'provider'}-${Date.now().toString().slice(-6)}`

const createEmptyProviderForm = (): ProviderFormState => ({
  provider_code: createProviderCode('provider'),
  provider_name: '',
  provider_type: 'openai_compatible',
  base_url: '',
  api_key_secret_ref: '',
  enabled: true,
})

const providerToForm = (provider: ModelProviderConfig): ProviderFormState => ({
  provider_code: provider.provider_code,
  provider_name: provider.provider_name || '',
  provider_type: provider.provider_type || 'openai_compatible',
  base_url: provider.base_url || '',
  api_key_secret_ref: '',
  enabled: provider.enabled,
})

const normalizeCapabilities = (value: string) =>
  value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)

const parseJsonObject = (text: string): Record<string, unknown> | undefined => {
  if (!text.trim()) {
    return undefined
  }
  const parsed = JSON.parse(text)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('默认参数必须是 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

const modelToForm = (record: ModelRecordConfig): ModelRecordFormState => ({
  model_code: record.model_code,
  model_name: record.model_name,
  provider_code: record.provider_code,
  upstream_model_code: record.upstream_model_code,
  capabilities_text: (record.capabilities || []).join(','),
  default_system_prompt: record.default_system_prompt || '',
  default_options_text: record.default_options ? JSON.stringify(record.default_options, null, 2) : '',
  enabled: record.enabled,
})

const createEmptyModelForm = (providerCode: string): ModelRecordFormState => ({
  model_code: '',
  model_name: '',
  provider_code: providerCode,
  upstream_model_code: '',
  capabilities_text: '',
  default_system_prompt: '',
  default_options_text: '',
  enabled: true,
})

const ModelConfigPanel: React.FC<ModelConfigPanelProps> = ({ currentUserId }) => {
  const [providers, setProviders] = useState<ModelProviderConfig[]>([])
  const [providerForm, setProviderForm] = useState<ProviderFormState>(createEmptyProviderForm())
  const [selectedProviderCode, setSelectedProviderCode] = useState('')

  const [records, setRecords] = useState<ModelRecordConfig[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [pageSize] = useState(10)
  const [filters, setFilters] = useState<ModelFilters>({
    keyword: '',
    providerCode: '',
    enabled: undefined,
  })
  const [keywordInput, setKeywordInput] = useState('')
  const [providerFilterInput, setProviderFilterInput] = useState('')
  const [enabledFilterInput, setEnabledFilterInput] = useState<'all' | 'true' | 'false'>('all')

  const [modelEditor, setModelEditor] = useState<ModelEditorState | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [isLoadingProviders, setIsLoadingProviders] = useState(false)
  const [isLoadingRecords, setIsLoadingRecords] = useState(false)
  const [isSavingProvider, setIsSavingProvider] = useState(false)
  const [isSavingRecord, setIsSavingRecord] = useState(false)

  const totalPages = useMemo(() => Math.max(Math.ceil(total / pageSize), 1), [pageSize, total])

  const loadProviders = async () => {
    setIsLoadingProviders(true)
    try {
      const items = await getModelProviders()
      setProviders(items)
      if (items.length === 0) {
        setSelectedProviderCode('')
        setProviderForm(createEmptyProviderForm())
        return
      }

      const selected = items.find((item) => item.provider_code === selectedProviderCode) || items[0]
      setSelectedProviderCode(selected.provider_code)
      setProviderForm(providerToForm(selected))
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '加载服务商失败')
    } finally {
      setIsLoadingProviders(false)
    }
  }

  const loadModelRecords = async () => {
    setIsLoadingRecords(true)
    try {
      const result = await getModelRecords({
        page,
        pageSize,
        keyword: filters.keyword || undefined,
        providerCode: filters.providerCode || undefined,
        enabled: filters.enabled,
      })
      setRecords(result.items || [])
      setTotal(result.total || 0)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '加载模型记录失败')
    } finally {
      setIsLoadingRecords(false)
    }
  }

  useEffect(() => {
    void loadProviders()
  }, [])

  useEffect(() => {
    void loadModelRecords()
  }, [page, pageSize, filters])

  const handleProviderSelect = (providerCode: string) => {
    setSelectedProviderCode(providerCode)
    const selected = providers.find((item) => item.provider_code === providerCode)
    if (selected) {
      setProviderForm(providerToForm(selected))
    }
  }

  const handleCreateProvider = () => {
    const next = createEmptyProviderForm()
    setSelectedProviderCode('')
    setProviderForm(next)
    setStatus('已切换到新建服务商草稿')
  }

  const handleValidateProvider = async () => {
    try {
      const result = await validateModelProviderDraft(
        {
          provider_type: providerForm.provider_type.trim(),
          base_url: providerForm.base_url.trim(),
          api_key_secret_ref: providerForm.api_key_secret_ref.trim() || undefined,
          request_body: {
            model: 'connectivity-check',
            messages: [{ role: 'user', content: 'ping' }],
          },
        },
        currentUserId
      )
      setStatus(`服务商连通性验证通过：HTTP ${result.status_code || 200}`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '服务商连通性测试失败')
    }
  }

  const handleSaveProvider = async () => {
    try {
      setIsSavingProvider(true)
      const payload = {
        provider_code: providerForm.provider_code.trim(),
        provider_name: providerForm.provider_name.trim(),
        provider_type: providerForm.provider_type.trim(),
        base_url: providerForm.base_url.trim(),
        api_key_secret_ref: providerForm.api_key_secret_ref.trim() || undefined,
        enabled: providerForm.enabled,
      }
      if (!payload.provider_code || !payload.provider_name || !payload.provider_type || !payload.base_url) {
        setStatus('请完整填写服务商编码、名称、类型和地址')
        return
      }
      const saved = await saveModelProvider(payload, currentUserId, selectedProviderCode || undefined)
      setSelectedProviderCode(saved.provider_code)
      setProviderForm(providerToForm(saved))
      await loadProviders()
      setStatus('服务商已保存')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '保存服务商失败')
    } finally {
      setIsSavingProvider(false)
    }
  }

  const handleDeleteProvider = async () => {
    const providerCode = selectedProviderCode || providerForm.provider_code.trim()
    if (!providerCode) {
      setStatus('请先选择要删除的服务商')
      return
    }
    try {
      await deleteModelProvider(providerCode, currentUserId)
      setStatus('服务商已删除')
      await loadProviders()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '删除服务商失败')
    }
  }

  const openCreateModel = () => {
    const fallbackProviderCode = providerFilterInput || selectedProviderCode || providers[0]?.provider_code || ''
    setModelEditor({
      mode: 'create',
      form: createEmptyModelForm(fallbackProviderCode),
    })
  }

  const openEditModel = (record: ModelRecordConfig) => {
    setModelEditor({
      mode: 'edit',
      originalModelCode: record.model_code,
      form: modelToForm(record),
    })
  }

  const handleSaveModelRecord = async () => {
    if (!modelEditor) return
    const form = modelEditor.form
    if (!form.model_code.trim() || !form.model_name.trim() || !form.provider_code.trim() || !form.upstream_model_code.trim()) {
      setStatus('请完整填写模型编码、名称、服务商和上游模型编码')
      return
    }
    try {
      setIsSavingRecord(true)
      const payload = {
        model_code: form.model_code.trim(),
        model_name: form.model_name.trim(),
        provider_code: form.provider_code.trim(),
        upstream_model_code: form.upstream_model_code.trim(),
        capabilities: normalizeCapabilities(form.capabilities_text),
        default_system_prompt: form.default_system_prompt.trim() || undefined,
        default_options: parseJsonObject(form.default_options_text),
        enabled: form.enabled,
      }
      await saveModelRecord(payload, currentUserId, modelEditor.mode === 'edit' ? modelEditor.originalModelCode : undefined)
      setStatus(modelEditor.mode === 'edit' ? '模型记录已更新' : '模型记录已创建')
      setModelEditor(null)
      if (modelEditor.mode === 'create') {
        setPage(0)
      }
      await loadModelRecords()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '保存模型记录失败')
    } finally {
      setIsSavingRecord(false)
    }
  }

  const handleToggleModelEnabled = async (record: ModelRecordConfig) => {
    try {
      await saveModelRecord(
        {
          model_code: record.model_code,
          model_name: record.model_name,
          provider_code: record.provider_code,
          upstream_model_code: record.upstream_model_code,
          capabilities: record.capabilities || [],
          default_system_prompt: record.default_system_prompt || undefined,
          default_options: record.default_options || undefined,
          enabled: !record.enabled,
        },
        currentUserId,
        record.model_code
      )
      await loadModelRecords()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '切换模型启用状态失败')
    }
  }

  const handleDeleteModel = async (record: ModelRecordConfig) => {
    try {
      await deleteModelRecord(record.model_code, currentUserId)
      setStatus(`模型 ${record.model_code} 已删除`)
      await loadModelRecords()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '删除模型记录失败')
    }
  }

  const applyFilters = () => {
    setPage(0)
    setFilters({
      keyword: keywordInput.trim(),
      providerCode: providerFilterInput,
      enabled: enabledFilterInput === 'all' ? undefined : enabledFilterInput === 'true',
    })
  }

  return (
    <div className="panel-card h-full min-h-0 overflow-hidden p-0" data-testid="model-config-layout">
      <div className="flex h-full min-h-0">
        <section className="flex h-full min-h-0 w-[360px] flex-col border-r border-slate-200 bg-white" data-testid="model-provider-panel">
          <div className="border-b border-slate-200 px-4 py-3">
            <div className="panel-title">服务商配置</div>
            <div className="mt-1 text-xs text-slate-500">维护 provider_code / type / base_url / api_key_secret_ref</div>
          </div>
          <div className="min-h-0 flex-1 space-y-3 overflow-auto px-4 py-4">
            {status && <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">{status}</div>}
            <div className="grid gap-2">
              <select
                value={selectedProviderCode}
                onChange={(event) => handleProviderSelect(event.target.value)}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
              >
                {providers.length === 0 && <option value="">暂无服务商</option>}
                {providers.map((provider) => (
                  <option key={provider.provider_code} value={provider.provider_code}>
                    {provider.provider_name || provider.provider_code}
                  </option>
                ))}
              </select>
              <button type="button" className="prompt-secondary" onClick={handleCreateProvider}>
                新建服务商
              </button>
            </div>

            <input
              value={providerForm.provider_code}
              onChange={(event) => setProviderForm((prev) => ({ ...prev, provider_code: event.target.value }))}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="provider_code"
            />
            <input
              value={providerForm.provider_name}
              onChange={(event) => setProviderForm((prev) => ({ ...prev, provider_name: event.target.value }))}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="provider_name"
            />
            <select
              value={providerForm.provider_type}
              onChange={(event) => {
                const providerType = event.target.value
                setProviderForm((prev) => ({
                  ...prev,
                  provider_type: providerType,
                  provider_code: selectedProviderCode ? prev.provider_code : createProviderCode(providerType),
                }))
              }}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              {providerTypeOptions.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </select>
            <input
              value={providerForm.base_url}
              onChange={(event) => setProviderForm((prev) => ({ ...prev, base_url: event.target.value }))}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="base_url"
            />
            <input
              value={providerForm.api_key_secret_ref}
              onChange={(event) => setProviderForm((prev) => ({ ...prev, api_key_secret_ref: event.target.value }))}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="api_key_secret_ref (可选)"
              type="password"
            />
            <label className="flex items-center gap-2 text-sm text-slate-700">
              <input
                type="checkbox"
                checked={providerForm.enabled}
                onChange={(event) => setProviderForm((prev) => ({ ...prev, enabled: event.target.checked }))}
              />
              启用服务商
            </label>
            <div className="flex items-center gap-2">
              <button type="button" className="prompt-secondary" onClick={() => void handleValidateProvider()}>
                测试连通性
              </button>
              <button
                type="button"
                className="prompt-primary"
                onClick={() => void handleSaveProvider()}
                disabled={isSavingProvider}
              >
                {isSavingProvider ? '保存中...' : '保存服务商'}
              </button>
              <button type="button" className="prompt-secondary" onClick={() => void handleDeleteProvider()}>
                删除
              </button>
            </div>
            {isLoadingProviders && <div className="text-xs text-slate-500">服务商加载中...</div>}
          </div>
        </section>

        <section className="flex min-h-0 flex-1 flex-col bg-white">
          <div className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-4 py-3">
            <input
              data-testid="model-record-search-input"
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              className="min-w-[200px] flex-1 rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="搜索 model_code / model_name / upstream_model_code"
            />
            <select
              data-testid="model-record-provider-filter"
              value={providerFilterInput}
              onChange={(event) => setProviderFilterInput(event.target.value)}
              className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">全部服务商</option>
              {providers.map((provider) => (
                <option key={provider.provider_code} value={provider.provider_code}>
                  {provider.provider_name || provider.provider_code}
                </option>
              ))}
            </select>
            <select
              data-testid="model-record-enabled-filter"
              value={enabledFilterInput}
              onChange={(event) => setEnabledFilterInput(event.target.value as 'all' | 'true' | 'false')}
              className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="all">全部状态</option>
              <option value="true">仅启用</option>
              <option value="false">仅禁用</option>
            </select>
            <button data-testid="model-record-search-apply" type="button" className="prompt-secondary" onClick={applyFilters}>
              应用筛选
            </button>
            <button data-testid="model-record-create" type="button" className="prompt-primary" onClick={openCreateModel}>
              新建模型记录
            </button>
          </div>

          {modelEditor && (
            <div className="grid gap-2 border-b border-slate-200 bg-slate-50 px-4 py-3">
              <div className="text-xs font-semibold text-slate-500">{modelEditor.mode === 'edit' ? '编辑模型记录' : '新建模型记录'}</div>
              <div className="grid gap-2 md:grid-cols-2">
                <input
                  data-testid="model-record-form-model-code"
                  value={modelEditor.form.model_code}
                  onChange={(event) =>
                    setModelEditor((prev) =>
                      prev
                        ? { ...prev, form: { ...prev.form, model_code: event.target.value } }
                        : prev
                    )
                  }
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="model_code"
                />
                <input
                  data-testid="model-record-form-model-name"
                  value={modelEditor.form.model_name}
                  onChange={(event) =>
                    setModelEditor((prev) =>
                      prev
                        ? { ...prev, form: { ...prev.form, model_name: event.target.value } }
                        : prev
                    )
                  }
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="model_name"
                />
                <select
                  data-testid="model-record-form-provider-code"
                  value={modelEditor.form.provider_code}
                  onChange={(event) =>
                    setModelEditor((prev) =>
                      prev
                        ? { ...prev, form: { ...prev.form, provider_code: event.target.value } }
                        : prev
                    )
                  }
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                >
                  <option value="">选择 provider_code</option>
                  {providers.map((provider) => (
                    <option key={provider.provider_code} value={provider.provider_code}>
                      {provider.provider_name || provider.provider_code}
                    </option>
                  ))}
                </select>
                <input
                  data-testid="model-record-form-upstream-code"
                  value={modelEditor.form.upstream_model_code}
                  onChange={(event) =>
                    setModelEditor((prev) =>
                      prev
                        ? { ...prev, form: { ...prev.form, upstream_model_code: event.target.value } }
                        : prev
                    )
                  }
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="upstream_model_code"
                />
              </div>
              <input
                data-testid="model-record-form-capabilities"
                value={modelEditor.form.capabilities_text}
                onChange={(event) =>
                  setModelEditor((prev) =>
                    prev
                      ? { ...prev, form: { ...prev.form, capabilities_text: event.target.value } }
                      : prev
                  )
                }
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                placeholder="capabilities，逗号分隔，如 text,stream,json"
              />
              <textarea
                value={modelEditor.form.default_system_prompt}
                onChange={(event) =>
                  setModelEditor((prev) =>
                    prev
                      ? { ...prev, form: { ...prev.form, default_system_prompt: event.target.value } }
                      : prev
                  )
                }
                className="min-h-[72px] rounded-xl border border-slate-200 px-3 py-2 text-sm"
                placeholder="default_system_prompt（可选）"
              />
              <textarea
                value={modelEditor.form.default_options_text}
                onChange={(event) =>
                  setModelEditor((prev) =>
                    prev
                      ? { ...prev, form: { ...prev.form, default_options_text: event.target.value } }
                      : prev
                  )
                }
                className="min-h-[90px] rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                placeholder="default_options JSON（可选）"
              />
              <label className="flex items-center gap-2 text-sm text-slate-700">
                <input
                  type="checkbox"
                  checked={modelEditor.form.enabled}
                  onChange={(event) =>
                    setModelEditor((prev) =>
                      prev
                        ? { ...prev, form: { ...prev.form, enabled: event.target.checked } }
                        : prev
                    )
                  }
                />
                启用模型
              </label>
              <div className="flex items-center gap-2">
                <button
                  data-testid="model-record-form-save"
                  type="button"
                  className="prompt-primary"
                  onClick={() => void handleSaveModelRecord()}
                  disabled={isSavingRecord}
                >
                  {isSavingRecord ? '保存中...' : '保存模型记录'}
                </button>
                <button type="button" className="prompt-secondary" onClick={() => setModelEditor(null)}>
                  取消
                </button>
              </div>
            </div>
          )}

          <div className="min-h-0 flex-1 overflow-auto" data-testid="model-record-list">
            {isLoadingRecords && <div className="px-4 py-3 text-sm text-slate-500">模型记录加载中...</div>}
            {!isLoadingRecords && records.length === 0 && <div className="px-4 py-6 text-sm text-slate-500">暂无模型记录</div>}
            {!isLoadingRecords &&
              records.map((record) => (
                <button
                  key={record.model_code}
                  type="button"
                  data-testid={`model-record-row-${record.model_code}`}
                  className="flex w-full items-start justify-between border-b border-slate-100 px-4 py-3 text-left hover:bg-slate-50"
                  onClick={() => openEditModel(record)}
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm font-medium text-slate-800">{record.model_name}</span>
                      <span className="rounded-full border border-slate-200 px-2 py-0.5 text-[11px] text-slate-500">
                        {record.enabled ? '启用' : '禁用'}
                      </span>
                    </div>
                    <div className="mt-1 truncate text-xs text-slate-500">
                      {record.model_code} · {record.provider_code} · {record.upstream_model_code}
                    </div>
                  </div>
                  <div className="ml-3 flex items-center gap-2">
                    <button
                      type="button"
                      data-testid={`model-record-toggle-${record.model_code}`}
                      className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-700"
                      onClick={(event) => {
                        event.stopPropagation()
                        void handleToggleModelEnabled(record)
                      }}
                    >
                      {record.enabled ? '禁用' : '启用'}
                    </button>
                    <button
                      type="button"
                      data-testid={`model-record-delete-${record.model_code}`}
                      className="rounded-md border border-rose-200 px-2 py-1 text-xs text-rose-700"
                      onClick={(event) => {
                        event.stopPropagation()
                        void handleDeleteModel(record)
                      }}
                    >
                      删除
                    </button>
                  </div>
                </button>
              ))}
          </div>

          <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3 text-xs text-slate-600" data-testid="model-record-pagination">
            <div>
              第 {Math.min(page + 1, totalPages)} / {totalPages} 页，共 {total} 条
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                className="rounded-md border border-slate-200 px-3 py-1 disabled:cursor-not-allowed disabled:opacity-50"
                disabled={page <= 0}
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
              >
                上一页
              </button>
              <button
                type="button"
                className="rounded-md border border-slate-200 px-3 py-1 disabled:cursor-not-allowed disabled:opacity-50"
                disabled={(page + 1) * pageSize >= total}
                onClick={() => setPage((current) => current + 1)}
              >
                下一页
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  )
}

export default ModelConfigPanel
