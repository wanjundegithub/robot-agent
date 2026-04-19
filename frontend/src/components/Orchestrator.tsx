import { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import ReactFlow, {
  addEdge,
  Background,
  Controls,
  MiniMap,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type Node,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { publishWorkflow, saveWorkflowDraft, validateWorkflowDraft } from '../services/api'
import type { WorkflowEditorSelection, WorkflowValidationIssue } from '../types'

type DesignerNodeType = 'start' | 'coordinate' | 'sub_agent' | 'tool' | 'message' | 'end'
type VariableScope = 'global' | 'temp'
type VariableType =
  | 'string'
  | 'text'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'time'
  | 'enum'
  | 'array'
  | 'object'
  | 'json'
  | 'markdown'
  | 'file'
  | 'image'
  | 'any'

interface VariableDefinition {
  id: string
  name: string
  type: VariableType
  scope: VariableScope
  description: string
}

interface CanvasNodeData {
  label: string
  nodeType: DesignerNodeType
  config: Record<string, unknown>
}

interface WorkflowDraftPayload {
  workflowCode: string
  workflowVersion: string
  definition: Record<string, unknown>
  entryRule: Record<string, unknown>
  workflowConfig: Record<string, unknown>
}

interface WorkflowMetaState {
  workflowId: number | null
  workflowCode: string
  draftVersion: string
  publishedVersion: string | null
}

export interface WorkflowSummaryRule {
  label: string
  valid: boolean
}

export interface WorkflowSidebarState {
  workflowId: number | null
  workflowCode: string
  draftVersion: string
  publishedVersion: string | null
  workflowName: string
  saveStatus: string
  isSaving: boolean
  isPublishing: boolean
  validationIssues: WorkflowValidationIssue[]
  summaryRules: WorkflowSummaryRule[]
}

export interface WorkflowVersionMutation {
  workflowCode: string
  version: string
  action: 'save_draft' | 'publish' | 'rollback' | 'archive'
  refreshAt: number
}

export interface OrchestratorHandle {
  setWorkflowName: (name: string) => void
  validateDraft: () => Promise<void>
  saveDraft: () => Promise<void>
  publish: () => Promise<void>
}

interface OrchestratorProps {
  currentUserId: string
  editorSelection?: WorkflowEditorSelection | null
  onWorkflowDraftChange?: (draft: WorkflowDraftPayload) => void
  onWorkflowSidebarStateChange?: (state: WorkflowSidebarState) => void
  onWorkflowVersionMutation?: (mutation: WorkflowVersionMutation) => void
}

const DRAFT_VERSION = 'draft'

const variableTypeOptions: Array<{ value: VariableType; label: string }> = [
  { value: 'string', label: 'string' },
  { value: 'text', label: 'text' },
  { value: 'integer', label: 'integer' },
  { value: 'number', label: 'number' },
  { value: 'boolean', label: 'boolean' },
  { value: 'date', label: 'date' },
  { value: 'datetime', label: 'datetime' },
  { value: 'time', label: 'time' },
  { value: 'enum', label: 'enum' },
  { value: 'array', label: 'array' },
  { value: 'object', label: 'object' },
  { value: 'json', label: 'json' },
  { value: 'markdown', label: 'markdown' },
  { value: 'file', label: 'file' },
  { value: 'image', label: 'image' },
  { value: 'any', label: 'any' },
]

const initialNodes: Node<CanvasNodeData>[] = [
  {
    id: 'start',
    type: 'input',
    position: { x: 80, y: 120 },
    data: {
      label: '开始节点',
      nodeType: 'start',
      config: {
        prompt: '接收用户输入并初始化流程变量。',
        input_variable_ids: [],
      },
    },
  },
  {
    id: 'message_1',
    position: { x: 360, y: 120 },
    data: {
      label: '消息节点',
      nodeType: 'message',
      config: {
        message_text: '好的，我正在处理，请稍候。',
      },
    },
  },
  {
    id: 'end',
    type: 'output',
    position: { x: 640, y: 120 },
    data: {
      label: '结束节点',
      nodeType: 'end',
      config: {
        prompt: '输出流程最终结果。',
        output_variable_ids: [],
      },
    },
  },
]

const initialEdges: Edge[] = [
  { id: 'e_start_message', source: 'start', target: 'message_1' },
  { id: 'e_message_end', source: 'message_1', target: 'end' },
]

const nodeTemplates: Array<{ nodeType: DesignerNodeType; label: string; config: Record<string, unknown> }> = [
  {
    nodeType: 'start',
    label: '开始节点',
    config: {
      prompt: '定义流程启动时的输入变量。',
      input_variable_ids: [],
    },
  },
  {
    nodeType: 'coordinate',
    label: '协调节点',
    config: {
      prompt: '协调多个子步骤，决定后续处理路径。',
    },
  },
  {
    nodeType: 'sub_agent',
    label: '子代理节点',
    config: {
      prompt: '让子代理处理子任务，并返回后续节点需要的变量。',
    },
  },
  {
    nodeType: 'tool',
    label: '工具节点',
    config: {
      invoke_type: 'api',
      url: '',
      method: 'POST',
      payload_mapping: {},
    },
  },
  {
    nodeType: 'message',
    label: '消息节点',
    config: {
      message_text: '这里填写固定输出内容。',
    },
  },
  {
    nodeType: 'end',
    label: '结束节点',
    config: {
      prompt: '定义结束节点返回的输出变量。',
      output_variable_ids: [],
    },
  },
]

const emptyVariableForm = {
  name: '',
  type: 'string' as VariableType,
  scope: 'global' as VariableScope,
  description: '',
}

const Orchestrator = forwardRef<OrchestratorHandle, OrchestratorProps>(function Orchestrator(
  { currentUserId, editorSelection, onWorkflowDraftChange, onWorkflowSidebarStateChange, onWorkflowVersionMutation },
  ref
) {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [workflowName, setWorkflowName] = useState('')
  const [workflowMeta, setWorkflowMeta] = useState<WorkflowMetaState>({
    workflowId: null,
    workflowCode: '',
    draftVersion: DRAFT_VERSION,
    publishedVersion: null,
  })
  const [validationIssues, setValidationIssues] = useState<WorkflowValidationIssue[]>([])
  const [saveStatus, setSaveStatus] = useState('尚未保存')
  const [isSaving, setIsSaving] = useState(false)
  const [isPublishing, setIsPublishing] = useState(false)
  const [globalVariables, setGlobalVariables] = useState<VariableDefinition[]>([])
  const [tempVariables, setTempVariables] = useState<VariableDefinition[]>([])
  const [variableForm, setVariableForm] = useState(emptyVariableForm)

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedNodeData = (selectedNode?.data || null) as CanvasNodeData | null

  const allVariables = useMemo(() => [...globalVariables, ...tempVariables], [globalVariables, tempVariables])

  const variableNameMap = useMemo(() => new Map(allVariables.map((item) => [item.id, item])), [allVariables])

  const summaryRules = useMemo<WorkflowSummaryRule[]>(() => {
    const startCount = nodes.filter((node) => (node.data as CanvasNodeData).nodeType === 'start').length
    const endCount = nodes.filter((node) => (node.data as CanvasNodeData).nodeType === 'end').length

    return [
      { label: '仅保留一个开始节点', valid: startCount === 1 },
      { label: '仅保留一个结束节点', valid: endCount === 1 },
      { label: '已维护变量设置', valid: allVariables.length > 0 },
    ]
  }, [allVariables.length, nodes])

  const buildDefinition = () => {
    const nodeMap = Object.fromEntries(
      nodes.map((node) => {
        const data = node.data as CanvasNodeData
        return [
          node.id,
          {
            id: node.id,
            type: data.nodeType,
            name: data.label,
            config: normalizeNodeConfig(data.nodeType, data.config, variableNameMap),
          },
        ]
      })
    )

    const transitions: Record<string, unknown> = {}
    nodes.forEach((node) => {
      const outgoing = edges.filter((edge) => edge.source === node.id)
      transitions[node.id] = outgoing[0]?.target ?? null
    })

    return {
      workflow_code: workflowMeta.workflowCode,
      workflow_name: workflowName.trim(),
      workflow_version: workflowMeta.draftVersion,
      entry: nodes.find((node) => (node.data as CanvasNodeData).nodeType === 'start')?.id || 'start',
      nodes: nodeMap,
      transitions,
      config: {
        intent_profile_ref: 'intent-router-v1',
        llm_defaults: {
          model_profile_ref: 'general-chat-v1',
          provider_code: 'openai-compatible-prod',
        },
        variable_registry: {
          global: globalVariables,
          temporary: tempVariables,
        },
      },
    }
  }

  const currentDefinition = useMemo(
    () => buildDefinition(),
    [nodes, edges, workflowMeta.workflowCode, workflowMeta.draftVersion, workflowName, globalVariables, tempVariables]
  )

  const currentEntryRule = useMemo(
    () => ({
      intent_codes: ['general_agent_request'],
      keywords: workflowName.trim() ? [workflowName.trim()] : ['流程'],
      priority: 100,
    }),
    [workflowName]
  )

  useEffect(() => {
    onWorkflowDraftChange?.({
      workflowCode: workflowMeta.workflowCode,
      workflowVersion: workflowMeta.draftVersion,
      definition: currentDefinition,
      entryRule: currentEntryRule,
      workflowConfig: currentDefinition.config as Record<string, unknown>,
    })
  }, [onWorkflowDraftChange, workflowMeta, currentDefinition, currentEntryRule])

  useEffect(() => {
    onWorkflowSidebarStateChange?.({
      workflowId: workflowMeta.workflowId,
      workflowCode: workflowMeta.workflowCode,
      draftVersion: workflowMeta.draftVersion,
      publishedVersion: workflowMeta.publishedVersion,
      workflowName,
      saveStatus,
      isSaving,
      isPublishing,
      validationIssues,
      summaryRules,
    })
  }, [
    isPublishing,
    isSaving,
    onWorkflowSidebarStateChange,
    saveStatus,
    summaryRules,
    validationIssues,
    workflowMeta,
    workflowName,
  ])

  useEffect(() => {
    if (!editorSelection) return

    const hydrated = hydrateWorkflowSelection(editorSelection)
    setNodes(hydrated.nodes)
    setEdges(hydrated.edges)
    setSelectedNodeId(null)
    setWorkflowName(hydrated.workflowName)
    setWorkflowMeta({
      workflowId: hydrated.workflowId,
      workflowCode: hydrated.workflowCode,
      draftVersion: hydrated.draftVersion,
      publishedVersion: hydrated.publishedVersion,
    })
    setGlobalVariables(hydrated.globalVariables)
    setTempVariables(hydrated.tempVariables)
    setVariableForm(emptyVariableForm)
    setValidationIssues([])
    setSaveStatus(`已载入版本 ${editorSelection.version.version}`)
  }, [editorSelection, setEdges, setNodes])

  const ensureWorkflowBasics = () => {
    const trimmedName = workflowName.trim()
    if (!trimmedName) {
      setSaveStatus('请先填写流程名')
      return null
    }

    const workflowCode = workflowMeta.workflowCode || createWorkflowCode()
    if (workflowCode !== workflowMeta.workflowCode) {
      setWorkflowMeta((prev) => ({ ...prev, workflowCode }))
    }
    return {
      workflowCode,
      workflowName: trimmedName,
    }
  }

  const persistDraft = async (version: string) => {
    const basics = ensureWorkflowBasics()
    if (!basics) return null

    const definition = {
      ...currentDefinition,
      workflow_code: basics.workflowCode,
      workflow_name: basics.workflowName,
      workflow_version: version,
    }

    const response = await saveWorkflowDraft(basics.workflowCode, {
      workflowName: basics.workflowName,
      version,
      definition,
      entryRule: currentEntryRule,
      workflowConfig: definition.config as Record<string, unknown>,
      currentUserId,
    })

    setWorkflowMeta((prev) => ({
      workflowId: response.workflowId ?? prev.workflowId,
      workflowCode: response.workflowCode || basics.workflowCode,
      draftVersion: DRAFT_VERSION,
      publishedVersion: version === DRAFT_VERSION ? prev.publishedVersion : version,
    }))
    if (response.workflowName) {
      setWorkflowName(response.workflowName)
    }
    return {
      ...response,
      workflowCode: response.workflowCode || basics.workflowCode,
      version,
    }
  }

  const handleSaveDraft = async () => {
    setIsSaving(true)
    try {
      const response = await persistDraft(DRAFT_VERSION)
      if (!response) return
      setSaveStatus(response.workflowId ? `草稿已保存，流程 ID: ${response.workflowId}` : '草稿已保存')
      onWorkflowVersionMutation?.({
        workflowCode: response.workflowCode,
        version: response.version,
        action: 'save_draft',
        refreshAt: Date.now(),
      })
    } catch (error) {
      setSaveStatus(error instanceof Error ? `保存失败: ${error.message}` : '保存失败')
    } finally {
      setIsSaving(false)
    }
  }

  const handleValidateDraft = async () => {
    const basics = ensureWorkflowBasics()
    if (!basics) return

    try {
      const response = await validateWorkflowDraft(basics.workflowCode, {
        definition: {
          ...currentDefinition,
          workflow_code: basics.workflowCode,
          workflow_name: basics.workflowName,
        },
        workflowConfig: currentDefinition.config as Record<string, unknown>,
      })
      const issues = response.issues ?? []
      setValidationIssues(issues)
      setSaveStatus(issues.length === 0 ? '校验通过' : `发现 ${issues.length} 个问题`)
    } catch (error) {
      setValidationIssues([])
      setSaveStatus(error instanceof Error ? `校验失败: ${error.message}` : '校验失败')
    }
  }

  const handlePublish = async () => {
    const basics = ensureWorkflowBasics()
    if (!basics) return

    setIsPublishing(true)
    try {
      const validation = await validateWorkflowDraft(basics.workflowCode, {
        definition: {
          ...currentDefinition,
          workflow_code: basics.workflowCode,
          workflow_name: basics.workflowName,
        },
        workflowConfig: currentDefinition.config as Record<string, unknown>,
      })
      const issues = validation.issues ?? []
      setValidationIssues(issues)
      if (issues.length > 0) {
        setSaveStatus(`发布前校验失败，共 ${issues.length} 个问题`)
        return
      }

      const publishVersion = createPublishVersion()
      const saved = await persistDraft(publishVersion)
      if (!saved) return

      await publishWorkflow(saved.workflowCode, publishVersion, currentUserId)
      setWorkflowMeta((prev) => ({ ...prev, publishedVersion: publishVersion }))
      setSaveStatus(`已发布版本 ${publishVersion}`)
      onWorkflowVersionMutation?.({
        workflowCode: saved.workflowCode,
        version: publishVersion,
        action: 'publish',
        refreshAt: Date.now(),
      })
    } catch (error) {
      setSaveStatus(error instanceof Error ? `发布失败: ${error.message}` : '发布失败')
    } finally {
      setIsPublishing(false)
    }
  }

  const addNode = (nodeType: DesignerNodeType) => {
    const template = nodeTemplates.find((item) => item.nodeType === nodeType)
    if (!template) return
    const id = `${nodeType}_${Date.now()}`
    setNodes((prev) => [
      ...prev,
      {
        id,
        type: toFlowType(nodeType),
        position: { x: 140 + prev.length * 60, y: 180 + (prev.length % 3) * 110 },
        data: {
          label: template.label,
          nodeType: template.nodeType,
          config: structuredClone(template.config),
        },
      },
    ])
    setSelectedNodeId(id)
  }

  const onConnect = (connection: Connection) => {
    setEdges((prev) => addEdge({ ...connection, id: `edge_${Date.now()}` }, prev))
  }

  const updateSelectedNodeLabel = (label: string) => {
    if (!selectedNodeId) return
    setNodes((prev) =>
      prev.map((node) =>
        node.id === selectedNodeId
          ? { ...node, data: { ...(node.data as CanvasNodeData), label } }
          : node
      )
    )
  }

  const replaceSelectedConfig = (nextConfig: Record<string, unknown>) => {
    if (!selectedNodeId) return
    setNodes((prev) =>
      prev.map((node) =>
        node.id === selectedNodeId
          ? { ...node, data: { ...(node.data as CanvasNodeData), config: nextConfig } }
          : node
      )
    )
  }

  const updateSelectedConfigField = (field: string, value: unknown) => {
    if (!selectedNodeData) return
    const nextConfig = structuredClone(selectedNodeData.config || {})
    assignPath(nextConfig, field, value)
    replaceSelectedConfig(nextConfig)
  }

  const toggleSelectedVariable = (field: 'input_variable_ids' | 'output_variable_ids', variableId: string) => {
    if (!selectedNodeData) return
    const current = Array.isArray(selectedNodeData.config[field]) ? [...(selectedNodeData.config[field] as string[])] : []
    const next = current.includes(variableId)
      ? current.filter((item) => item !== variableId)
      : [...current, variableId]
    updateSelectedConfigField(field, next)
  }

  const removeSelectedNode = () => {
    if (!selectedNodeId) return
    setNodes((prev) => prev.filter((node) => node.id !== selectedNodeId))
    setEdges((prev) => prev.filter((edge) => edge.source !== selectedNodeId && edge.target !== selectedNodeId))
    setSelectedNodeId(null)
  }

  const addVariable = () => {
    const name = variableForm.name.trim()
    if (!name) return
    const nextVariable: VariableDefinition = {
      id: `${variableForm.scope}_${Date.now()}`,
      name,
      type: variableForm.type,
      scope: variableForm.scope,
      description: variableForm.description.trim(),
    }

    if (variableForm.scope === 'global') {
      setGlobalVariables((prev) => [...prev, nextVariable])
    } else {
      setTempVariables((prev) => [...prev, nextVariable])
    }
    setVariableForm(emptyVariableForm)
  }

  const updateVariable = (
    scope: VariableScope,
    variableId: string,
    field: 'name' | 'type' | 'description',
    value: string
  ) => {
    const setter = scope === 'global' ? setGlobalVariables : setTempVariables
    setter((prev) =>
      prev.map((item) =>
        item.id === variableId
          ? { ...item, [field]: field === 'type' ? (value as VariableType) : value }
          : item
      )
    )
  }

  const removeVariable = (scope: VariableScope, variableId: string) => {
    const setter = scope === 'global' ? setGlobalVariables : setTempVariables
    setter((prev) => prev.filter((item) => item.id !== variableId))
    setNodes((prev) =>
      prev.map((node) => {
        const data = node.data as CanvasNodeData
        const nextConfig = structuredClone(data.config || {})
        for (const key of ['input_variable_ids', 'output_variable_ids']) {
          if (Array.isArray(nextConfig[key])) {
            nextConfig[key] = (nextConfig[key] as string[]).filter((item) => item !== variableId)
          }
        }
        return { ...node, data: { ...data, config: nextConfig } }
      })
    )
  }

  const renderVariableListSelector = (field: 'input_variable_ids' | 'output_variable_ids') => {
    const selectedIds = Array.isArray(selectedNodeData?.config[field]) ? (selectedNodeData?.config[field] as string[]) : []

    return (
      <div className="space-y-2">
        {allVariables.length === 0 && <div className="text-xs text-slate-500">请先在右下角维护变量设置。</div>}
        {allVariables.map((variable) => (
          <label key={variable.id} className="flex items-start justify-between gap-3 rounded-lg border border-slate-200 px-3 py-2 text-sm">
            <span className="min-w-0">
              <span className="block font-medium text-slate-700">{variable.name}</span>
              <span className="block text-xs text-slate-400">
                {variable.scope === 'global' ? '全局' : '临时'} · {variable.type}
              </span>
              {variable.description && <span className="mt-1 block text-xs text-slate-500">{variable.description}</span>}
            </span>
            <input
              type="checkbox"
              checked={selectedIds.includes(variable.id)}
              onChange={() => toggleSelectedVariable(field, variable.id)}
            />
          </label>
        ))}
      </div>
    )
  }

  const renderNodeEditor = () => {
    if (!selectedNodeData) {
      return <div className="text-sm text-slate-500">选择一个节点后，在这里编辑节点配置。</div>
    }

    const config = selectedNodeData.config || {}
    const nodeType = selectedNodeData.nodeType

    return (
      <div className="space-y-3">
        <input
          value={selectedNodeData.label}
          onChange={(event) => updateSelectedNodeLabel(event.target.value)}
          className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
          placeholder="节点名称"
        />
        <div className="text-xs text-slate-400">节点类型: {displayNodeType(nodeType)}</div>

        {(nodeType === 'start' || nodeType === 'coordinate' || nodeType === 'sub_agent' || nodeType === 'end') && (
          <textarea
            value={String(config.prompt || '')}
            onChange={(event) => updateSelectedConfigField('prompt', event.target.value)}
            className="min-h-[100px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder="请输入提示词"
          />
        )}

        {nodeType === 'start' && (
          <div>
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">输入变量</div>
            {renderVariableListSelector('input_variable_ids')}
          </div>
        )}

        {nodeType === 'message' && (
          <textarea
            value={String(config.message_text || '')}
            onChange={(event) => updateSelectedConfigField('message_text', event.target.value)}
            className="min-h-[100px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder="请输入固定输出话术"
          />
        )}

        {nodeType === 'tool' && (
          <>
            <select
              value={String(config.invoke_type || 'api')}
              onChange={(event) => updateSelectedConfigField('invoke_type', event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="function">函数调用</option>
              <option value="api">API 调用</option>
              <option value="mcp">MCP 调用</option>
              <option value="skill">Skill 调用</option>
            </select>

            {String(config.invoke_type || 'api') === 'function' && (
              <input
                value={String(config.function_name || '')}
                onChange={(event) => updateSelectedConfigField('function_name', event.target.value)}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                placeholder="函数名称"
              />
            )}

            {String(config.invoke_type || 'api') === 'api' && (
              <>
                <input
                  value={String(config.url || '')}
                  onChange={(event) => updateSelectedConfigField('url', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="API 地址"
                />
                <select
                  value={String(config.method || 'POST')}
                  onChange={(event) => updateSelectedConfigField('method', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                >
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="PATCH">PATCH</option>
                </select>
              </>
            )}

            {String(config.invoke_type || 'api') === 'mcp' && (
              <>
                <input
                  value={String(config.mcp_endpoint || '')}
                  onChange={(event) => updateSelectedConfigField('mcp_endpoint', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="MCP 服务地址"
                />
                <input
                  value={String(config.tool_name || '')}
                  onChange={(event) => updateSelectedConfigField('tool_name', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="MCP 工具名"
                />
              </>
            )}

            {String(config.invoke_type || 'api') === 'skill' && (
              <>
                <input
                  value={String(config.skill_endpoint || '')}
                  onChange={(event) => updateSelectedConfigField('skill_endpoint', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="Skill 地址"
                />
                <input
                  value={String(config.skill_name || '')}
                  onChange={(event) => updateSelectedConfigField('skill_name', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="Skill 名称"
                />
              </>
            )}

            <textarea
              value={formatObject(config.payload_mapping)}
              onChange={(event) => updateJsonConfigField('payload_mapping', event.target.value, config, replaceSelectedConfig)}
              className="min-h-[120px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
              placeholder='工具参数映射，例如 {"user_message":"execution.user_message"}'
            />
          </>
        )}

        {nodeType === 'end' && (
          <div>
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">输出变量</div>
            {renderVariableListSelector('output_variable_ids')}
          </div>
        )}

        {!['start', 'end'].includes(nodeType) && (
          <button className="prompt-secondary w-full" type="button" onClick={removeSelectedNode}>
            删除当前节点
          </button>
        )}
      </div>
    )
  }

  const renderVariableManager = (scope: VariableScope, items: VariableDefinition[]) => (
    <div className="space-y-2">
      {items.length === 0 && <div className="text-xs text-slate-500">{scope === 'global' ? '暂无全局变量' : '暂无临时变量'}</div>}
      {items.map((item) => (
        <div key={item.id} className="space-y-2 rounded-lg border border-slate-200 bg-white px-3 py-3">
          <div className="grid gap-2 md:grid-cols-[1fr_140px_88px]">
            <input
              value={item.name}
              onChange={(event) => updateVariable(scope, item.id, 'name', event.target.value)}
              className="rounded-lg border border-slate-200 px-3 py-2 text-sm"
              placeholder="变量名"
            />
            <select
              value={item.type}
              onChange={(event) => updateVariable(scope, item.id, 'type', event.target.value)}
              className="rounded-lg border border-slate-200 px-3 py-2 text-sm"
            >
              {variableTypeOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <button
              className="rounded-lg border border-red-200 px-3 py-2 text-xs text-red-600"
              onClick={() => removeVariable(scope, item.id)}
              type="button"
            >
              删除
            </button>
          </div>
          <textarea
            value={item.description}
            onChange={(event) => updateVariable(scope, item.id, 'description', event.target.value)}
            className="min-h-[72px] w-full rounded-lg border border-slate-200 px-3 py-2 text-sm"
            placeholder="变量描述，供大模型理解变量含义和使用方式"
          />
        </div>
      ))}
    </div>
  )

  useImperativeHandle(
    ref,
    () => ({
      setWorkflowName,
      validateDraft: handleValidateDraft,
      saveDraft: handleSaveDraft,
      publish: handlePublish,
    }),
    [handlePublish, handleSaveDraft, handleValidateDraft]
  )

  return (
    <div className="panel-card h-full">
      <div className="grid h-full gap-4 lg:grid-cols-[minmax(0,2.5fr)_360px]">
        <div className="flex min-h-0 flex-col gap-4">
          <div className="panel-header mb-0">
            <div>
              <div className="panel-title">流程画布</div>
              <div className="text-xs text-slate-500">{workflowName.trim() || '未命名流程'}</div>
            </div>
            <div className="text-xs text-slate-400">{saveStatus}</div>
          </div>

          <div className="flex flex-wrap gap-2">
            {nodeTemplates.map((template) => (
              <button
                key={template.nodeType}
                className="prompt-secondary"
                onClick={() => addNode(template.nodeType)}
                type="button"
              >
                + {template.label}
              </button>
            ))}
          </div>

          <div className="panel-body min-h-[760px] rounded-xl border border-slate-200 bg-white">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              fitView
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              onNodeClick={(_, node) => setSelectedNodeId(node.id)}
              onPaneClick={() => setSelectedNodeId(null)}
              panOnScroll
            >
              <Background gap={12} size={1} />
              <MiniMap pannable zoomable />
              <Controls />
            </ReactFlow>
          </div>
        </div>

        <div className="grid min-h-0 gap-4 lg:grid-rows-[minmax(0,1fr)_minmax(0,1fr)]">
          <div className="min-h-0 rounded-xl border border-slate-200 bg-white p-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">节点编辑</div>
            <div className="max-h-full overflow-auto pr-1">{renderNodeEditor()}</div>
          </div>

          <div className="min-h-0 rounded-xl border border-slate-200 bg-slate-50/80 p-4">
            <div className="mb-3 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">变量设置</div>
            <div className="mb-3 grid gap-2">
              <input
                value={variableForm.name}
                onChange={(event) => setVariableForm((prev) => ({ ...prev, name: event.target.value }))}
                className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                placeholder="变量名"
              />
              <div className="grid gap-2 sm:grid-cols-[1fr_120px]">
                <select
                  value={variableForm.type}
                  onChange={(event) => setVariableForm((prev) => ({ ...prev, type: event.target.value as VariableType }))}
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                >
                  {variableTypeOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <select
                  value={variableForm.scope}
                  onChange={(event) => setVariableForm((prev) => ({ ...prev, scope: event.target.value as VariableScope }))}
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                >
                  <option value="global">全局变量</option>
                  <option value="temp">临时变量</option>
                </select>
              </div>
              <textarea
                value={variableForm.description}
                onChange={(event) => setVariableForm((prev) => ({ ...prev, description: event.target.value }))}
                className="min-h-[80px] rounded-xl border border-slate-200 px-3 py-2 text-sm"
                placeholder="变量描述，供大模型理解变量的语义、格式和使用限制"
              />
              <button className="prompt-primary" type="button" onClick={addVariable}>
                新增变量
              </button>
            </div>

            <div className="grid max-h-[420px] gap-4 overflow-auto xl:grid-cols-2">
              <div className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">全局变量</div>
                {renderVariableManager('global', globalVariables)}
              </div>
              <div className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">临时变量</div>
                {renderVariableManager('temp', tempVariables)}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
})

function toFlowType(nodeType: DesignerNodeType): 'input' | 'default' | 'output' {
  if (nodeType === 'start') return 'input'
  if (nodeType === 'end') return 'output'
  return 'default'
}

function normalizeNodeConfig(
  nodeType: DesignerNodeType,
  config: Record<string, unknown>,
  variableNameMap: Map<string, VariableDefinition>
) {
  switch (nodeType) {
    case 'start':
      return {
        prompt: String(config.prompt || ''),
        initial_variables: mapVariableIdsToObject(config.input_variable_ids, variableNameMap, '', true),
      }
    case 'coordinate':
    case 'sub_agent':
      return {
        prompt: String(config.prompt || ''),
        user_prompt: String(config.prompt || ''),
      }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'tool': {
      const invokeType = String(config.invoke_type || 'api')
      const base: Record<string, unknown> = {
        invoke_type: invokeType,
        payload_mapping: ensureObject(config.payload_mapping),
      }
      if (invokeType === 'function') {
        base.function_name = String(config.function_name || '')
        base.tool_code = String(config.function_name || '')
      } else if (invokeType === 'api') {
        base.url = String(config.url || '')
        base.method = String(config.method || 'POST')
      } else if (invokeType === 'mcp') {
        base.mcp_endpoint = String(config.mcp_endpoint || '')
        base.tool_name = String(config.tool_name || '')
        base.tool_code = String(config.tool_name || '')
      } else if (invokeType === 'skill') {
        base.skill_endpoint = String(config.skill_endpoint || '')
        base.skill_name = String(config.skill_name || '')
        base.tool_code = String(config.skill_name || '')
      }
      return base
    }
    case 'end':
      return {
        prompt: String(config.prompt || ''),
        output_format: mapVariableIdsToObject(config.output_variable_ids, variableNameMap, 'execution', false),
      }
    default:
      return config
  }
}

function mapVariableIdsToObject(
  source: unknown,
  variableNameMap: Map<string, VariableDefinition>,
  prefix: string,
  useEmptyDefault: boolean
) {
  const ids = Array.isArray(source) ? (source as string[]) : []
  const entries = ids
    .map((id) => variableNameMap.get(id))
    .filter((item): item is VariableDefinition => Boolean(item))
    .map((item) => [
      item.name,
      prefix ? `$${prefix}.${item.name}` : (useEmptyDefault ? '' : item.name),
    ])
  return Object.fromEntries(entries)
}

function ensureObject(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function formatObject(value: unknown) {
  return JSON.stringify(ensureObject(value), null, 2)
}

function updateJsonConfigField(
  field: string,
  rawValue: string,
  currentConfig: Record<string, unknown>,
  replaceConfig: (config: Record<string, unknown>) => void
) {
  try {
    const parsed = rawValue.trim() ? JSON.parse(rawValue) : {}
    const nextConfig = structuredClone(currentConfig)
    nextConfig[field] = parsed
    replaceConfig(nextConfig)
  } catch {
  }
}

function assignPath(target: Record<string, unknown>, field: string, value: unknown) {
  target[field] = value
}

function displayNodeType(nodeType: DesignerNodeType) {
  switch (nodeType) {
    case 'start':
      return '开始节点'
    case 'coordinate':
      return '协调节点'
    case 'sub_agent':
      return '子代理节点'
    case 'tool':
      return '工具节点'
    case 'message':
      return '消息节点'
    case 'end':
      return '结束节点'
    default:
      return nodeType
  }
}

function createWorkflowCode() {
  return `workflow_${Date.now()}`
}

function createPublishVersion() {
  const now = new Date()
  const parts = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
    String(now.getHours()).padStart(2, '0'),
    String(now.getMinutes()).padStart(2, '0'),
    String(now.getSeconds()).padStart(2, '0'),
  ]
  return `v${parts.join('')}`
}

function hydrateWorkflowSelection(selection: WorkflowEditorSelection) {
  const definition = parseJsonObject(selection.version.definition)
  const versionConfig = parseJsonObject(selection.version.config)
  const definitionConfig = asRecord(definition.config)
  const variableRegistry =
    asRecord(versionConfig.variable_registry).global || asRecord(versionConfig.variable_registry).temporary
      ? asRecord(versionConfig.variable_registry)
      : asRecord(definitionConfig.variable_registry)

  const globalVariables = toVariableDefinitions(variableRegistry.global, 'global')
  const tempVariables = toVariableDefinitions(variableRegistry.temporary, 'temp')
  const variableNameToId = new Map(
    [...globalVariables, ...tempVariables].map((variable) => [variable.name, variable.id])
  )
  const nodes = buildCanvasNodes(asRecord(definition.nodes), variableNameToId)
  const edges = buildCanvasEdges(asRecord(definition.transitions))

  return {
    workflowId: selection.version.workflowId ?? null,
    workflowCode: selection.workflowCode,
    workflowName:
      stringValue(definition.workflow_name) ||
      selection.workflowName ||
      selection.version.workflowName ||
      selection.workflowCode,
    draftVersion:
      String(selection.version.status || '').toLowerCase() === 'draft' ? selection.version.version : DRAFT_VERSION,
    publishedVersion:
      selection.publishedVersion ||
      (String(selection.version.status || '').toLowerCase() === 'published' ? selection.version.version : null),
    globalVariables,
    tempVariables,
    nodes: nodes.length > 0 ? nodes : structuredClone(initialNodes),
    edges,
  }
}

function buildCanvasNodes(
  source: Record<string, unknown>,
  variableNameToId: Map<string, string>
): Node<CanvasNodeData>[] {
  const entries = Object.values(source)
  if (entries.length === 0) {
    return structuredClone(initialNodes)
  }

  return entries.map((item, index) => {
    const node = asRecord(item)
    const nodeId = stringValue(node.id) || `node_${index + 1}`
    const nodeType = normalizeDesignerNodeType(stringValue(node.type))
    const config = denormalizeNodeConfig(nodeType, asRecord(node.config), variableNameToId)
    return {
      id: nodeId,
      type: toFlowType(nodeType),
      position: { x: 120 + (index % 3) * 280, y: 120 + Math.floor(index / 3) * 180 },
      data: {
        label: stringValue(node.name) || resolveNodeLabel(nodeType),
        nodeType,
        config,
      },
    }
  })
}

function buildCanvasEdges(source: Record<string, unknown>): Edge[] {
  const seen = new Set<string>()
  const edges: Edge[] = []

  Object.entries(source).forEach(([fromNodeId, target]) => {
    const targets = typeof target === 'string'
      ? [target]
      : Object.values(asRecord(target)).filter((value): value is string => typeof value === 'string' && value.trim().length > 0)

    targets.forEach((toNodeId, index) => {
      const edgeKey = `${fromNodeId}->${toNodeId}`
      if (!fromNodeId || !toNodeId || seen.has(edgeKey)) return
      seen.add(edgeKey)
      edges.push({
        id: `edge_${fromNodeId}_${toNodeId}_${index}`,
        source: fromNodeId,
        target: toNodeId,
      })
    })
  })

  return edges
}

function denormalizeNodeConfig(
  nodeType: DesignerNodeType,
  config: Record<string, unknown>,
  variableNameToId: Map<string, string>
) {
  switch (nodeType) {
    case 'start':
      return {
        prompt: String(config.prompt || ''),
        input_variable_ids: mapObjectKeysToVariableIds(config.initial_variables, variableNameToId),
      }
    case 'coordinate':
    case 'sub_agent':
      return {
        prompt: String(config.prompt || config.user_prompt || ''),
      }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'tool': {
      const invokeType = String(config.invoke_type || 'api')
      const restored: Record<string, unknown> = {
        invoke_type: invokeType,
        payload_mapping: ensureObject(config.payload_mapping),
      }
      if (invokeType === 'function') {
        restored.function_name = String(config.function_name || config.tool_code || '')
      } else if (invokeType === 'api') {
        restored.url = String(config.url || '')
        restored.method = String(config.method || 'POST')
      } else if (invokeType === 'mcp') {
        restored.mcp_endpoint = String(config.mcp_endpoint || '')
        restored.tool_name = String(config.tool_name || config.tool_code || '')
      } else if (invokeType === 'skill') {
        restored.skill_endpoint = String(config.skill_endpoint || '')
        restored.skill_name = String(config.skill_name || config.tool_code || '')
      }
      return restored
    }
    case 'end':
      return {
        prompt: String(config.prompt || ''),
        output_variable_ids: mapObjectKeysToVariableIds(config.output_format, variableNameToId),
      }
    default:
      return config
  }
}

function mapObjectKeysToVariableIds(source: unknown, variableNameToId: Map<string, string>) {
  return Object.keys(asRecord(source))
    .map((name) => variableNameToId.get(name))
    .filter((item): item is string => Boolean(item))
}

function toVariableDefinitions(source: unknown, scope: VariableScope): VariableDefinition[] {
  if (!Array.isArray(source)) {
    return []
  }

  return source.map((item, index) => {
    const value = asRecord(item)
    return {
      id: stringValue(value.id) || `${scope}_${index + 1}`,
      name: stringValue(value.name) || `${scope}_var_${index + 1}`,
      type: normalizeVariableType(stringValue(value.type)),
      scope,
      description: stringValue(value.description) || '',
    }
  })
}

function normalizeVariableType(value: string | null): VariableType {
  if (!value) return 'string'
  const allowedTypes: VariableType[] = [
    'string',
    'text',
    'integer',
    'number',
    'boolean',
    'date',
    'datetime',
    'time',
    'enum',
    'array',
    'object',
    'json',
    'markdown',
    'file',
    'image',
    'any',
  ]
  return allowedTypes.includes(value as VariableType) ? (value as VariableType) : 'string'
}

function normalizeDesignerNodeType(value: string | null): DesignerNodeType {
  switch (value) {
    case 'start':
    case 'coordinate':
    case 'sub_agent':
    case 'tool':
    case 'message':
    case 'end':
      return value
    default:
      return 'message'
  }
}

function resolveNodeLabel(nodeType: DesignerNodeType) {
  return nodeTemplates.find((template) => template.nodeType === nodeType)?.label || nodeType
}

function parseJsonObject(source?: string | null): Record<string, unknown> {
  if (!source || !source.trim()) {
    return {}
  }

  let candidate = source
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const parsed = JSON.parse(candidate) as unknown
      if (typeof parsed === 'string') {
        candidate = parsed
        continue
      }
      return asRecord(parsed)
    } catch {
      candidate = candidate.trim()
      if (candidate.startsWith('"') && candidate.endsWith('"') && candidate.length >= 2) {
        candidate = candidate.slice(1, -1)
      }
      candidate = candidate.replace(/\\"/g, '"').replace(/""/g, '"')
    }
  }

  return {}
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function stringValue(value: unknown) {
  if (typeof value !== 'string') {
    return value == null ? null : String(value)
  }
  return value.trim() ? value : null
}

export default Orchestrator
