import React, { useEffect, useMemo, useState } from 'react'
import { deleteModelRecord, getModelRecord, getModelRecords, saveModelRecord, testModelRecordConnection } from '../services/api'
import type { ModelRecordConfig } from '../types'

interface ModelConfigPanelProps {
  currentUserId: string
}

type ModelFormState = {
  id?: number
  model_code: string
  custom_model_name: string
  provider: string
  model_name: string
  api_key: string
  base_url: string
  default_options: string
}

const providerOptions = ['openai', 'openai_compatible', 'doubao', 'gemini', 'claude', 'qwen', 'deepseek', 'custom']

const createEmptyForm = (): ModelFormState => ({
  model_code: '',
  custom_model_name: '',
  provider: 'openai',
  model_name: '',
  api_key: '',
  base_url: '',
  default_options: '',
})

const recordToForm = (record: ModelRecordConfig): ModelFormState => ({
  id: record.id,
  model_code: record.model_code,
  custom_model_name: record.custom_model_name,
  provider: record.provider,
  model_name: record.model_name,
  api_key: record.api_key || '',
  base_url: record.base_url,
  default_options: record.default_options && Object.keys(record.default_options).length > 0
    ? JSON.stringify(record.default_options, null, 2)
    : '',
})

const ModelConfigPanel: React.FC<ModelConfigPanelProps> = ({ currentUserId }) => {
  const [records, setRecords] = useState<ModelRecordConfig[]>([])
  const [form, setForm] = useState<ModelFormState>(createEmptyForm())
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [isLoading, setIsLoading] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [isTesting, setIsTesting] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)
  const [showApiKey, setShowApiKey] = useState(false)
  const pageSize = 20

  const totalPages = useMemo(() => Math.max(1, Math.ceil(total / pageSize)), [pageSize, total])

  const loadRecords = async () => {
    setIsLoading(true)
    try {
      const result = await getModelRecords({
        page,
        pageSize,
        keyword: keyword || undefined,
      })
      setRecords(result.items || [])
      setTotal(result.total || 0)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '加载模型列表失败')
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    void loadRecords()
  }, [page, keyword])

  const handleCreate = () => {
    setForm(createEmptyForm())
    setShowApiKey(false)
    setStatus('已切换到新建模型表单')
  }

  const handleSelect = async (record: ModelRecordConfig) => {
    setShowApiKey(false)
    try {
      const detail = await getModelRecord(record.model_code)
      setForm(recordToForm(detail))
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '加载模型详情失败')
    }
  }

  const isFormComplete = () =>
    Boolean(
      form.model_code.trim() &&
        form.custom_model_name.trim() &&
        form.provider.trim() &&
        form.model_name.trim() &&
        form.api_key.trim() &&
        form.base_url.trim()
    )

  const parseDefaultOptions = (): Record<string, unknown> | undefined => {
    const text = form.default_options.trim()
    if (!text) {
      return undefined
    }
    try {
      const parsed = JSON.parse(text) as unknown
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('请求参数 JSON 必须是对象')
      }
      return parsed as Record<string, unknown>
    } catch (error) {
      throw new Error(error instanceof Error ? error.message : '请求参数 JSON 格式不正确')
    }
  }

  const handleSave = async () => {
    if (!isFormComplete()) {
      setStatus('请完整填写模型编码、自定义模型名、供应商、Model 名称、API Key 和 Base URL')
      return
    }

    try {
      setIsSaving(true)
      const defaultOptions = parseDefaultOptions()
      const saved = await saveModelRecord(
        {
          model_code: form.model_code.trim(),
          custom_model_name: form.custom_model_name.trim(),
          provider: form.provider.trim(),
          model_name: form.model_name.trim(),
          api_key: form.api_key.trim(),
          base_url: form.base_url.trim(),
          ...(defaultOptions ? { default_options: defaultOptions } : {}),
        },
        currentUserId,
        typeof form.id === 'number' ? form.model_code.trim() : undefined
      )
      const created = typeof form.id !== 'number'
      setForm(recordToForm(saved))
      setStatus(created ? '已新建模型配置' : '已保存模型配置')
      if (created) {
        setKeyword('')
        setKeywordInput('')
        setPage(0)
      }
      await loadRecords()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '保存模型配置失败')
    } finally {
      setIsSaving(false)
    }
  }

  const handleDelete = async () => {
    if (typeof form.id !== 'number') {
      setStatus('请先从右侧列表中选择要删除的模型')
      return
    }
    try {
      setIsDeleting(true)
      await deleteModelRecord(form.model_code.trim(), currentUserId)
      setForm(createEmptyForm())
      setShowApiKey(false)
      setStatus('已删除模型配置')
      await loadRecords()
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '删除模型配置失败')
    } finally {
      setIsDeleting(false)
    }
  }

  const handleTest = async () => {
    if (!isFormComplete()) {
      setStatus('请完整填写模型编码、自定义模型名、供应商、Model 名称、API Key 和 Base URL')
      return
    }
    try {
      setIsTesting(true)
      const defaultOptions = parseDefaultOptions()
      const result = await testModelRecordConnection(
        {
          model_code: form.model_code.trim(),
          custom_model_name: form.custom_model_name.trim(),
          provider: form.provider.trim(),
          model_name: form.model_name.trim(),
          api_key: form.api_key.trim(),
          base_url: form.base_url.trim(),
          ...(defaultOptions ? { default_options: defaultOptions } : {}),
        },
        currentUserId
      )
      const answer = typeof result.answer === 'string' ? result.answer : '调用成功'
      setStatus(`测试返回：${answer}`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '测试调用失败')
    } finally {
      setIsTesting(false)
    }
  }

  const applySearch = () => {
    setPage(0)
    setKeyword(keywordInput.trim())
  }

  return (
    <div
      className="flex h-full min-h-0 w-full overflow-hidden rounded-[18px] border border-slate-200 bg-white shadow-[0_18px_40px_rgba(15,23,42,0.08)]"
      data-testid="model-config-layout"
    >
      <aside
        className="flex h-full min-h-0 flex-[0_0_30%] flex-col border-r border-slate-200 bg-[linear-gradient(180deg,rgba(248,250,252,0.96),rgba(255,255,255,0.98))]"
        data-testid="model-config-sidebar"
      >
        <div className="border-b border-slate-200 px-5 py-4">
          <div className="text-sm font-semibold text-slate-800">模型配置</div>
          <div className="mt-1 text-xs text-slate-500">在左侧维护模型基础信息，在右侧按列表查找和切换记录。</div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto px-5 py-5">
          <div className="mb-4 flex items-center justify-between gap-2">
            <button
              type="button"
              className="prompt-secondary"
              data-testid="model-config-create"
              onClick={handleCreate}
            >
              新建模型
            </button>
          </div>

          {status && (
            <div className="mb-4 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-3 text-sm text-slate-600">
              {status}
            </div>
          )}

          <div className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor="model-code" className="block text-sm font-medium text-slate-700">
                模型编码
              </label>
              <div className="text-xs text-slate-500">用于后台配置和工作流引用，创建后不可修改。</div>
              <input
                id="model-code"
                value={form.model_code}
                onChange={(event) => setForm((current) => ({ ...current, model_code: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                required
                disabled={typeof form.id === 'number'}
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="custom-model-name" className="block text-sm font-medium text-slate-700">
                自定义模型名
              </label>
              <div className="text-xs text-slate-500">用于页面识别的名称。</div>
              <input
                id="custom-model-name"
                value={form.custom_model_name}
                onChange={(event) => setForm((current) => ({ ...current, custom_model_name: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                required
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="provider" className="block text-sm font-medium text-slate-700">
                供应商
              </label>
              <div className="text-xs text-slate-500">模型服务提供方。</div>
              <select
                id="provider"
                value={form.provider}
                onChange={(event) => setForm((current) => ({ ...current, provider: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                required
              >
                {providerOptions.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="model-name" className="block text-sm font-medium text-slate-700">
                Model 名称（实际调用模型）
              </label>
              <div className="text-xs text-slate-500">发送给上游接口的模型名。</div>
              <input
                id="model-name"
                value={form.model_name}
                onChange={(event) => setForm((current) => ({ ...current, model_name: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                required
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="api-key" className="block text-sm font-medium text-slate-700">
                API Key（接口密钥）
              </label>
              <div className="text-xs text-slate-500">调用上游模型接口使用的密钥。</div>
              <div className="flex overflow-hidden rounded-xl border border-slate-200 bg-white">
                <input
                  id="api-key"
                  type={showApiKey ? 'text' : 'password'}
                  value={form.api_key}
                  onChange={(event) => setForm((current) => ({ ...current, api_key: event.target.value }))}
                  className="min-w-0 flex-1 border-0 px-3 py-2 text-sm outline-none"
                  required
                />
                <button
                  type="button"
                  className="flex w-10 items-center justify-center border-l border-slate-200 text-slate-500 hover:bg-slate-50"
                  aria-label={showApiKey ? '隐藏 API Key' : '显示 API Key'}
                  onClick={() => setShowApiKey((current) => !current)}
                >
                  {showApiKey ? (
                    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current stroke-2">
                      <path d="M3 3l18 18" />
                      <path d="M10.6 10.6a2 2 0 0 0 2.8 2.8" />
                      <path d="M9.9 4.2A9.7 9.7 0 0 1 12 4c5 0 8.5 4 10 8a15.8 15.8 0 0 1-3.1 4.7" />
                      <path d="M6.5 6.5A15.4 15.4 0 0 0 2 12c1.5 4 5 8 10 8a9.7 9.7 0 0 0 4.1-.9" />
                    </svg>
                  ) : (
                    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current stroke-2">
                      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
                      <circle cx="12" cy="12" r="3" />
                    </svg>
                  )}
                </button>
              </div>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="base-url" className="block text-sm font-medium text-slate-700">
                Base URL（接口地址）
              </label>
              <div className="text-xs text-slate-500">上游模型接口的根地址。</div>
              <input
                id="base-url"
                value={form.base_url}
                onChange={(event) => setForm((current) => ({ ...current, base_url: event.target.value }))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                required
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="default-options" className="block text-sm font-medium text-slate-700">
                请求参数 JSON
              </label>
              <div className="text-xs text-slate-500">透传给上游模型接口的额外 JSON 参数。</div>
              <textarea
                id="default-options"
                value={form.default_options}
                onChange={(event) => setForm((current) => ({ ...current, default_options: event.target.value }))}
                className="min-h-[96px] w-full resize-y rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
                placeholder='{"stream":true,"enable_thinking":true}'
              />
            </div>

            <div className="flex flex-wrap gap-2 pt-1">
              <button
                type="button"
                className="prompt-primary"
                data-testid="model-config-save"
                onClick={() => void handleSave()}
                disabled={isSaving}
              >
                {isSaving ? '保存中...' : '保存模型'}
              </button>
              <button
                type="button"
                className="prompt-secondary"
                data-testid="model-config-test-call"
                onClick={() => void handleTest()}
                disabled={isTesting}
              >
                {isTesting ? '测试中...' : '测试调用'}
              </button>
              <button
                type="button"
                className="prompt-secondary"
                onClick={() => void handleDelete()}
                disabled={isDeleting || typeof form.id !== 'number'}
              >
                {isDeleting ? '删除中...' : '删除模型'}
              </button>
            </div>
          </div>
        </div>
      </aside>

      <section className="flex h-full min-h-0 min-w-0 flex-[0_0_70%] flex-col bg-white" data-testid="model-config-list">
        <div className="border-b border-slate-200 px-5 py-4">
          <div className="text-sm font-semibold text-slate-800">模型记录列表</div>
          <div className="mt-1 text-xs text-slate-500">支持按模型编码、自定义模型名和 Model 名称模糊查找。</div>
          <div className="mt-4 flex flex-wrap gap-2">
            <input
              data-testid="model-config-search-input"
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              className="min-w-[240px] flex-1 rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="输入模型编码、自定义模型名或 Model 名称"
            />
            <button
              type="button"
              className="prompt-secondary"
              data-testid="model-config-search-apply"
              onClick={applySearch}
            >
              查找模型
            </button>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto">
          {isLoading && <div className="px-5 py-4 text-sm text-slate-500">模型列表加载中...</div>}
          {!isLoading && records.length === 0 && <div className="px-5 py-6 text-sm text-slate-500">暂无模型记录</div>}
          {!isLoading &&
            records.map((record) => (
              <button
                key={record.model_code}
                type="button"
                className="flex w-full items-start justify-between border-b border-slate-100 px-5 py-4 text-left transition hover:bg-slate-50"
                data-testid={`model-config-row-${record.model_code}`}
                onClick={() => void handleSelect(record)}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-slate-800">{record.custom_model_name}</div>
                  <div className="mt-1 truncate font-mono text-xs text-slate-500">{record.model_code}</div>
                  <div className="mt-1 truncate text-xs text-slate-500">
                    {record.provider} · {record.model_name}
                  </div>
                  <div className="mt-1 truncate text-xs text-slate-400">{record.base_url}</div>
                </div>
                {record.updated_at && <div className="ml-4 text-xs text-slate-400">{record.updated_at}</div>}
              </button>
            ))}
        </div>

        <div className="flex items-center justify-between border-t border-slate-200 px-5 py-4 text-xs text-slate-500">
          <div>
            第 {Math.min(page + 1, totalPages)} / {totalPages} 页，共 {total} 条
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="rounded-md border border-slate-200 px-3 py-1 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={page <= 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
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
  )
}

export default ModelConfigPanel
