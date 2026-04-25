import React, { useEffect, useMemo, useState } from 'react'
import {
  getModelProfiles,
  getModelProviders,
  saveModelProfile,
  saveModelProvider,
  testModelProfileChat,
  validateModelProvider,
  validateModelProviderDraft,
} from '../services/api'
import type { ModelProfileConfig, ModelProviderConfig, ProviderPreset } from '../types'

interface ModelConfigPanelProps {
  currentUserId: string
}

type ProviderFormState = {
  provider_code: string
  provider_name: string
  provider_type: string
  base_url: string
  default_model_code: string
  api_key_secret_ref: string
  enabled: boolean
}

type PurposeDef = {
  code: string
  label: string
  description: string
  defaultProfileCode: string
}

type PurposeFormState = {
  profile_code: string
  provider_code: string
  enabled: boolean
}

const providerPresets: ProviderPreset[] = [
  { value: 'openai', label: 'OpenAI', base_url: 'https://api.openai.com/v1', placeholder_model: 'gpt-4o-mini', protocol: 'openai' },
  { value: 'gemini', label: 'Gemini', base_url: 'https://generativelanguage.googleapis.com/v1beta', placeholder_model: 'gemini-1.5-flash', protocol: 'gemini' },
  { value: 'claude', label: 'Claude', base_url: 'https://api.anthropic.com/v1', placeholder_model: 'claude-3-5-sonnet-latest', protocol: 'claude' },
  { value: 'qwen', label: 'Qwen / 通义千问', base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', placeholder_model: 'qwen-plus', protocol: 'openai' },
  { value: 'deepseek', label: 'DeepSeek', base_url: 'https://api.deepseek.com/v1', placeholder_model: 'deepseek-chat', protocol: 'openai' },
  { value: 'doubao', label: '豆包', base_url: 'https://ark.cn-beijing.volces.com/api/v3', placeholder_model: 'doubao-seed-2-0-pro-260215', protocol: 'openai' },
  { value: 'custom', label: '自定义厂商', base_url: '', placeholder_model: 'custom-model', protocol: 'openai' },
]

const purposeDefs: PurposeDef[] = [
  { code: 'intent_routing', label: '意图识别', description: '用于工作流路由、意图识别。', defaultProfileCode: 'intent-router-v1' },
  { code: 'knowledge_query_rewrite', label: '知识检索改写', description: '用于知识库检索前的问题改写。', defaultProfileCode: 'knowledge-query-rewrite-v1' },
  { code: 'knowledge_answer', label: '知识答案', description: '用于知识库答案生成与总结。', defaultProfileCode: 'knowledge-answer-v1' },
  { code: 'general_llm', label: '通用对话', description: '用于普通对话、兜底回复。', defaultProfileCode: 'general-chat-v1' },
  { code: 'structured_extraction', label: '结构化抽取', description: '用于槽位提取、结构化解析。', defaultProfileCode: 'structured-extraction-v1' },
]

const providerPresetByType = (providerType: string) => providerPresets.find((item) => item.value === providerType)

const providerLabel = (providerName: string | undefined | null, providerType: string, defaultModelCode: string) => {
  const normalizedName = providerName?.trim()
  if (normalizedName) {
    return normalizedName
  }
  const preset = providerPresetByType(providerType)
  const vendor = preset?.label || providerType || '未命名厂商'
  const model = defaultModelCode.trim()
  return model ? `${vendor} / ${model}` : vendor
}

const defaultRequestBody = (providerType: string, modelCode: string) => {
  switch (providerType) {
    case 'doubao':
      return JSON.stringify(
        {
          model: modelCode,
          input: [
            {
              role: 'user',
              content: [
                {
                  type: 'input_image',
                  image_url: 'https://ark-project.tos-cn-beijing.volces.com/doc_image/ark_demo_img_1.png',
                },
                {
                  type: 'input_text',
                  text: '你看见了什么？',
                },
              ],
            },
          ],
        },
        null,
        2
      )
    case 'gemini':
      return JSON.stringify(
        {
          contents: [
            {
              role: 'user',
              parts: [{ text: '请回复：连接测试成功' }],
            },
          ],
        },
        null,
        2
      )
    case 'claude':
      return JSON.stringify(
        {
          model: modelCode,
          max_tokens: 32,
          messages: [{ role: 'user', content: '请回复：连接测试成功' }],
        },
        null,
        2
      )
    default:
      return JSON.stringify(
        {
          model: modelCode,
          messages: [{ role: 'user', content: '请回复：连接测试成功' }],
          max_tokens: 32,
        },
        null,
        2
      )
  }
}

const emptyProviderForm = (): ProviderFormState => ({
  provider_code: '',
  provider_name: 'OpenAI',
  provider_type: 'openai',
  base_url: 'https://api.openai.com/v1',
  default_model_code: 'gpt-4o-mini',
  api_key_secret_ref: '',
  enabled: true,
})

const providerToForm = (provider?: ModelProviderConfig): ProviderFormState => {
  if (!provider) return emptyProviderForm()
  return {
    provider_code: provider.provider_code,
    provider_name: provider.provider_name || '',
    provider_type: provider.provider_type,
    base_url: provider.base_url,
    default_model_code: provider.default_model_code,
    api_key_secret_ref: '',
    enabled: provider.enabled,
  }
}

const emptyPurposeForm = (purpose: PurposeDef, providerCode = ''): PurposeFormState => ({
  profile_code: purpose.defaultProfileCode,
  provider_code: providerCode,
  enabled: true,
})

const purposeFormFromProfile = (profile: ModelProfileConfig): PurposeFormState => ({
  profile_code: profile.profile_code,
  provider_code: profile.provider_code,
  enabled: profile.enabled,
})

const providerCodeFromType = (providerType: string) => `${providerType}-${Date.now().toString().slice(-6)}`

const ModelConfigPanel: React.FC<ModelConfigPanelProps> = ({ currentUserId }) => {
  const [providers, setProviders] = useState<ModelProviderConfig[]>([])
  const [profiles, setProfiles] = useState<ModelProfileConfig[]>([])
  const [selectedProviderCode, setSelectedProviderCode] = useState('')
  const [providerForm, setProviderForm] = useState<ProviderFormState>(emptyProviderForm())
  const [purposeForms, setPurposeForms] = useState<Record<string, PurposeFormState>>({})
  const [selectedPurposeCode, setSelectedPurposeCode] = useState(purposeDefs[0].code)
  const [requestBodyText, setRequestBodyText] = useState(defaultRequestBody('openai', 'gpt-4o-mini'))
  const [testMessage, setTestMessage] = useState('请用一句话回复：模型测试成功。')
  const [chatAnswer, setChatAnswer] = useState<Record<string, string>>({})
  const [status, setStatus] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isSavingProvider, setIsSavingProvider] = useState(false)
  const [isValidatingProvider, setIsValidatingProvider] = useState(false)
  const [lastValidatedProviderFingerprint, setLastValidatedProviderFingerprint] = useState('')

  const requiresAdmin = currentUserId !== 'demo-admin'
  const selectedProvider = useMemo(() => providers.find((item) => item.provider_code === selectedProviderCode) || null, [providers, selectedProviderCode])
  const selectedPurpose = useMemo(() => purposeDefs.find((item) => item.code === selectedPurposeCode) || purposeDefs[0], [selectedPurposeCode])
  const activePurposeForm = purposeForms[selectedPurpose.code] || emptyPurposeForm(selectedPurpose)
  const activePurposeProvider = providers.find((item) => item.provider_code === activePurposeForm.provider_code) || null

  const currentProviderFingerprint = useMemo(
    () =>
      JSON.stringify({
        provider_type: providerForm.provider_type.trim(),
        base_url: providerForm.base_url.trim(),
        default_model_code: providerForm.default_model_code.trim(),
        api_key_secret_ref: providerForm.api_key_secret_ref.trim() ? `provided:${providerForm.api_key_secret_ref.trim()}` : '',
        request_body: requestBodyText.trim(),
      }),
    [providerForm, requestBodyText]
  )

  const providerValidationPassed = lastValidatedProviderFingerprint !== '' && lastValidatedProviderFingerprint === currentProviderFingerprint

  const rebuildPurposeForms = (providerItems: ModelProviderConfig[], profileItems: ModelProfileConfig[]) => {
    const fallbackProviderCode = providerItems[0]?.provider_code || ''
    const nextForms: Record<string, PurposeFormState> = {}
    for (const purpose of purposeDefs) {
      const existing = profileItems.find((item) => item.purpose === purpose.code)
      nextForms[purpose.code] = existing ? purposeFormFromProfile(existing) : emptyPurposeForm(purpose, fallbackProviderCode)
    }
    return nextForms
  }

  const load = async () => {
    setIsLoading(true)
    try {
      const [providerItems, profileItems] = await Promise.all([getModelProviders(), getModelProfiles()])
      setProviders(providerItems)
      setProfiles(profileItems)
      const activeProvider = providerItems.find((item) => item.provider_code === selectedProviderCode) || providerItems[0]
      if (activeProvider) {
        setSelectedProviderCode(activeProvider.provider_code)
        setProviderForm(providerToForm(activeProvider))
        setRequestBodyText(defaultRequestBody(activeProvider.provider_type, activeProvider.default_model_code))
      } else {
        setSelectedProviderCode('')
        setProviderForm(emptyProviderForm())
        setRequestBodyText(defaultRequestBody('openai', 'gpt-4o-mini'))
      }
      setPurposeForms(rebuildPurposeForms(providerItems, profileItems))
      setLastValidatedProviderFingerprint('')
      setStatus('服务商配置负责厂商连接和默认模型，业务模型按用途绑定已配置服务商。')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '加载模型配置失败')
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const updateProviderForm = (patch: Partial<ProviderFormState>) => {
    setProviderForm((prev) => ({ ...prev, ...patch }))
  }

  const updatePurposeForm = (purposeCode: string, patch: Partial<PurposeFormState>) => {
    setPurposeForms((prev) => ({
      ...prev,
      [purposeCode]: {
        ...prev[purposeCode],
        ...patch,
      },
    }))
  }

  const parseJsonText = (text: string) => {
    if (!text.trim()) return undefined
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('请求体必须是 JSON 对象')
    }
    return parsed as Record<string, unknown>
  }

  const handleProviderTypeChange = (nextType: string) => {
    const preset = providerPresetByType(nextType)
    const nextModel = preset?.placeholder_model || 'gpt-4o-mini'
    setProviderForm((prev) => ({
      ...prev,
      provider_type: nextType,
      provider_name: prev.provider_name.trim() ? prev.provider_name : preset?.label || nextType,
      base_url: preset?.base_url || '',
      default_model_code: nextModel,
      provider_code: selectedProviderCode ? prev.provider_code : providerCodeFromType(nextType),
    }))
    setRequestBodyText(defaultRequestBody(nextType, nextModel))
  }

  const handleCreateProvider = () => {
    const preset = providerPresets[0]
    setSelectedProviderCode('')
    setProviderForm({
      provider_code: providerCodeFromType(preset.value),
      provider_name: preset.label,
      provider_type: preset.value,
      base_url: preset.base_url,
      default_model_code: preset.placeholder_model,
      api_key_secret_ref: '',
      enabled: true,
    })
    setRequestBodyText(defaultRequestBody(preset.value, preset.placeholder_model))
    setLastValidatedProviderFingerprint('')
    setStatus('已切换到新建服务商草稿，请先测试连通性，通过后才能保存。')
  }

  const handleValidateProvider = async () => {
    try {
      setIsValidatingProvider(true)
      const result = await validateModelProviderDraft(
        {
          provider_type: providerForm.provider_type.trim(),
          base_url: providerForm.base_url.trim(),
          default_model_code: providerForm.default_model_code.trim(),
          api_key_secret_ref: providerForm.api_key_secret_ref.trim() || undefined,
          request_body: parseJsonText(requestBodyText),
        },
        currentUserId
      )
      setLastValidatedProviderFingerprint(currentProviderFingerprint)
      setStatus(`HTTP ${result.status_code || 200}，模型 ${result.tested_model_code || providerForm.default_model_code.trim()}，${result.message}`)
    } catch (error) {
      setLastValidatedProviderFingerprint('')
      setStatus(error instanceof Error ? error.message : '连通性测试失败')
    } finally {
      setIsValidatingProvider(false)
    }
  }

  const handleSaveProvider = async () => {
    if (!providerValidationPassed) {
      setStatus('请先完成连通性测试并通过后，再保存服务商。')
      return
    }
    try {
      setIsSavingProvider(true)
      const draftProviderCode = providerForm.provider_code.trim() || providerCodeFromType(providerForm.provider_type.trim() || 'openai')
      const payload = {
        provider_code: draftProviderCode,
        provider_name: providerForm.provider_name.trim(),
        provider_type: providerForm.provider_type.trim(),
        base_url: providerForm.base_url.trim(),
        default_model_code: providerForm.default_model_code.trim(),
        api_key_secret_ref: providerForm.api_key_secret_ref.trim() || undefined,
        enabled: providerForm.enabled,
      }
      const saved = await saveModelProvider(payload, currentUserId, selectedProviderCode || undefined)
      await load()
      setSelectedProviderCode(saved.provider_code)
      setProviderForm(providerToForm(saved))
      setRequestBodyText(defaultRequestBody(saved.provider_type, saved.default_model_code))
      setLastValidatedProviderFingerprint('')
      setStatus('服务商已保存')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '保存服务商失败')
    } finally {
      setIsSavingProvider(false)
    }
  }

  const handleSavePurpose = async (purpose: PurposeDef) => {
    const form = purposeForms[purpose.code]
    const provider = providers.find((item) => item.provider_code === form.provider_code)
    if (!provider) {
      setStatus(`请先为${purpose.label}选择已配置服务商`)
      return
    }
    try {
      const existing = profiles.find((item) => item.profile_code === form.profile_code)
      await saveModelProfile(
        {
          profile_code: form.profile_code.trim(),
          provider_code: form.provider_code.trim(),
          purpose: purpose.code,
          response_format: {},
          enabled: form.enabled,
        },
        currentUserId,
        existing?.profile_code
      )
      await load()
      setStatus(`${purpose.label}配置已保存`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '保存业务模型配置失败')
    }
  }

  const handlePurposeValidate = async (purpose: PurposeDef) => {
    const form = purposeForms[purpose.code]
    const provider = providers.find((item) => item.provider_code === form.provider_code)
    if (!provider) {
      setStatus(`请先为${purpose.label}选择已配置服务商`)
      return
    }
    try {
      const result = await validateModelProvider(
        provider.provider_code,
        {
          purpose: purpose.code,
          model_code: provider.default_model_code,
        },
        currentUserId
      )
      setStatus(`HTTP ${result.status_code || 200}，模型 ${result.tested_model_code || provider.default_model_code}，${purpose.label}${result.message}`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '连通性测试失败')
    }
  }

  const handlePurposeChatTest = async (purpose: PurposeDef) => {
    const form = purposeForms[purpose.code]
    if (!form?.profile_code) {
      setStatus(`请先保存${purpose.label}配置，再测试会话`)
      return
    }
    try {
      const result = await testModelProfileChat(
        form.profile_code,
        {
          system_prompt: '你是一个简洁可靠的机器人助手。',
          message: testMessage.trim() || '请用一句话回复：模型测试成功。',
        },
        currentUserId
      )
      setChatAnswer((prev) => ({ ...prev, [purpose.code]: result.answer }))
      setStatus(`${purpose.label}会话测试成功`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '会话测试失败')
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">模型配置</div>
          <div className="text-xs text-slate-500">服务商配置包含厂商、接口地址、密钥和默认模型；业务模型按用途绑定已配置服务商</div>
        </div>
        <button className="text-xs text-slate-500 hover:text-slate-700" onClick={() => void load()}>
          刷新
        </button>
      </div>

      <div className="panel-body space-y-4">
        {isLoading && <div className="text-sm text-slate-500">加载中...</div>}
        {status && <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">{status}</div>}
        {requiresAdmin && <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">当前身份是 {currentUserId}。保存和测试接口需要“演示管理员”权限。</div>}

        <section className="space-y-3 rounded-xl border border-slate-200 bg-white/60 p-4">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="text-sm font-medium text-slate-800">服务商配置</div>
              <div className="text-xs text-slate-500">直接编辑当前服务商草稿，不再提供顶部服务商选择控件。</div>
            </div>
            <button className="rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-700" onClick={handleCreateProvider}>
              新建服务商
            </button>
          </div>

          <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
            当前草稿: {providerLabel(providerForm.provider_name, providerForm.provider_type, providerForm.default_model_code)}
          </div>

          <input
            value={providerForm.provider_name}
            onChange={(event) => updateProviderForm({ provider_name: event.target.value })}
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder="服务商名称，例如 豆包生产环境"
          />

          <select value={providerForm.provider_type} onChange={(event) => handleProviderTypeChange(event.target.value)} className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm">
            {providerPresets.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>

          <input value={providerForm.base_url} onChange={(event) => updateProviderForm({ base_url: event.target.value })} className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm" placeholder="接口地址" />
          <input
            value={providerForm.default_model_code}
            onChange={(event) => {
              updateProviderForm({ default_model_code: event.target.value })
              setRequestBodyText(defaultRequestBody(providerForm.provider_type, event.target.value))
            }}
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder="默认模型，例如 doubao-seed-2-0-pro-260215"
          />
          <input
            type="password"
            value={providerForm.api_key_secret_ref}
            onChange={(event) => updateProviderForm({ api_key_secret_ref: event.target.value })}
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder={selectedProvider?.api_key_configured ? selectedProvider.api_key_masked || '请输入可用访问密钥' : '请输入可用访问密钥'}
          />

          {selectedProvider?.api_key_error && !providerForm.api_key_secret_ref.trim() && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
              {selectedProvider.api_key_error}。请直接输入当前厂商可用的访问密钥。
            </div>
          )}

          <textarea value={requestBodyText} onChange={(event) => setRequestBodyText(event.target.value)} className="min-h-[180px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs" placeholder="连通性测试请求体 JSON" />

          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" checked={providerForm.enabled} onChange={(event) => updateProviderForm({ enabled: event.target.checked })} />
            启用服务商
          </label>

          {providerValidationPassed && <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs text-emerald-700">当前草稿已通过连通测试，可以保存。</div>}

          <div className="flex items-center gap-2">
            <button className="rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-700 disabled:cursor-not-allowed disabled:opacity-50" onClick={() => void handleValidateProvider()} disabled={isValidatingProvider}>
              {isValidatingProvider ? '测试中...' : '测试连通性'}
            </button>
            <button
              className="rounded-md bg-slate-900 px-3 py-2 text-xs text-white disabled:cursor-not-allowed disabled:opacity-50"
              onClick={() => void handleSaveProvider()}
              disabled={!providerValidationPassed || isSavingProvider || isValidatingProvider}
            >
              {isSavingProvider ? '保存中...' : '保存服务商'}
            </button>
          </div>
        </section>

        <section className="space-y-3 rounded-xl border border-slate-200 bg-white/60 p-4">
          <div>
            <div className="text-sm font-medium text-slate-800">业务模型配置</div>
            <div className="text-xs text-slate-500">按业务用途逐个选择已配置服务商，不再平铺所有用途。</div>
          </div>

          <select value={selectedPurposeCode} onChange={(event) => setSelectedPurposeCode(event.target.value)} className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm">
            {purposeDefs.map((purpose) => (
              <option key={purpose.code} value={purpose.code}>
                {purpose.label}
              </option>
            ))}
          </select>

          <textarea value={testMessage} onChange={(event) => setTestMessage(event.target.value)} className="min-h-[88px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm" placeholder="会话测试消息" />

          <div className="space-y-3 rounded-xl border border-slate-200 bg-slate-50/70 p-4">
            <div>
              <div className="text-sm font-medium text-slate-800">{selectedPurpose.label}</div>
              <div className="text-xs text-slate-500">{selectedPurpose.description}</div>
            </div>

            <input
              value={activePurposeForm.profile_code}
              onChange={(event) => updatePurposeForm(selectedPurpose.code, { profile_code: event.target.value })}
              className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
              placeholder="配置编码"
            />

            <select
              value={activePurposeForm.provider_code}
              onChange={(event) => updatePurposeForm(selectedPurpose.code, { provider_code: event.target.value })}
              className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm"
            >
              <option value="">选择已配置服务商</option>
              {providers.map((item) => (
                <option key={item.provider_code} value={item.provider_code}>
                  {providerLabel(item.provider_name, item.provider_type, item.default_model_code)}
                </option>
              ))}
            </select>

            <div className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">当前模型：{activePurposeProvider?.default_model_code || '未选择服务商'}</div>
            <div className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">采样参数、输出长度和超时阈值由后端按用途统一控制，前台不再编辑。</div>

            <label className="flex items-center gap-2 text-sm text-slate-700">
              <input type="checkbox" checked={activePurposeForm.enabled} onChange={(event) => updatePurposeForm(selectedPurpose.code, { enabled: event.target.checked })} />
              启用
            </label>

            <div className="flex flex-wrap items-center gap-2">
              <button className="rounded-md bg-slate-900 px-3 py-2 text-xs text-white" onClick={() => void handleSavePurpose(selectedPurpose)}>
                保存
              </button>
              <button className="rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-700" onClick={() => void handlePurposeValidate(selectedPurpose)}>
                连通测试
              </button>
              <button className="rounded-md border border-slate-200 px-3 py-2 text-xs text-slate-700" onClick={() => void handlePurposeChatTest(selectedPurpose)}>
                会话测试
              </button>
            </div>

            {chatAnswer[selectedPurpose.code] && <div className="whitespace-pre-wrap rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-700">{chatAnswer[selectedPurpose.code]}</div>}
          </div>
        </section>
      </div>
    </div>
  )
}

export default ModelConfigPanel
