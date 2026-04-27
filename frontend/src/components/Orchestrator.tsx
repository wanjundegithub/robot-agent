import { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react'
import ReactFlow, {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  Controls,
  MiniMap,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from 'reactflow'
import 'reactflow/dist/style.css'
import {
  getCapabilitiesByGroup,
  getCapabilityGroups,
  publishWorkflow,
  saveWorkflowDraft,
  validateWorkflowDraft,
} from '../services/api'
import type {
  CapabilityGroupSummary,
  CapabilityItemSummary,
  WorkflowDesignerDefinitionV2,
  WorkflowEditorSelection,
  WorkflowValidationIssue,
} from '../types'

type DesignerNodeType = 'start' | 'coordinator' | 'sub_agent' | 'tool' | 'message' | 'function' | 'end'
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

interface WorkflowGraphState {
  id: string
  name: string
  nodes: Node<CanvasNodeData>[]
  edges: Edge[]
}

interface ModelBindingsState {
  routing_model_code: string
  llm_defaults: {
    model_code: string
  }
}

interface WorkflowDraftPayload {
  workflowCode: string
  workflowVersion: string
  definition: WorkflowDesignerDefinitionV2
  entryRule: Record<string, unknown>
  workflowConfig: Record<string, unknown>
}

interface WorkflowMetaState {
  workflowId: number | null
  workflowCode: string
  draftVersion: string
  publishedVersion: string | null
}

interface HydratedWorkflowState {
  workflowId: number | null
  workflowCode: string
  workflowName: string
  draftVersion: string
  publishedVersion: string | null
  globalVariables: VariableDefinition[]
  tempVariables: VariableDefinition[]
  modelBindings: ModelBindingsState
  graphs: Record<string, WorkflowGraphState>
  graphOrder: string[]
  currentGraphId: string
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
const WORKFLOW_SCHEMA_VERSION = 'workflow-designer/v2'
const MAIN_GRAPH_ID = 'main'

const defaultModelBindings: ModelBindingsState = {
  routing_model_code: 'intent-router',
  llm_defaults: {
    model_code: 'general-chat',
  },
}

const variableTypeOptions: Array<{ value: VariableType; label: string }> = [
  { value: 'string', label: '字符串' },
  { value: 'text', label: '长文本' },
  { value: 'integer', label: '整数' },
  { value: 'number', label: '数值' },
  { value: 'boolean', label: '布尔值' },
  { value: 'date', label: '日期' },
  { value: 'datetime', label: '日期时间' },
  { value: 'time', label: '时间' },
  { value: 'enum', label: '枚举' },
  { value: 'array', label: '数组' },
  { value: 'object', label: '对象' },
  { value: 'json', label: '结构文本' },
  { value: 'markdown', label: '富文本' },
  { value: 'file', label: '文件' },
  { value: 'image', label: '图片' },
  { value: 'any', label: '任意类型' },
]

const mainInitialNodes: Node<CanvasNodeData>[] = [
  {
    id: 'coordinator_main',
    position: { x: 220, y: 160 },
    data: {
      label: '协调节点',
      nodeType: 'coordinator',
      config: {
        prompt: '根据用户意图选择要进入的子代理流程。',
      },
    },
  },
]

const mainInitialEdges: Edge[] = []

const subflowInitialNodes: Node<CanvasNodeData>[] = [
  {
    id: 'start',
    type: 'input',
    position: { x: 80, y: 120 },
    data: {
      label: '开始节点',
      nodeType: 'start',
      config: {
        prompt: '接收用户输入并初始化工作流变量。',
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
        message_text: '正在处理，请稍候。',
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
        prompt: '返回工作流最终输出。',
        output_variable_ids: [],
      },
    },
  },
]

const subflowInitialEdges: Edge[] = [
  { id: 'e_start_message', source: 'start', target: 'message_1' },
  { id: 'e_message_end', source: 'message_1', target: 'end' },
]

const nodeTemplates: Array<{ nodeType: DesignerNodeType; label: string; config: Record<string, unknown> }> = [
  {
    nodeType: 'start',
    label: '开始节点',
    config: {
      prompt: '定义工作流启动时可用的输入变量。',
      input_variable_ids: [],
    },
  },
  {
    nodeType: 'coordinator',
    label: '协调节点',
    config: {
      prompt: '协调多个子步骤，并决定下一条路由路径。',
    },
  },
  {
    nodeType: 'sub_agent',
    label: '子代理节点',
    config: {
      prompt: '将子任务委派给子代理，并记录下游所需变量。',
      subgraph_id: '',
    },
  },
  {
    nodeType: 'tool',
    label: '工具节点',
    config: {
      invoke_type: 'capability',
      group_id: '',
      capability_code: '',
      payload_mapping: {},
    },
  },
  {
    nodeType: 'message',
    label: '消息节点',
    config: {
      message_text: '在这里填写固定输出消息。',
    },
  },
  {
    nodeType: 'function',
    label: '函数节点',
    config: {
      operation_type: 'assign',
      assignments: {},
    },
  },
  {
    nodeType: 'end',
    label: '结束节点',
    config: {
      prompt: '定义结束节点需要返回哪些输出变量。',
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

const mainGraphNodeGroups: Array<{
  title: string
  items: DesignerNodeType[]
}> = [
  {
    title: '决策节点',
    items: ['coordinator', 'sub_agent'],
  },
]

const subflowNodeGroups: Array<{
  title: string
  items: DesignerNodeType[]
}> = [
  {
    title: '流程节点',
    items: ['start', 'message', 'end'],
  },
  {
    title: '执行节点',
    items: ['function', 'tool'],
  },
]

const Orchestrator = forwardRef<OrchestratorHandle, OrchestratorProps>(function Orchestrator(
  { currentUserId, editorSelection, onWorkflowDraftChange, onWorkflowSidebarStateChange, onWorkflowVersionMutation },
  ref
) {
  const [graphs, setGraphs] = useState<Record<string, WorkflowGraphState>>(() => ({
    [MAIN_GRAPH_ID]: createInitialGraph(MAIN_GRAPH_ID),
  }))
  const [graphOrder, setGraphOrder] = useState<string[]>([MAIN_GRAPH_ID])
  const [currentGraphId, setCurrentGraphId] = useState(MAIN_GRAPH_ID)
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
  const [modelBindings, setModelBindings] = useState<ModelBindingsState>(defaultModelBindings)
  const [variableForm, setVariableForm] = useState(emptyVariableForm)
  const [capabilityGroups, setCapabilityGroups] = useState<CapabilityGroupSummary[]>([])
  const [capabilityItems, setCapabilityItems] = useState<CapabilityItemSummary[]>([])

  const currentGraph = graphs[currentGraphId] ?? createInitialGraph(currentGraphId)
  const currentGraphIsMain = currentGraphId === MAIN_GRAPH_ID
  const nodes = currentGraph.nodes
  const edges = currentGraph.edges
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedNodeData = (selectedNode?.data || null) as CanvasNodeData | null
  const allVariables = useMemo(() => [...globalVariables, ...tempVariables], [globalVariables, tempVariables])
  const variableNameMap = useMemo(() => new Map(allVariables.map((item) => [item.id, item])), [allVariables])

  const graphParentMap = useMemo(() => buildGraphParentMap(graphs), [graphs])
  const graphBreadcrumb = useMemo(
    () => resolveGraphBreadcrumb(currentGraphId, graphParentMap, graphs),
    [currentGraphId, graphParentMap, graphs]
  )
  const subgraphIds = useMemo(() => graphOrder.filter((graphId) => graphId !== MAIN_GRAPH_ID), [graphOrder])
  const visibleNodeGroups = currentGraphIsMain ? mainGraphNodeGroups : subflowNodeGroups

  const loadCapabilityItems = async (groupId: number) => {
    if (!groupId) {
      setCapabilityItems([])
      return
    }
    try {
      const items = await getCapabilitiesByGroup(groupId)
      setCapabilityItems(
        items.filter((item) => String(item.status || '').toUpperCase() === 'PUBLISHED')
      )
    } catch (error) {
      console.error('Failed to load capability node options:', error)
      setCapabilityItems([])
    }
  }

  const updateCurrentGraph = (updater: (graph: WorkflowGraphState) => WorkflowGraphState) => {
    setGraphs((prev) => {
      const current = prev[currentGraphId] ?? createInitialGraph(currentGraphId)
      return { ...prev, [currentGraphId]: updater(current) }
    })
  }

  const onNodesChange = (changes: NodeChange[]) => {
    updateCurrentGraph((graph) => ({
      ...graph,
      nodes: applyNodeChanges(changes, graph.nodes),
    }))
  }

  const onEdgesChange = (changes: EdgeChange[]) => {
    updateCurrentGraph((graph) => ({
      ...graph,
      edges: applyEdgeChanges(changes, graph.edges),
    }))
  }

  useEffect(() => {
    if (graphs[currentGraphId]) return
    if (graphs[MAIN_GRAPH_ID]) {
      setCurrentGraphId(MAIN_GRAPH_ID)
      return
    }
    const fallback = createInitialGraph(MAIN_GRAPH_ID)
    setGraphs({ [MAIN_GRAPH_ID]: fallback })
    setGraphOrder([MAIN_GRAPH_ID])
    setCurrentGraphId(MAIN_GRAPH_ID)
  }, [currentGraphId, graphs])

  useEffect(() => {
    if (!selectedNodeId) return
    if (nodes.some((node) => node.id === selectedNodeId)) return
    setSelectedNodeId(null)
  }, [nodes, selectedNodeId])

  useEffect(() => {
    void (async () => {
      try {
        const groups = await getCapabilityGroups()
        setCapabilityGroups(
          groups.filter((group) => String(group.status || '').toUpperCase() === 'PUBLISHED')
        )
      } catch (error) {
        console.error('Failed to load capability groups for orchestrator:', error)
      }
    })()
  }, [])

  useEffect(() => {
    const config = selectedNodeData?.config || {}
    if (selectedNodeData?.nodeType !== 'tool' || String(config.invoke_type || 'capability') !== 'capability') {
      setCapabilityItems([])
      return
    }
    const groupId = Number(config.group_id || 0)
    if (!groupId) {
      setCapabilityItems([])
      return
    }

    void loadCapabilityItems(groupId)
  }, [selectedNodeData])

  const summaryRules = useMemo<WorkflowSummaryRule[]>(() => {
    const mainNodes = graphs[MAIN_GRAPH_ID]?.nodes ?? []
    const coordinatorCount = mainNodes.filter(
      (node) => (node.data as CanvasNodeData).nodeType === 'coordinator'
    ).length
    const mainNodeTypesValid = mainNodes.every((node) =>
      ['coordinator', 'sub_agent'].includes((node.data as CanvasNodeData).nodeType)
    )
    const subflowGraphs = Object.values(graphs).filter((graph) => graph.id !== MAIN_GRAPH_ID)
    const subflowStructureValid = subflowGraphs.every((graph) => {
      const startCount = graph.nodes.filter(
        (node) => (node.data as CanvasNodeData).nodeType === 'start'
      ).length
      const endCount = graph.nodes.filter(
        (node) => (node.data as CanvasNodeData).nodeType === 'end'
      ).length
      return startCount === 1 && endCount === 1
    })
    return [
      { label: '主流程必须至少包含一个协调节点', valid: coordinatorCount >= 1 },
      { label: '主流程仅允许协调节点和子代理节点', valid: mainNodeTypesValid },
      { label: '每个子流程都必须保留唯一的开始和结束节点', valid: subflowStructureValid },
      { label: '必须维护变量定义', valid: allVariables.length > 0 },
    ]
  }, [allVariables.length, graphs])

  const currentDefinition = useMemo<WorkflowDesignerDefinitionV2>(() => {
    const orderedGraphs = graphOrder
      .map((graphId) => graphs[graphId])
      .filter((graph): graph is WorkflowGraphState => Boolean(graph))
    const normalizedGraphs = orderedGraphs.length > 0 ? orderedGraphs : [createInitialGraph(MAIN_GRAPH_ID)]
    return {
      schema_version: WORKFLOW_SCHEMA_VERSION,
      workflow_code: workflowMeta.workflowCode,
      workflow_name: workflowName.trim(),
      workflow_version: workflowMeta.draftVersion,
      main_graph_id: MAIN_GRAPH_ID,
      graphs: Object.fromEntries(
        normalizedGraphs.map((graph) => [graph.id, toDefinitionGraph(graph, variableNameMap)])
      ),
      variables: {
        global: globalVariables,
        temporary: tempVariables,
      },
      model_bindings: modelBindings,
      editor_meta: {
        layout_engine: 'reactflow',
        viewport: { x: 0, y: 0, zoom: 0.92 },
        readonly: false,
        last_saved_by: currentUserId,
        current_graph_id: currentGraphId,
        graph_order: normalizedGraphs.map((graph) => graph.id),
        graph_layouts: buildGraphLayouts(normalizedGraphs),
      },
    }
  }, [
    currentGraphId,
    currentUserId,
    globalVariables,
    graphOrder,
    graphs,
    modelBindings,
    tempVariables,
    variableNameMap,
    workflowMeta.draftVersion,
    workflowMeta.workflowCode,
    workflowName,
  ])

  const compatibilityWorkflowConfig = useMemo(
    () => ({
      schema_version: WORKFLOW_SCHEMA_VERSION,
      main_graph_id: MAIN_GRAPH_ID,
      variable_registry: {
        global: globalVariables,
        temporary: tempVariables,
      },
      model_bindings: modelBindings,
    }),
    [globalVariables, modelBindings, tempVariables]
  )

  const currentEntryRule = useMemo(
    () => ({
      intent_codes: ['general_agent_request'],
      keywords: workflowName.trim() ? [workflowName.trim()] : ['workflow'],
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
      workflowConfig: compatibilityWorkflowConfig,
    })
  }, [compatibilityWorkflowConfig, currentDefinition, currentEntryRule, onWorkflowDraftChange, workflowMeta])

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
    setGraphs(hydrated.graphs)
    setGraphOrder(hydrated.graphOrder)
    setCurrentGraphId(hydrated.currentGraphId)
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
    setModelBindings(hydrated.modelBindings)
    setVariableForm(emptyVariableForm)
    setValidationIssues([])
    setSaveStatus(`已加载版本 ${editorSelection.version.version}`)
  }, [editorSelection])

  const ensureWorkflowBasics = () => {
    const trimmedName = workflowName.trim()
    if (!trimmedName) {
      setSaveStatus('请先填写工作流名称。')
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
      workflowConfig: compatibilityWorkflowConfig,
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
      setSaveStatus(response.workflowId ? `草稿已保存。工作流 ID: ${response.workflowId}` : '草稿已保存。')
      onWorkflowVersionMutation?.({
        workflowCode: response.workflowCode,
        version: response.version,
        action: 'save_draft',
        refreshAt: Date.now(),
      })
    } catch (error) {
      setSaveStatus(error instanceof Error ? `保存失败：${error.message}` : '保存失败。')
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
        workflowConfig: compatibilityWorkflowConfig,
      })
      const issues = response.issues ?? []
      setValidationIssues(issues)
      setSaveStatus(issues.length === 0 ? '校验通过。' : `发现 ${issues.length} 个校验问题。`)
    } catch (error) {
      setValidationIssues([])
      setSaveStatus(error instanceof Error ? `校验失败：${error.message}` : '校验失败。')
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
        workflowConfig: compatibilityWorkflowConfig,
      })
      const issues = validation.issues ?? []
      setValidationIssues(issues)
      if (issues.length > 0) {
        setSaveStatus(`发布被阻止，仍有 ${issues.length} 个校验问题待处理。`)
        return
      }

      const publishVersion = createPublishVersion()
      const saved = await persistDraft(publishVersion)
      if (!saved) return

      await publishWorkflow(saved.workflowCode, publishVersion, currentUserId)
      setWorkflowMeta((prev) => ({ ...prev, publishedVersion: publishVersion }))
      setSaveStatus(`已发布版本 ${publishVersion}。`)
      onWorkflowVersionMutation?.({
        workflowCode: saved.workflowCode,
        version: publishVersion,
        action: 'publish',
        refreshAt: Date.now(),
      })
    } catch (error) {
      setSaveStatus(error instanceof Error ? `发布失败：${error.message}` : '发布失败。')
    } finally {
      setIsPublishing(false)
    }
  }

  const addNode = (nodeType: DesignerNodeType) => {
    const template = nodeTemplates.find((item) => item.nodeType === nodeType)
    if (!template) return
    const id = `${nodeType}_${Date.now()}`
    updateCurrentGraph((graph) => ({
      ...graph,
      nodes: [
        ...graph.nodes,
        {
          id,
          type: toFlowType(nodeType),
          position: { x: 140 + graph.nodes.length * 60, y: 180 + (graph.nodes.length % 3) * 110 },
          data: {
            label: template.label,
            nodeType: template.nodeType,
            config: structuredClone(template.config),
          },
        },
      ],
    }))
    setSelectedNodeId(id)
  }

  const onConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return
    if (currentGraphIsMain) {
      const sourceNode = nodes.find((node) => node.id === connection.source)
      const targetNode = nodes.find((node) => node.id === connection.target)
      const sourceType = (sourceNode?.data as CanvasNodeData | undefined)?.nodeType
      const targetType = (targetNode?.data as CanvasNodeData | undefined)?.nodeType
      if (sourceType !== 'coordinator' || targetType !== 'sub_agent') {
        return
      }
    }
    updateCurrentGraph((graph) => ({
      ...graph,
      edges: addEdge({ ...connection, id: `edge_${Date.now()}` }, graph.edges),
    }))
  }

  const updateSelectedNodeLabel = (label: string) => {
    if (!selectedNodeId) return
    updateCurrentGraph((graph) => ({
      ...graph,
      nodes: graph.nodes.map((node) =>
        node.id === selectedNodeId ? { ...node, data: { ...(node.data as CanvasNodeData), label } } : node
      ),
    }))
  }

  const replaceSelectedConfig = (nextConfig: Record<string, unknown>) => {
    if (!selectedNodeId) return
    updateCurrentGraph((graph) => ({
      ...graph,
      nodes: graph.nodes.map((node) =>
        node.id === selectedNodeId
          ? { ...node, data: { ...(node.data as CanvasNodeData), config: nextConfig } }
          : node
      ),
    }))
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
    const next = current.includes(variableId) ? current.filter((item) => item !== variableId) : [...current, variableId]
    updateSelectedConfigField(field, next)
  }

  const removeSelectedNode = () => {
    if (!selectedNodeId) return
    const linkedSubgraphId =
      selectedNodeData?.nodeType === 'sub_agent'
        ? String(selectedNodeData.config.subgraph_id || '').trim()
        : ''
    const removedGraphIds = linkedSubgraphId
      ? collectRemovableGraphIds(linkedSubgraphId, graphs)
      : []

    setGraphs((prev) => {
      const next = Object.fromEntries(
        Object.entries(prev)
          .filter(([graphId]) => !removedGraphIds.includes(graphId))
          .map(([graphId, graph]) => {
            if (graphId !== currentGraphId) {
              return [graphId, graph]
            }
            return [
              graphId,
              {
                ...graph,
                nodes: graph.nodes.filter((node) => node.id !== selectedNodeId),
                edges: graph.edges.filter(
                  (edge) => edge.source !== selectedNodeId && edge.target !== selectedNodeId
                ),
              },
            ]
          })
      ) as Record<string, WorkflowGraphState>

      if (!next[MAIN_GRAPH_ID]) {
        next[MAIN_GRAPH_ID] = createInitialGraph(MAIN_GRAPH_ID)
      }
      return next
    })
    if (removedGraphIds.length > 0) {
      setGraphOrder((prev) => prev.filter((graphId) => !removedGraphIds.includes(graphId)))
      if (removedGraphIds.includes(currentGraphId)) {
        setCurrentGraphId(MAIN_GRAPH_ID)
      }
    }
    setSelectedNodeId(null)
  }

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!selectedNodeId) return
      if (!['Delete', 'Backspace'].includes(event.key)) return
      const target = event.target as HTMLElement | null
      const tagName = target?.tagName?.toLowerCase()
      if (target?.isContentEditable || ['input', 'textarea', 'select'].includes(tagName || '')) {
        return
      }
      event.preventDefault()
      removeSelectedNode()
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [removeSelectedNode, selectedNodeId])

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

  const updateVariable = (scope: VariableScope, variableId: string, field: 'name' | 'type' | 'description', value: string) => {
    const setter = scope === 'global' ? setGlobalVariables : setTempVariables
    setter((prev) =>
      prev.map((item) =>
        item.id === variableId ? { ...item, [field]: field === 'type' ? (value as VariableType) : value } : item
      )
    )
  }

  const removeVariable = (scope: VariableScope, variableId: string) => {
    const setter = scope === 'global' ? setGlobalVariables : setTempVariables
    setter((prev) => prev.filter((item) => item.id !== variableId))
    setGraphs((prev) =>
      Object.fromEntries(
        Object.entries(prev).map(([graphId, graph]) => [
          graphId,
          {
            ...graph,
            nodes: graph.nodes.map((node) => {
              const data = node.data as CanvasNodeData
              const nextConfig = structuredClone(data.config || {})
              for (const key of ['input_variable_ids', 'output_variable_ids']) {
                if (Array.isArray(nextConfig[key])) {
                  nextConfig[key] = (nextConfig[key] as string[]).filter((item) => item !== variableId)
                }
              }
              return { ...node, data: { ...data, config: nextConfig } }
            }),
          },
        ])
      )
    )
  }

  const openGraph = (graphId: string) => {
    const normalized = graphId.trim()
    if (!normalized) return
    setGraphs((prev) => ({
      ...prev,
      [normalized]: prev[normalized] ?? createInitialGraph(normalized),
    }))
    setGraphOrder((prev) => (prev.includes(normalized) ? prev : [...prev, normalized]))
    setCurrentGraphId(normalized)
    setSelectedNodeId(null)
  }

  const renameCurrentGraph = (name: string) => {
    const normalized = name.trim()
    updateCurrentGraph((graph) => ({
      ...graph,
      name: normalized || defaultGraphName(graph.id),
    }))
  }

  const createSubgraph = () => {
    let index = subgraphIds.length + 1
    let graphId = `subgraph_${index}`
    while (graphs[graphId]) {
      index += 1
      graphId = `subgraph_${index}`
    }

    setGraphs((prev) => ({
      ...prev,
      [graphId]: createInitialGraph(graphId, `子流程 ${index}`),
    }))
    setGraphOrder((prev) => uniqueGraphOrder([...prev, graphId]))
    setCurrentGraphId(graphId)
    setSelectedNodeId(null)
  }

  const bindAndOpenSubgraph = () => {
    if (!selectedNodeId || !selectedNodeData || selectedNodeData.nodeType !== 'sub_agent') return
    const configured = String(selectedNodeData.config.subgraph_id || '').trim()
    const subgraphId = configured || `subgraph_${selectedNodeId}`
    const nextConfig = structuredClone(selectedNodeData.config || {})
    nextConfig.subgraph_id = subgraphId
    replaceSelectedConfig(nextConfig)
    openGraph(subgraphId)
  }

  const handleNodeClick = (_event: unknown, node: Node<CanvasNodeData>) => {
    const data = node.data as CanvasNodeData
    if (selectedNodeId === node.id && data.nodeType === 'sub_agent') {
      const subgraphId = String(data.config?.subgraph_id || '').trim()
      if (subgraphId) {
        openGraph(subgraphId)
        return
      }
    }
    setSelectedNodeId(node.id)
  }

  const renderVariableListSelector = (field: 'input_variable_ids' | 'output_variable_ids') => {
    const selectedIds = Array.isArray(selectedNodeData?.config[field]) ? (selectedNodeData?.config[field] as string[]) : []

    return (
      <div className="space-y-2">
        {allVariables.length === 0 && <div className="text-xs text-slate-500">请先在下方面板创建变量。</div>}
        {allVariables.map((variable) => (
          <label key={variable.id} className="flex items-start justify-between gap-3 rounded-lg border border-slate-200 px-3 py-2 text-sm">
            <span className="min-w-0">
              <span className="block font-medium text-slate-700">{variable.name}</span>
              <span className="block text-xs text-slate-400">
                {displayVariableScope(variable.scope)} / {displayVariableType(variable.type)}
              </span>
              {variable.description && <span className="mt-1 block text-xs text-slate-500">{variable.description}</span>}
            </span>
            <input type="checkbox" checked={selectedIds.includes(variable.id)} onChange={() => toggleSelectedVariable(field, variable.id)} />
          </label>
        ))}
      </div>
    )
  }

  const renderNodeEditor = () => {
    if (!selectedNodeData) {
      return (
        <div className="space-y-4">
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-4">
            <div className="text-sm font-semibold text-slate-800">当前流程属性</div>
            <div className="mt-1 text-xs text-slate-500">未选中节点时，可在这里维护当前流程画布的名称和概览。</div>
            <div className="mt-4 space-y-3">
              <label className="block space-y-2">
                <span className="text-xs font-medium text-slate-500">流程名称</span>
                <input
                  value={currentGraph.name}
                  onChange={(event) => renameCurrentGraph(event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder={currentGraphId === MAIN_GRAPH_ID ? '主流程' : '子流程名称'}
                />
              </label>
              <div className="grid gap-2 sm:grid-cols-3">
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
                  <div className="text-xs text-slate-400">流程类型</div>
                  <div className="mt-1 text-sm font-medium text-slate-700">
                    {currentGraphId === MAIN_GRAPH_ID ? '主流程' : '子流程'}
                  </div>
                </div>
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
                  <div className="text-xs text-slate-400">节点数量</div>
                  <div className="mt-1 text-sm font-medium text-slate-700">{currentGraph.nodes.length} 个</div>
                </div>
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-3">
                  <div className="text-xs text-slate-400">连线数量</div>
                  <div className="mt-1 text-sm font-medium text-slate-700">{currentGraph.edges.length} 条</div>
                </div>
              </div>
              <div className="rounded-xl border border-dashed border-slate-300 bg-white px-3 py-3 text-xs leading-6 text-slate-500">
                {currentGraphId === MAIN_GRAPH_ID
                  ? '主流程只负责协调和分发子代理节点，协调节点可以连接多个子代理节点。'
                  : '子流程用于承接主流程委派的局部任务，建议保持结构紧凑，避免承担过多无关逻辑。'}
              </div>
            </div>
          </div>
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-5 text-sm text-slate-500">
            请选择一个节点，在这里编辑节点配置。
          </div>
        </div>
      )
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
        <div className="text-xs text-slate-400">节点类型：{displayNodeType(nodeType)}</div>

        {(nodeType === 'start' || nodeType === 'coordinator' || nodeType === 'sub_agent' || nodeType === 'end') && (
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

        {nodeType === 'sub_agent' && (
          <>
            <input
              value={String(config.subgraph_id || '')}
              onChange={(event) => updateSelectedConfigField('subgraph_id', event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              placeholder="请输入子流程标识"
              data-testid="workflow-subgraph-id-input"
            />
            <button className="prompt-secondary w-full" type="button" onClick={bindAndOpenSubgraph} data-testid="workflow-open-subgraph">
              进入子流程画布
            </button>
          </>
        )}

        {nodeType === 'message' && (
          <textarea
            value={String(config.message_text || '')}
            onChange={(event) => updateSelectedConfigField('message_text', event.target.value)}
            className="min-h-[100px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder="请输入固定输出文本"
          />
        )}

        {nodeType === 'function' && (
          <>
            <select
              value={String(config.operation_type || 'assign')}
              onChange={(event) => updateSelectedConfigField('operation_type', event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="assign">变量赋值</option>
            </select>
            <textarea
              value={formatObject(config.assignments)}
              onChange={(event) =>
                updateJsonConfigField('assignments', event.target.value, config, replaceSelectedConfig)
              }
              className="min-h-[120px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
              placeholder="请填写 assignments 对象"
            />
          </>
        )}

        {nodeType === 'tool' && (
          <>
            <select
              value={String(config.invoke_type || 'capability')}
              onChange={(event) => updateSelectedConfigField('invoke_type', event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="capability">已发布接口能力</option>
              <option value="api">直接接口调用</option>
            </select>

            {String(config.invoke_type || 'capability') === 'capability' && (
              <>
                <select
                  value={String(config.group_id || '')}
                  onChange={(event) => {
                    const nextGroupId = event.target.value
                    updateSelectedConfigField('group_id', nextGroupId)
                    updateSelectedConfigField('capability_code', '')
                    void loadCapabilityItems(Number(nextGroupId || 0))
                  }}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  data-testid="workflow-capability-group-select"
                >
                  <option value="">选择能力组</option>
                  {capabilityGroups.map((group) => (
                    <option key={group.id} value={group.id}>
                      {group.groupName}
                    </option>
                  ))}
                </select>
                <select
                  value={String(config.capability_code || '')}
                  onChange={(event) => {
                    const nextCapabilityCode = event.target.value
                    updateSelectedConfigField('capability_code', nextCapabilityCode)
                  }}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  data-testid="workflow-capability-code-select"
                >
                  <option value="">选择能力</option>
                  {capabilityItems.map((item) => (
                    <option key={item.capabilityCode} value={item.capabilityCode}>
                      {item.capabilityName}
                    </option>
                  ))}
                </select>
              </>
            )}

            {String(config.invoke_type || 'capability') === 'api' && (
              <>
                <input
                  value={String(config.url || '')}
                  onChange={(event) => updateSelectedConfigField('url', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="接口地址"
                />
                <select
                  value={String(config.method || 'POST')}
                  onChange={(event) => updateSelectedConfigField('method', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                >
                  <option value="GET">读取请求</option>
                  <option value="POST">提交请求</option>
                  <option value="PUT">整体更新</option>
                  <option value="PATCH">局部更新</option>
                  <option value="DELETE">删除请求</option>
                </select>
              </>
            )}

            <textarea
              value={formatObject(config.payload_mapping)}
              onChange={(event) => updateJsonConfigField('payload_mapping', event.target.value, config, replaceSelectedConfig)}
              className="min-h-[120px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs"
              placeholder="请填写载荷映射对象"
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
          <button
            className="prompt-secondary w-full"
            type="button"
            onClick={removeSelectedNode}
            data-testid="workflow-delete-node"
          >
            删除当前节点
          </button>
        )}
      </div>
    )
  }

  const renderVariableManager = (scope: VariableScope, items: VariableDefinition[]) => (
    <div className="space-y-2">
      {items.length === 0 && <div className="text-xs text-slate-500">{scope === 'global' ? '暂无全局变量。' : '暂无临时变量。'}</div>}
      {items.map((item) => (
        <div key={item.id} className="space-y-2 rounded-lg border border-slate-200 bg-white px-3 py-3">
          <div className="grid gap-2 md:grid-cols-[1fr_140px_88px]">
            <input
              value={item.name}
              onChange={(event) => updateVariable(scope, item.id, 'name', event.target.value)}
              className="rounded-lg border border-slate-200 px-3 py-2 text-sm"
              placeholder="变量名称"
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
            placeholder="描述变量语义，便于其余节点和代理正确使用。"
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
    <div className="panel-card h-full overflow-hidden">
      <div className="flex h-full min-h-0 flex-col gap-4">
        <div className="rounded-3xl border border-slate-200 bg-[linear-gradient(135deg,rgba(255,255,255,0.98),rgba(244,247,251,0.96))] px-5 py-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="panel-title">工作流设计器</div>
              <div className="mt-2 text-lg font-semibold text-slate-900">
                {workflowName.trim() || '未命名工作流'}
              </div>
              <div className="mt-1 text-sm text-slate-500" data-testid="workflow-current-graph">
                当前画布：{graphs[currentGraphId]?.name || defaultGraphName(currentGraphId)}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white/90 px-4 py-3 text-right text-xs text-slate-500">
              <div>保存状态：{saveStatus}</div>
              <div className="mt-1">主流程：1 个 / 子流程：{subgraphIds.length} 个</div>
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-2 text-xs text-slate-500">
            {graphBreadcrumb.map((graphId) => (
              <button
                key={graphId}
                type="button"
                data-testid={`workflow-breadcrumb-${graphId}`}
                className={`rounded-full border px-3 py-1.5 ${
                  graphId === currentGraphId ? 'border-sky-300 bg-sky-50 text-sky-700' : 'border-slate-200 bg-white text-slate-600'
                }`}
                onClick={() => openGraph(graphId)}
              >
                {graphs[graphId]?.name || defaultGraphName(graphId)}
              </button>
            ))}
          </div>
        </div>

        <div className="grid flex-1 min-h-0 gap-4 xl:grid-cols-[260px_minmax(0,1fr)_360px]">
          <aside
            className="flex min-h-0 flex-col rounded-3xl border border-slate-200 bg-white/95 p-4"
            data-testid="workflow-graph-nav"
          >
            <div className="mb-4">
              <div className="panel-title">流程导航</div>
              <div className="mt-2 text-sm text-slate-500">在主流程与子流程之间切换，并查看每个画布的节点概况。</div>
            </div>

            <div className="space-y-3 overflow-auto pr-1">
              {graphOrder.map((graphId) => {
                const graph = graphs[graphId]
                if (!graph) return null
                const isActive = graphId === currentGraphId
                const issueCount = validationIssues.filter((issue) => !issue.node_id || graph.nodes.some((node) => node.id === issue.node_id)).length
                return (
                  <button
                    key={graphId}
                    type="button"
                    onClick={() => openGraph(graphId)}
                    className={`w-full rounded-2xl border px-4 py-3 text-left transition ${
                      isActive
                        ? 'border-sky-300 bg-sky-50 shadow-[0_12px_24px_rgba(14,165,233,0.08)]'
                        : 'border-slate-200 bg-slate-50/70 hover:border-slate-300 hover:bg-white'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate text-sm font-semibold text-slate-800">
                          {graph.name || defaultGraphName(graphId)}
                        </div>
                        <div className="mt-1 text-xs text-slate-500">
                          {graphId === MAIN_GRAPH_ID ? '主流程' : '子流程'}
                        </div>
                      </div>
                      <div className="rounded-full border border-slate-200 bg-white px-2 py-1 text-[11px] text-slate-500">
                        {graph.nodes.length} 节点
                      </div>
                    </div>
                    <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                      <span>{graph.edges.length} 条连线</span>
                      <span className={issueCount > 0 ? 'text-amber-600' : 'text-emerald-600'}>
                        {issueCount > 0 ? `${issueCount} 项待处理` : '结构完整'}
                      </span>
                    </div>
                  </button>
                )
              })}
            </div>

            <button className="prompt-secondary mt-4 w-full" type="button" onClick={createSubgraph}>
              新增子流程
            </button>
          </aside>

          <section className="flex min-h-0 flex-col rounded-3xl border border-slate-200 bg-white/95 p-4">
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="panel-title">画布编辑区</div>
                <div className="mt-2 text-sm text-slate-500">
                  当前正在编辑 {graphs[currentGraphId]?.name || defaultGraphName(currentGraphId)}，连线仅表达可达关系，不承载条件。
                </div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
                单击节点可编辑，双击子代理节点可快速进入子流程。
              </div>
            </div>

            <div className="mb-4 grid gap-3 md:grid-cols-3">
              {visibleNodeGroups.map((group) => (
                <div key={group.title} className="rounded-2xl border border-slate-200 bg-slate-50/80 p-3">
                  <div className="text-xs font-semibold text-slate-400">{group.title}</div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {group.items.map((nodeType) => {
                      const template = nodeTemplates.find((item) => item.nodeType === nodeType)
                      if (!template) return null
                      return (
                        <button
                          key={template.nodeType}
                          className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-700 transition hover:border-sky-300 hover:text-sky-700"
                          onClick={() => addNode(template.nodeType)}
                          type="button"
                          data-testid={`workflow-add-node-${template.nodeType}`}
                        >
                          + {template.label}
                        </button>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>

            <div className="panel-body min-h-[760px] rounded-2xl border border-slate-200 bg-white">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                fitView
                data-testid="workflow-reactflow"
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onNodeClick={handleNodeClick}
                onPaneClick={() => setSelectedNodeId(null)}
                panOnScroll
              >
                <Background gap={12} size={1} />
                <MiniMap pannable zoomable />
                <Controls />
              </ReactFlow>
            </div>
          </section>

          <aside
            className="grid min-h-0 gap-4 xl:grid-rows-[minmax(0,1fr)_minmax(0,1.1fr)]"
            data-testid="workflow-properties-panel"
          >
            <div className="min-h-0 rounded-3xl border border-slate-200 bg-white/95 p-4">
              <div className="mb-3">
                <div className="panel-title">流程属性</div>
                <div className="mt-2 text-sm text-slate-500">
                  {selectedNodeData ? '正在编辑节点属性。' : '当前显示流程级属性，可直接调整当前画布名称。'}
                </div>
              </div>
              <div className="max-h-full overflow-auto pr-1">{renderNodeEditor()}</div>
            </div>

            <div className="min-h-0 rounded-3xl border border-slate-200 bg-slate-50/90 p-4">
              <div className="mb-3">
                <div className="panel-title">变量管理</div>
                <div className="mt-2 text-sm text-slate-500">统一维护全局变量与临时变量，供主流程和子流程复用。</div>
              </div>
              <div className="mb-3 grid gap-2">
                <input
                  value={variableForm.name}
                  onChange={(event) => setVariableForm((prev) => ({ ...prev, name: event.target.value }))}
                  className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="变量名称"
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
                  placeholder="描述变量语义、预期格式和使用约束。"
                />
                <button className="prompt-primary" type="button" onClick={addVariable}>
                  添加变量
                </button>
              </div>

              <div className="grid max-h-[420px] gap-4 overflow-auto xl:grid-cols-2">
                <div className="space-y-2">
                  <div className="text-xs font-semibold text-slate-400">全局变量</div>
                  {renderVariableManager('global', globalVariables)}
                </div>
                <div className="space-y-2">
                  <div className="text-xs font-semibold text-slate-400">临时变量</div>
                  {renderVariableManager('temp', tempVariables)}
                </div>
              </div>
            </div>
          </aside>
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

function createInitialGraph(graphId: string, name?: string): WorkflowGraphState {
  const isMainGraph = graphId === MAIN_GRAPH_ID
  return {
    id: graphId,
    name: name || defaultGraphName(graphId),
    nodes: structuredClone(isMainGraph ? mainInitialNodes : subflowInitialNodes),
    edges: structuredClone(isMainGraph ? mainInitialEdges : subflowInitialEdges),
  }
}

function toDefinitionGraph(graph: WorkflowGraphState, variableNameMap: Map<string, VariableDefinition>) {
  const nodeMap = Object.fromEntries(
    graph.nodes.map((node) => {
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

  const edges = graph.edges.map((edge) => ({
    edge_id: edge.id,
    source_node_id: edge.source,
    target_node_id: edge.target,
  }))

  return {
    graph_id: graph.id,
    graph_type: graph.id === MAIN_GRAPH_ID ? 'MAIN' : 'SUBGRAPH',
    graph_name: graph.name,
    entry_node_id:
      graph.nodes.find((node) => (node.data as CanvasNodeData).nodeType === 'start')?.id || graph.nodes[0]?.id || 'start',
    nodes: nodeMap,
    edges,
  }
}

function buildGraphLayouts(graphs: WorkflowGraphState[]) {
  return Object.fromEntries(
    graphs.map((graph) => [
      graph.id,
      {
        nodes: graph.nodes.map((node) => ({
          id: node.id,
          x: node.position.x,
          y: node.position.y,
        })),
      },
    ])
  )
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
    case 'coordinator':
      return {
        prompt: String(config.prompt || ''),
        user_prompt: String(config.prompt || ''),
      }
    case 'sub_agent': {
      const base: Record<string, unknown> = {
        prompt: String(config.prompt || ''),
        user_prompt: String(config.prompt || ''),
      }
      const subgraphId = String(config.subgraph_id || '').trim()
      if (subgraphId) {
        base.subgraph_id = subgraphId
      }
      return base
    }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'function':
      return {
        operation_type: String(config.operation_type || 'assign'),
        assignments: ensureObject(config.assignments),
      }
    case 'tool': {
      const invokeType = String(config.invoke_type || 'capability')
      const base: Record<string, unknown> = {
        invoke_type: invokeType,
        payload_mapping: ensureObject(config.payload_mapping),
      }
      if (invokeType === 'capability') {
        base.group_id = Number(config.group_id || 0) || ''
        base.capability_code = String(config.capability_code || '')
        base.tool_code = String(config.capability_code || '')
      } else if (invokeType === 'api') {
        base.url = String(config.url || '')
        base.method = String(config.method || 'POST')
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
    .map((item) => [item.name, (prefix ? `$${prefix}.${item.name}` : useEmptyDefault ? '' : item.name)])
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
    case 'coordinator':
      return '协调节点'
    case 'sub_agent':
      return '子代理节点'
    case 'tool':
      return '工具节点'
    case 'message':
      return '消息节点'
    case 'function':
      return '函数节点'
    case 'end':
      return '结束节点'
    default:
      return nodeType
  }
}

function displayVariableScope(scope: VariableScope) {
  return scope === 'global' ? '全局变量' : '临时变量'
}

function displayVariableType(type: VariableType) {
  return variableTypeOptions.find((option) => option.value === type)?.label || type
}

function defaultGraphName(graphId: string) {
  return graphId === MAIN_GRAPH_ID ? '主流程' : '未命名子流程'
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

function hydrateWorkflowSelection(selection: WorkflowEditorSelection): HydratedWorkflowState {
  const definition = parseJsonObject(selection.version.definition)
  const versionConfig = parseJsonObject(selection.version.config)
  const versionEditorMeta = parseJsonObject(selection.version.editorMeta)
  const definitionEditorMeta = asRecord(definition.editor_meta)
  const editorMeta = Object.keys(definitionEditorMeta).length > 0 ? definitionEditorMeta : versionEditorMeta

  const variablesSource = resolveVariableSource(definition, versionConfig)
  const globalVariables = toVariableDefinitions(variablesSource.global, 'global')
  const tempVariables = toVariableDefinitions(variablesSource.temporary, 'temp')
  const variableNameToId = new Map(
    [...globalVariables, ...tempVariables].map((variable) => [variable.name, variable.id])
  )
  const hydratedGraphs = hydrateGraphs(definition, editorMeta, variableNameToId)

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
    modelBindings: resolveModelBindings(definition, versionConfig),
    graphs: hydratedGraphs.graphs,
    graphOrder: hydratedGraphs.graphOrder,
    currentGraphId: hydratedGraphs.currentGraphId,
  }
}

function resolveModelBindings(definition: Record<string, unknown>, versionConfig: Record<string, unknown>): ModelBindingsState {
  const definitionBindings = asRecord(definition.model_bindings)
  if (Object.keys(definitionBindings).length > 0) {
    const definitionDefaults = asRecord(definitionBindings.llm_defaults)
    return {
      routing_model_code: String(definitionBindings.routing_model_code || defaultModelBindings.routing_model_code),
      llm_defaults: {
        model_code: String(definitionDefaults.model_code || defaultModelBindings.llm_defaults.model_code),
      },
    }
  }

  const configBindings = asRecord(versionConfig.model_bindings)
  if (Object.keys(configBindings).length > 0) {
    const configDefaults = asRecord(configBindings.llm_defaults)
    return {
      routing_model_code: String(configBindings.routing_model_code || defaultModelBindings.routing_model_code),
      llm_defaults: {
        model_code: String(configDefaults.model_code || defaultModelBindings.llm_defaults.model_code),
      },
    }
  }

  const definitionConfig = asRecord(definition.config)
  const definitionConfigDefaults = asRecord(definitionConfig.llm_defaults)
  return {
    routing_model_code: String(definitionConfig.routing_model_code || defaultModelBindings.routing_model_code),
    llm_defaults: {
      model_code: String(definitionConfigDefaults.model_code || defaultModelBindings.llm_defaults.model_code),
    },
  }
}

function resolveVariableSource(definition: Record<string, unknown>, versionConfig: Record<string, unknown>) {
  const definitionVariables = asRecord(definition.variables)
  if (definitionVariables.global || definitionVariables.temporary || definitionVariables.temp) {
    return {
      global: definitionVariables.global,
      temporary: definitionVariables.temporary ?? definitionVariables.temp,
    }
  }

  const versionRegistry = asRecord(versionConfig.variable_registry)
  if (versionRegistry.global || versionRegistry.temporary || versionRegistry.temp) {
    return {
      global: versionRegistry.global,
      temporary: versionRegistry.temporary ?? versionRegistry.temp,
    }
  }

  const definitionConfig = asRecord(definition.config)
  const fallbackRegistry = asRecord(definitionConfig.variable_registry)
  return {
    global: fallbackRegistry.global,
    temporary: fallbackRegistry.temporary ?? fallbackRegistry.temp,
  }
}

function hydrateGraphs(
  definition: Record<string, unknown>,
  editorMeta: Record<string, unknown>,
  variableNameToId: Map<string, string>
) {
  const graphLayouts = asRecord(editorMeta.graph_layouts)
  const graphEntries = parseGraphEntries(definition.graphs)
  const definitionSchemaVersion = String(definition.schema_version || '')
  const definitionMainGraphId = stringValue(definition.main_graph_id) || MAIN_GRAPH_ID

  if (definitionSchemaVersion === WORKFLOW_SCHEMA_VERSION && graphEntries.length > 0) {
    const graphs: Record<string, WorkflowGraphState> = {}
    graphEntries.forEach((entry, index) => {
      const item = asRecord(entry)
      const graphId =
        stringValue(item.graph_id) ||
        stringValue(item.id) ||
        (index === 0 ? definitionMainGraphId : `graph_${index + 1}`)
      const positions = extractNodePositions(graphLayouts, graphId)
      const graphNodes = buildCanvasNodes(asRecord(item.nodes), variableNameToId, positions)
      graphs[graphId] = {
        id: graphId,
        name: stringValue(item.graph_name) || stringValue(item.name) || defaultGraphName(graphId),
        nodes: graphNodes.length > 0 ? graphNodes : createInitialGraph(graphId).nodes,
        edges: buildCanvasEdgesFromGraphDefinition(item),
      }
    })

    if (!graphs[definitionMainGraphId]) {
      graphs[definitionMainGraphId] = createInitialGraph(definitionMainGraphId)
    }

    const rawOrder = Array.isArray(editorMeta.graph_order) ? (editorMeta.graph_order as unknown[]) : []
    const normalizedOrder = rawOrder.map((item) => String(item || '').trim()).filter((item) => item && graphs[item])
    const fallbackOrder = Object.keys(graphs)
    const graphOrder = uniqueGraphOrder([definitionMainGraphId, ...normalizedOrder, ...fallbackOrder])
    const preferredCurrentGraphId = stringValue(editorMeta.current_graph_id)
    return {
      graphs,
      graphOrder,
      currentGraphId:
        preferredCurrentGraphId && graphs[preferredCurrentGraphId] ? preferredCurrentGraphId : definitionMainGraphId,
    }
  }

  const mainNodes = buildCanvasNodes(asRecord(definition.nodes), variableNameToId)
  const mainGraph = {
    id: MAIN_GRAPH_ID,
    name: defaultGraphName(MAIN_GRAPH_ID),
    nodes: mainNodes.length > 0 ? mainNodes : createInitialGraph(MAIN_GRAPH_ID).nodes,
    edges: buildCanvasEdges(asRecord(definition.transitions)),
  }
  return {
    graphs: { [MAIN_GRAPH_ID]: mainGraph },
    graphOrder: [MAIN_GRAPH_ID],
    currentGraphId: MAIN_GRAPH_ID,
  }
}

function uniqueGraphOrder(items: string[]) {
  return Array.from(
    items.reduce((set, item) => {
      if (item.trim()) set.add(item)
      return set
    }, new Set<string>())
  )
}

function parseGraphEntries(source: unknown) {
  if (Array.isArray(source)) {
    return source.map((item) => asRecord(item))
  }
  const asMap = asRecord(source)
  return Object.entries(asMap).map(([graphId, item]) => {
    const value = asRecord(item)
    if (stringValue(value.graph_id) || stringValue(value.id)) {
      return value
    }
    return { ...value, graph_id: graphId }
  })
}

function extractNodePositions(layouts: Record<string, unknown>, graphId: string) {
  const layout = asRecord(layouts[graphId])
  const nodes = Array.isArray(layout.nodes) ? (layout.nodes as unknown[]) : []
  const positions = new Map<string, { x: number; y: number }>()
  nodes.forEach((item) => {
    const value = asRecord(item)
    const id = stringValue(value.id)
    const x = numberValue(value.x)
    const y = numberValue(value.y)
    if (id && x != null && y != null) {
      positions.set(id, { x, y })
    }
  })
  return positions
}

function buildCanvasNodes(
  source: Record<string, unknown>,
  variableNameToId: Map<string, string>,
  positions?: Map<string, { x: number; y: number }>
): Node<CanvasNodeData>[] {
  const entries = Object.values(source)
  if (entries.length === 0) {
    return []
  }

  return entries.map((item, index) => {
    const node = asRecord(item)
    const nodeId = stringValue(node.id) || `node_${index + 1}`
    const nodeType = normalizeDesignerNodeType(stringValue(node.type))
    const config = denormalizeNodeConfig(nodeType, asRecord(node.config), variableNameToId)
    const positioned = positions?.get(nodeId)
    return {
      id: nodeId,
      type: toFlowType(nodeType),
      position: positioned ?? { x: 120 + (index % 3) * 280, y: 120 + Math.floor(index / 3) * 180 },
      data: {
        label: stringValue(node.name) || resolveNodeLabel(nodeType),
        nodeType,
        config,
      },
    }
  })
}

function buildCanvasEdgesFromGraphDefinition(graph: Record<string, unknown>): Edge[] {
  const explicitEdges = Array.isArray(graph.edges) ? (graph.edges as unknown[]) : []
  if (explicitEdges.length > 0) {
    const seen = new Set<string>()
    const edges: Edge[] = []
    explicitEdges.forEach((item, index) => {
      const value = asRecord(item)
      const source = stringValue(value.source_node_id) || stringValue(value.source)
      const target = stringValue(value.target_node_id) || stringValue(value.target)
      if (!source || !target) return
      const edgeKey = `${source}->${target}`
      if (seen.has(edgeKey)) return
      seen.add(edgeKey)
      edges.push({
        id: stringValue(value.edge_id) || stringValue(value.id) || `edge_${source}_${target}_${index}`,
        source,
        target,
      })
    })
    return edges
  }

  return buildCanvasEdges(asRecord(graph.transitions))
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
    case 'coordinator':
      return {
        prompt: String(config.prompt || config.user_prompt || ''),
      }
    case 'sub_agent':
      return {
        prompt: String(config.prompt || config.user_prompt || ''),
        subgraph_id: String(config.subgraph_id || ''),
      }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'function':
      return {
        operation_type: String(config.operation_type || 'assign'),
        assignments: ensureObject(config.assignments),
      }
    case 'tool': {
      const invokeType = String(config.invoke_type || 'capability')
      const restored: Record<string, unknown> = {
        invoke_type: invokeType,
        payload_mapping: ensureObject(config.payload_mapping),
      }
      if (invokeType === 'capability') {
        restored.group_id = String(config.group_id || '')
        restored.capability_code = String(config.capability_code || config.tool_code || '')
      } else if (invokeType === 'api') {
        restored.url = String(config.url || '')
        restored.method = String(config.method || 'POST')
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
    case 'coordinator':
    case 'sub_agent':
    case 'tool':
    case 'message':
    case 'function':
    case 'end':
      return value
    case 'coordinate':
      return 'coordinator'
    default:
      return 'message'
  }
}

function resolveNodeLabel(nodeType: DesignerNodeType) {
  return nodeTemplates.find((template) => template.nodeType === nodeType)?.label || nodeType
}

function collectRemovableGraphIds(startGraphId: string, graphs: Record<string, WorkflowGraphState>) {
  const removable = new Set<string>()

  const visit = (graphId: string) => {
    if (!graphId || removable.has(graphId) || graphId === MAIN_GRAPH_ID || !graphs[graphId]) {
      return
    }
    removable.add(graphId)
    graphs[graphId].nodes.forEach((node) => {
      const data = node.data as CanvasNodeData
      if (data.nodeType !== 'sub_agent') return
      const childGraphId = String(data.config.subgraph_id || '').trim()
      if (childGraphId) {
        visit(childGraphId)
      }
    })
  }

  visit(startGraphId)
  return Array.from(removable)
}

function buildGraphParentMap(graphs: Record<string, WorkflowGraphState>) {
  const parentMap = new Map<string, string>()
  Object.values(graphs).forEach((graph) => {
    graph.nodes.forEach((node) => {
      const data = node.data as CanvasNodeData
      if (data.nodeType !== 'sub_agent') return
      const subgraphId = String(data.config.subgraph_id || '').trim()
      if (!subgraphId || parentMap.has(subgraphId) || !graphs[subgraphId]) return
      parentMap.set(subgraphId, graph.id)
    })
  })
  return parentMap
}

function resolveGraphBreadcrumb(
  currentGraphId: string,
  parentMap: Map<string, string>,
  graphs: Record<string, WorkflowGraphState>
) {
  if (!graphs[currentGraphId]) return [MAIN_GRAPH_ID]
  const chain: string[] = [currentGraphId]
  const visited = new Set<string>(chain)
  let cursor = currentGraphId
  while (cursor !== MAIN_GRAPH_ID) {
    const parent = parentMap.get(cursor)
    if (!parent || visited.has(parent)) {
      if (!visited.has(MAIN_GRAPH_ID) && graphs[MAIN_GRAPH_ID]) {
        chain.unshift(MAIN_GRAPH_ID)
      }
      break
    }
    chain.unshift(parent)
    visited.add(parent)
    cursor = parent
  }
  if (chain[0] !== MAIN_GRAPH_ID && graphs[MAIN_GRAPH_ID]) {
    chain.unshift(MAIN_GRAPH_ID)
  }
  return uniqueGraphOrder(chain)
}

function parseJsonObject(source?: string | null): Record<string, unknown> {
  if (!source || !source.trim()) return {}

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
  if (typeof value !== 'string') return value == null ? null : String(value)
  return value.trim() ? value : null
}

function numberValue(value: unknown) {
  const number = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(number) ? number : null
}

export default Orchestrator
