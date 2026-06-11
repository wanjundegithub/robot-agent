import { forwardRef, useEffect, useImperativeHandle, useMemo, useState, type MouseEvent as ReactMouseEvent } from 'react'
import ReactFlow, {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  Controls,
  MiniMap,
  PanOnScrollMode,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from 'reactflow'
import 'reactflow/dist/style.css'
import {
  getApiGroups,
  getApisByGroup,
  publishWorkflow,
  saveWorkflowDraft,
  testRunFunctionFragment,
  validateFunctionFragment,
  validateWorkflowDraft,
} from '../services/api'
import type {
  ApiGroupSummary,
  ApiItemSummary,
  FunctionFragmentTestRunResult,
  WorkflowDesignerDefinitionV2,
  WorkflowEditorSelection,
  WorkflowSnapshotV1,
  WorkflowValidationIssue,
} from '../types'

type DesignerNodeType = 'start' | 'coordinator' | 'sub_agent' | 'api' | 'message' | 'function' | 'end'
type VariableScope = 'global' | 'temp'
type ApiSchemaMappingField = 'payload_mapping' | 'output_mapping'
type WorkflowEditorColumnKey = 'graph' | 'canvas' | 'properties' | 'variables'
type VariableType =
  | 'String'
  | 'Number'
  | 'Boolean'
  | 'Object'
  | 'Array'

interface VariableDefinition {
  id: string
  name: string
  type: VariableType
  scope: VariableScope
  description: string
}

interface VariablePointerState {
  scope: VariableScope
  variableId: string
}

interface VariableContextMenuState extends VariablePointerState {
  x: number
  y: number
}

interface CanvasNodeData {
  label: string
  nodeType: DesignerNodeType
  config: Record<string, unknown>
}

interface WorkflowGraphState {
  id: string
  name: string
  description: string
  nodes: Node<CanvasNodeData>[]
  edges: Edge[]
}

interface WorkflowDraftPayload {
  workflowCode: string
  workflowName?: string
  workflowDescription?: string
  workflowVersion: string
  definition: WorkflowDesignerDefinitionV2
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
  workflowDescription: string
  draftVersion: string
  publishedVersion: string | null
  globalVariables: VariableDefinition[]
  tempVariables: VariableDefinition[]
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
  workflowDescription: string
  saveStatus: string
  isSaving: boolean
  isPublishing: boolean
  validationIssues: WorkflowValidationIssue[]
  summaryRules: WorkflowSummaryRule[]
}

export interface WorkflowVersionMutation {
  workflowCode: string
  version: string
  action: 'save_draft' | 'publish' | 'delete'
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

type FunctionFragmentValidationStatus = 'idle' | 'checking' | 'valid' | 'invalid' | 'error'
type FunctionTestVariableScope = 'global' | 'local'

interface FunctionFragmentValidationState {
  status: FunctionFragmentValidationStatus
  message: string
  line?: number | null
  column?: number | null
}

interface FunctionTestVariableReference {
  scope: FunctionTestVariableScope
  name: string
}

const DRAFT_VERSION = 'draft'
const WORKFLOW_SCHEMA_VERSION = 'workflow-designer/v2'
const MAIN_GRAPH_ID = 'main'
const workflowCanvasDefaultViewport = { x: 0, y: 0, zoom: 1 }
const workflowEditorColumnKeys: WorkflowEditorColumnKey[] = ['graph', 'canvas', 'properties', 'variables']
const workflowEditorInitialColumnRatios: Record<WorkflowEditorColumnKey, number> = {
  graph: 16,
  canvas: 44,
  properties: 20,
  variables: 20,
}
const workflowEditorMinColumnRatios: Record<WorkflowEditorColumnKey, number> = {
  graph: 10,
  canvas: 28,
  properties: 14,
  variables: 14,
}
const workflowResizeHandleWidth = 16
const variablePageSize = 6
const defaultFunctionTestVariablesText = JSON.stringify(
  {
    global: {
      user_name: '张三',
    },
    local: {
      order_id: 'A001',
    },
  },
  null,
  2
)

const variableTypeOptions: Array<{ value: VariableType; label: string }> = [
  { value: 'String', label: 'String' },
  { value: 'Number', label: 'Number' },
  { value: 'Boolean', label: 'Boolean' },
  { value: 'Object', label: 'Object' },
  { value: 'Array', label: 'Array' },
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
    nodeType: 'api',
    label: 'API节点',
    config: {
      invoke_type: 'api',
      group_id: '',
      api_id: '',
      request_url: '',
      input_schema: '',
      output_schema: '',
      payload_mapping: {},
      output_mapping: {},
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
      language: 'python',
      function_name: '',
      code: '',
      timeout_ms: 3000,
    },
  },
  {
    nodeType: 'end',
    label: '结束节点',
    config: {
      output_variable_ids: [],
    },
  },
]

const emptyVariableForm = {
  name: '',
  type: 'String' as VariableType,
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
    items: ['function', 'api'],
  },
]

const Orchestrator = forwardRef<OrchestratorHandle, OrchestratorProps>(function Orchestrator(
  {
    currentUserId,
    editorSelection,
    onWorkflowDraftChange,
    onWorkflowSidebarStateChange,
    onWorkflowVersionMutation,
  },
  ref
) {
  const [graphs, setGraphs] = useState<Record<string, WorkflowGraphState>>(() => ({
    [MAIN_GRAPH_ID]: createInitialGraph(MAIN_GRAPH_ID),
  }))
  const [graphOrder, setGraphOrder] = useState<string[]>([MAIN_GRAPH_ID])
  const [currentGraphId, setCurrentGraphId] = useState(MAIN_GRAPH_ID)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [workflowName, setWorkflowName] = useState('')
  const [workflowDescription, setWorkflowDescription] = useState('')
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
  const [editingVariable, setEditingVariable] = useState<VariablePointerState | null>(null)
  const [selectedVariable, setSelectedVariable] = useState<VariablePointerState | null>(null)
  const [variableContextMenu, setVariableContextMenu] = useState<VariableContextMenuState | null>(null)
  const [variablePages, setVariablePages] = useState<Record<VariableScope, number>>({ global: 1, temp: 1 })
  const [workflowColumnRatios, setWorkflowColumnRatios] = useState<Record<WorkflowEditorColumnKey, number>>(() => ({
    ...workflowEditorInitialColumnRatios,
  }))
  const [apiGroups, setApiGroups] = useState<ApiGroupSummary[]>([])
  const [apiItems, setApiItems] = useState<ApiItemSummary[]>([])
  const [selectedApiItem, setSelectedApiItem] = useState<ApiItemSummary | null>(null)
  const [functionValidation, setFunctionValidation] = useState<FunctionFragmentValidationState>({
    status: 'idle',
    message: '',
  })
  const [functionTestVariablesText, setFunctionTestVariablesText] = useState(defaultFunctionTestVariablesText)
  const [functionTestResult, setFunctionTestResult] = useState<FunctionFragmentTestRunResult | null>(null)
  const [functionTestError, setFunctionTestError] = useState('')
  const [isFunctionTestRunning, setIsFunctionTestRunning] = useState(false)
  const [functionTestDialogOpen, setFunctionTestDialogOpen] = useState(false)

  const currentGraph = graphs[currentGraphId] ?? createInitialGraph(currentGraphId)
  const currentGraphIsMain = currentGraphId === MAIN_GRAPH_ID
  const nodes = currentGraph.nodes
  const edges = currentGraph.edges
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedNodeData = (selectedNode?.data || null) as CanvasNodeData | null
  const selectedFunctionConfig = selectedNodeData?.nodeType === 'function' ? selectedNodeData.config : null
  const selectedFunctionCode = selectedFunctionConfig ? String(selectedFunctionConfig.code || '') : ''
  const selectedFunctionName = selectedFunctionConfig ? String(selectedFunctionConfig.function_name || '') : ''
  const selectedFunctionTimeoutMs = selectedFunctionConfig
    ? normalizeFunctionTimeoutMs(selectedFunctionConfig.timeout_ms)
    : 3000
  const functionTestVariableReferences = useMemo(
    () => extractFunctionTestVariableReferences(selectedFunctionCode),
    [selectedFunctionCode]
  )
  const functionTestRequiresVariables = functionTestVariableReferences.length > 0
  const allVariables = useMemo(() => [...globalVariables, ...tempVariables], [globalVariables, tempVariables])
  const variableNameMap = useMemo(() => new Map(allVariables.map((item) => [item.id, item])), [allVariables])

  const graphParentMap = useMemo(() => buildGraphParentMap(graphs), [graphs])
  const graphBreadcrumb = useMemo(
    () => resolveGraphBreadcrumb(currentGraphId, graphParentMap, graphs),
    [currentGraphId, graphParentMap, graphs]
  )
  const subgraphIds = useMemo(() => graphOrder.filter((graphId) => graphId !== MAIN_GRAPH_ID), [graphOrder])
  const visibleNodeGroups = currentGraphIsMain ? mainGraphNodeGroups : subflowNodeGroups
  const workflowEditorGridStyle = useMemo(() => {
    const gridTemplateColumns = workflowEditorColumnKeys
      .flatMap((key, index) => {
        const columnWidth = `minmax(0, ${workflowColumnRatios[key]}fr)`
        return index === workflowEditorColumnKeys.length - 1
          ? [columnWidth]
          : [columnWidth, `${workflowResizeHandleWidth}px`]
      })
      .join(' ')
    return { gridTemplateColumns }
  }, [workflowColumnRatios])

  const loadApiItems = async (groupId: number) => {
    if (!groupId) {
      setApiItems([])
      return
    }
    try {
      const items = await getApisByGroup(groupId)
      setApiItems(items.filter((item) => String(item.status || '').toUpperCase() !== 'DISABLED'))
    } catch (error) {
      console.error('Failed to load api node options:', error)
      setApiItems([])
    }
  }

  const updateCurrentGraph = (updater: (graph: WorkflowGraphState) => WorkflowGraphState) => {
    setGraphs((prev) => {
      const current = prev[currentGraphId] ?? createInitialGraph(currentGraphId)
      return { ...prev, [currentGraphId]: updater(current) }
    })
  }

  const startWorkflowColumnResize = (
    leftKey: WorkflowEditorColumnKey,
    rightKey: WorkflowEditorColumnKey,
    event: ReactMouseEvent<HTMLDivElement>
  ) => {
    event.preventDefault()
    const startX = event.clientX
    const startLeftRatio = workflowColumnRatios[leftKey]
    const startRightRatio = workflowColumnRatios[rightKey]
    const minLeftRatio = workflowEditorMinColumnRatios[leftKey]
    const minRightRatio = workflowEditorMinColumnRatios[rightKey]
    const totalRatio = workflowEditorColumnKeys.reduce((sum, key) => sum + workflowColumnRatios[key], 0)
    const parentWidth = event.currentTarget.parentElement?.getBoundingClientRect().width || 1
    const availableWidth = Math.max(1, parentWidth - workflowResizeHandleWidth * (workflowEditorColumnKeys.length - 1))

    const handleMouseMove = (moveEvent: MouseEvent) => {
      const rawDelta = moveEvent.clientX - startX
      const ratioDelta = (rawDelta * totalRatio) / availableWidth
      const constrainedDelta = Math.max(
        minLeftRatio - startLeftRatio,
        Math.min(ratioDelta, startRightRatio - minRightRatio)
      )
      setWorkflowColumnRatios((prev) => ({
        ...prev,
        [leftKey]: startLeftRatio + constrainedDelta,
        [rightKey]: startRightRatio - constrainedDelta,
      }))
    }

    const stopResize = () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', stopResize)
    }

    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', stopResize)
  }

  const updateWorkflowName = (name: string) => {
    setWorkflowName(name)
    setGraphs((prev) => {
      const mainGraph = prev[MAIN_GRAPH_ID] ?? createInitialGraph(MAIN_GRAPH_ID)
      if (mainGraph.name === name) return prev
      return { ...prev, [MAIN_GRAPH_ID]: { ...mainGraph, name } }
    })
  }

  const updateWorkflowDescription = (description: string) => {
    setWorkflowDescription(description)
    setGraphs((prev) => {
      const mainGraph = prev[MAIN_GRAPH_ID] ?? createInitialGraph(MAIN_GRAPH_ID)
      if (mainGraph.description === description) return prev
      return { ...prev, [MAIN_GRAPH_ID]: { ...mainGraph, description } }
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
    if (!selectedFunctionConfig) {
      setFunctionValidation({ status: 'idle', message: '' })
      setFunctionTestResult(null)
      setFunctionTestError('')
      setFunctionTestDialogOpen(false)
      return
    }
    if (!selectedFunctionCode.trim()) {
      setFunctionValidation({ status: 'idle', message: '' })
      return
    }

    let cancelled = false
    setFunctionValidation({ status: 'checking', message: '校验中...' })
    const timer = window.setTimeout(() => {
      void (async () => {
        try {
          const result = await validateFunctionFragment({
            language: 'python',
            function_name: selectedFunctionName,
            code: selectedFunctionCode,
            timeout_ms: selectedFunctionTimeoutMs,
          })
          if (cancelled) return
          setFunctionValidation({
            status: result.valid ? 'valid' : 'invalid',
            message: result.valid ? '校验通过' : result.error_message || '校验失败',
            line: result.line,
            column: result.column,
          })
        } catch (error) {
          if (cancelled) return
          setFunctionValidation({
            status: 'error',
            message: error instanceof Error ? error.message : '校验失败',
          })
        }
      })()
    }, 500)

    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [selectedFunctionCode, selectedFunctionConfig, selectedFunctionName, selectedFunctionTimeoutMs])

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
        const groups = await getApiGroups()
        setApiGroups(groups.filter((group) => String(group.status || '').toUpperCase() !== 'DISABLED'))
      } catch (error) {
        console.error('Failed to load API groups for orchestrator:', error)
      }
    })()
  }, [])

  useEffect(() => {
    const config = selectedNodeData?.config || {}
    if (selectedNodeData?.nodeType !== 'api') {
      setApiItems([])
      setSelectedApiItem(null)
      return
    }
    const groupId = Number(config.group_id || 0)
    if (!groupId) {
      setApiItems([])
      setSelectedApiItem(null)
      return
    }

    void loadApiItems(groupId)
  }, [selectedNodeData])

  useEffect(() => {
    const config = selectedNodeData?.config || {}
    if (selectedNodeData?.nodeType !== 'api') {
      setSelectedApiItem(null)
      return
    }
    const groupId = Number(config.group_id || 0)
    const apiId = Number(config.api_id || 0)
    if (!groupId || !apiId) {
      setSelectedApiItem(null)
      return
    }

    setSelectedApiItem(apiItems.find((item) => item.id === apiId) ?? null)
  }, [apiItems, selectedNodeData])

  const summaryRules = useMemo<WorkflowSummaryRule[]>(() => {
    const mainNodes = graphs[MAIN_GRAPH_ID]?.nodes ?? []
    const coordinatorCount = mainNodes.filter(
      (node) => (node.data as CanvasNodeData).nodeType === 'coordinator'
    ).length
    const mainNodeTypesValid = mainNodes.every((node) =>
      ['coordinator', 'sub_agent'].includes((node.data as CanvasNodeData).nodeType)
    )
    const subflowGraphs = Object.values(graphs).filter((graph) => graph.id !== MAIN_GRAPH_ID)
    const allGraphsHaveNames = Object.values(graphs).every((graph) => graph.name.trim().length > 0)
    const allGraphsHaveDescriptions = Object.values(graphs).every((graph) => graph.description.trim().length > 0)
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
      { label: '每个流程都必须填写名称', valid: allGraphsHaveNames },
      { label: '每个流程都必须填写描述', valid: allGraphsHaveDescriptions },
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
    const definition: WorkflowDesignerDefinitionV2 = {
      schema_version: WORKFLOW_SCHEMA_VERSION,
      workflow_code: workflowMeta.workflowCode,
      workflow_name: workflowName.trim(),
      workflow_description: workflowDescription.trim(),
      workflow_version: workflowMeta.draftVersion,
      main_graph_id: MAIN_GRAPH_ID,
      graphs: Object.fromEntries(
        normalizedGraphs.map((graph) => [graph.id, toDefinitionGraph(graph, variableNameMap)])
      ),
      variables: {
        global: globalVariables,
        temporary: tempVariables,
      },
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
    return definition
  }, [
    currentGraphId,
    currentUserId,
    globalVariables,
    graphOrder,
    graphs,
    tempVariables,
    variableNameMap,
    workflowMeta.draftVersion,
    workflowMeta.workflowCode,
    workflowName,
    workflowDescription,
  ])

  const compatibilityWorkflowConfig = useMemo(() => {
    const config: Record<string, unknown> = {
      schema_version: WORKFLOW_SCHEMA_VERSION,
      main_graph_id: MAIN_GRAPH_ID,
      variable_registry: {
        global: globalVariables,
        temporary: tempVariables,
      },
    }
    return config
  }, [globalVariables, tempVariables])

  useEffect(() => {
    onWorkflowDraftChange?.({
      workflowCode: workflowMeta.workflowCode,
      workflowDescription: workflowDescription.trim(),
      workflowVersion: workflowMeta.draftVersion,
      definition: currentDefinition,
      workflowConfig: compatibilityWorkflowConfig,
    })
  }, [compatibilityWorkflowConfig, currentDefinition, onWorkflowDraftChange, workflowMeta])

  useEffect(() => {
    onWorkflowSidebarStateChange?.({
      workflowId: workflowMeta.workflowId,
      workflowCode: workflowMeta.workflowCode,
      draftVersion: workflowMeta.draftVersion,
      publishedVersion: workflowMeta.publishedVersion,
      workflowName,
      workflowDescription,
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
    workflowDescription,
  ])

  useEffect(() => {
    if (!editorSelection) return
    const hydrated = hydrateWorkflowSelection(editorSelection)
    setGraphs(hydrated.graphs)
    setGraphOrder(hydrated.graphOrder)
    setCurrentGraphId(hydrated.currentGraphId)
    setSelectedNodeId(null)
    setWorkflowName(hydrated.workflowName)
    setWorkflowDescription(hydrated.workflowDescription)
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
    setSaveStatus(`已加载版本 ${editorSelection.version.version}`)
  }, [editorSelection])

  const ensureWorkflowBasics = () => {
    const trimmedName = workflowName.trim() || (graphs[MAIN_GRAPH_ID]?.name || '').trim()
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
    const resolvedWorkflowDescription = workflowDescription.trim() || (graphs[MAIN_GRAPH_ID]?.description || '').trim()

    const definition = {
      ...currentDefinition,
      workflow_code: basics.workflowCode,
      workflow_name: basics.workflowName,
      workflow_description: resolvedWorkflowDescription,
      workflow_version: version,
    }
    const workflowSnapshot = buildWorkflowSnapshot({
      workflowCode: basics.workflowCode,
      workflowName: basics.workflowName,
      workflowDescription: resolvedWorkflowDescription,
      workflowVersion: version,
      definition,
      workflowConfig: compatibilityWorkflowConfig,
    })

    const response = await saveWorkflowDraft(basics.workflowCode, {
      workflowName: basics.workflowName,
      workflowDescription: resolvedWorkflowDescription,
      version,
      definition,
      workflowConfig: compatibilityWorkflowConfig,
      workflowSnapshot: workflowSnapshot as unknown as Record<string, unknown>,
      currentUserId,
    })

    setWorkflowMeta((prev) => ({
      workflowId: response.workflowId ?? prev.workflowId,
      workflowCode: response.workflowCode || basics.workflowCode,
      draftVersion: DRAFT_VERSION,
      publishedVersion: version === DRAFT_VERSION ? prev.publishedVersion : version,
    }))
    if (response.workflowName) {
      updateWorkflowName(response.workflowName)
    }
    if (typeof response.workflowDescription === 'string') {
      updateWorkflowDescription(response.workflowDescription)
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

    const localIssues = collectLocalWorkflowValidationIssues(graphs)
    if (localIssues.length > 0) {
      setValidationIssues(localIssues)
      setSaveStatus(`发现 ${localIssues.length} 个校验问题。`)
      return
    }

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

    const localIssues = collectLocalWorkflowValidationIssues(graphs)
    if (localIssues.length > 0) {
      setValidationIssues(localIssues)
      setSaveStatus(`发布被阻止，仍有 ${localIssues.length} 个校验问题待处理。`)
      return
    }

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

  const updateSelectedSubAgentFlowName = (name: string) => {
    if (!selectedNodeId || selectedNodeData?.nodeType !== 'sub_agent') return
    const linkedSubgraphId = String(selectedNodeData.config.subgraph_id || '').trim()
    setGraphs((prev) => {
      const current = prev[currentGraphId] ?? createInitialGraph(currentGraphId)
      const nextGraphs = {
        ...prev,
        [currentGraphId]: {
          ...current,
          nodes: current.nodes.map((node) =>
            node.id === selectedNodeId ? { ...node, data: { ...(node.data as CanvasNodeData), label: name } } : node
          ),
        },
      }
      if (linkedSubgraphId && nextGraphs[linkedSubgraphId]) {
        nextGraphs[linkedSubgraphId] = { ...nextGraphs[linkedSubgraphId], name }
      }
      return nextGraphs
    })
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

  const openFunctionTestDialog = () => {
    if (!selectedFunctionConfig || !selectedFunctionCode.trim()) return
    setFunctionTestError('')
    setFunctionTestResult(null)
    if (functionTestRequiresVariables) {
      setFunctionTestVariablesText((current) =>
        buildFunctionTestVariablesText(functionTestVariableReferences, current)
      )
    }
    setFunctionTestDialogOpen(true)
  }

  const handleFunctionTestRun = async () => {
    if (!selectedFunctionConfig) return
    setFunctionTestError('')
    setFunctionTestResult(null)

    let normalizedVariables = normalizeFunctionTestVariables(null)
    if (functionTestRequiresVariables) {
      try {
        normalizedVariables = normalizeFunctionTestVariables(JSON.parse(functionTestVariablesText))
      } catch {
        setFunctionTestError('测试变量 JSON 格式错误')
        return
      }
    }
    setIsFunctionTestRunning(true)
    try {
      const result = await testRunFunctionFragment({
        language: 'python',
        function_name: selectedFunctionName,
        code: selectedFunctionCode,
        timeout_ms: selectedFunctionTimeoutMs,
        variables: normalizedVariables,
      })
      setFunctionTestResult(result)
      if (!result.success) {
        setFunctionTestError(result.error_message || '测试运行失败')
      }
    } catch (error) {
      setFunctionTestError(error instanceof Error ? error.message : '测试运行失败')
    } finally {
      setIsFunctionTestRunning(false)
    }
  }

  const selectApiGroup = (groupId: string) => {
    if (!selectedNodeData) return
    replaceSelectedConfig({
      ...structuredClone(selectedNodeData.config || {}),
      group_id: groupId,
      api_id: '',
      request_url: '',
      input_schema: '',
      output_schema: '',
      payload_mapping: {},
      output_mapping: {},
    })
    setSelectedApiItem(null)
  }

  const selectApiItem = (apiIdValue: string) => {
    if (!selectedNodeData) return
    const selectedApi = apiItems.find((item) => String(item.id) === apiIdValue)
    replaceSelectedConfig({
      ...structuredClone(selectedNodeData.config || {}),
      api_id: apiIdValue,
      request_url: selectedApi?.requestUrl || '',
      input_schema: selectedApi?.inputSchema || '',
      output_schema: selectedApi?.outputSchema || '',
      payload_mapping: {},
      output_mapping: {},
    })
    setSelectedApiItem(selectedApi ?? null)
  }

  const addSelectedVariable = (field: 'input_variable_ids' | 'output_variable_ids') => {
    if (!selectedNodeData) return
    const current = Array.isArray(selectedNodeData.config[field]) ? [...(selectedNodeData.config[field] as string[])] : []
    updateSelectedConfigField(field, [...current, ''])
  }

  const updateSelectedVariable = (
    field: 'input_variable_ids' | 'output_variable_ids',
    index: number,
    variableId: string
  ) => {
    if (!selectedNodeData) return
    const current = Array.isArray(selectedNodeData.config[field]) ? [...(selectedNodeData.config[field] as string[])] : []
    const next = current.map((item, itemIndex) => (itemIndex === index ? variableId : item))
    updateSelectedConfigField(field, next)
  }

  const removeSelectedVariable = (field: 'input_variable_ids' | 'output_variable_ids', index: number) => {
    if (!selectedNodeData) return
    const current = Array.isArray(selectedNodeData.config[field]) ? [...(selectedNodeData.config[field] as string[])] : []
    updateSelectedConfigField(
      field,
      current.filter((_, itemIndex) => itemIndex !== index)
    )
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

  useEffect(() => {
    if (!variableContextMenu) return
    const closeContextMenu = () => setVariableContextMenu(null)
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setVariableContextMenu(null)
      }
    }

    window.addEventListener('click', closeContextMenu)
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      window.removeEventListener('click', closeContextMenu)
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [variableContextMenu])

  const resetVariableForm = () => {
    setVariableForm(emptyVariableForm)
    setEditingVariable(null)
    setVariableContextMenu(null)
  }

  const beginEditVariable = (scope: VariableScope, variableId: string) => {
    const source = scope === 'global' ? globalVariables : tempVariables
    const variable = source.find((item) => item.id === variableId)
    if (!variable) return
    setSelectedVariable({ scope, variableId })
    setEditingVariable({ scope, variableId })
    setVariableForm({
      name: variable.name,
      type: variable.type,
      scope: variable.scope,
      description: variable.description,
    })
    setVariableContextMenu(null)
  }

  const submitVariable = () => {
    const name = variableForm.name.trim()
    if (!name) return

    if (editingVariable) {
      const setter = editingVariable.scope === 'global' ? setGlobalVariables : setTempVariables
      setter((prev) =>
        prev.map((item) =>
          item.id === editingVariable.variableId
            ? {
                ...item,
                name,
                type: variableForm.type,
                description: variableForm.description.trim(),
              }
            : item
        )
      )
      resetVariableForm()
      return
    }

    const currentLength = variableForm.scope === 'global' ? globalVariables.length : tempVariables.length
    const nextVariable: VariableDefinition = {
      id: `${variableForm.scope}_${Date.now()}_${currentLength + 1}`,
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
    resetVariableForm()
  }

  const removeVariable = (scope: VariableScope, variableId: string) => {
    const setter = scope === 'global' ? setGlobalVariables : setTempVariables
    const currentLength = scope === 'global' ? globalVariables.length : tempVariables.length
    setter((prev) => prev.filter((item) => item.id !== variableId))
    setVariablePages((prev) => ({
      ...prev,
      [scope]: Math.max(1, Math.min(prev[scope], Math.ceil(Math.max(currentLength - 1, 0) / variablePageSize))),
    }))
    setSelectedVariable((prev) => (prev?.scope === scope && prev.variableId === variableId ? null : prev))
    if (editingVariable?.scope === scope && editingVariable.variableId === variableId) {
      setEditingVariable(null)
      setVariableForm(emptyVariableForm)
    }
    setVariableContextMenu(null)
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
    if (currentGraphId === MAIN_GRAPH_ID) {
      setWorkflowName(name)
    }
    setGraphs((prev) =>
      syncLinkedSubAgentMetadata(
        {
          ...prev,
          [currentGraphId]: {
            ...(prev[currentGraphId] ?? createInitialGraph(currentGraphId)),
            name,
          },
        },
        currentGraphId,
        { name }
      )
    )
  }

  const updateSelectedNodeDescription = (description: string) => {
    if (!selectedNodeId) return
    updateCurrentGraph((graph) => ({
      ...graph,
      nodes: graph.nodes.map((node) => {
        if (node.id !== selectedNodeId) return node
        const data = node.data as CanvasNodeData
        return {
          ...node,
          data: {
            ...data,
            config: {
              ...data.config,
              description,
            },
          },
        }
      }),
    }))
  }

  const updateSelectedSubAgentFlowDescription = (description: string) => {
    if (!selectedNodeId || selectedNodeData?.nodeType !== 'sub_agent') return
    const linkedSubgraphId = String(selectedNodeData.config.subgraph_id || '').trim()
    setGraphs((prev) => {
      const current = prev[currentGraphId] ?? createInitialGraph(currentGraphId)
      const nextGraphs = {
        ...prev,
        [currentGraphId]: {
          ...current,
          nodes: current.nodes.map((node) => {
            if (node.id !== selectedNodeId) return node
            const data = node.data as CanvasNodeData
            return {
              ...node,
              data: {
                ...data,
                config: {
                  ...data.config,
                  description,
                },
              },
            }
          }),
        },
      }
      if (linkedSubgraphId && nextGraphs[linkedSubgraphId]) {
        nextGraphs[linkedSubgraphId] = { ...nextGraphs[linkedSubgraphId], description }
      }
      return nextGraphs
    })
  }

  const updateCurrentGraphDescription = (description: string) => {
    if (currentGraphId === MAIN_GRAPH_ID) {
      setWorkflowDescription(description)
    }
    setGraphs((prev) =>
      syncLinkedSubAgentMetadata(
        {
          ...prev,
          [currentGraphId]: {
            ...(prev[currentGraphId] ?? createInitialGraph(currentGraphId)),
            description,
          },
        },
        currentGraphId,
        { description }
      )
    )
  }

  const bindAndOpenSubgraph = () => {
    if (!selectedNodeId || !selectedNodeData || selectedNodeData.nodeType !== 'sub_agent') return
    const configured = String(selectedNodeData.config.subgraph_id || '').trim()
    const subgraphId = configured || `subgraph_${selectedNodeId}`
    const subgraphDescription = String(selectedNodeData.config.description || '').trim()
    const nextConfig = structuredClone(selectedNodeData.config || {})
    nextConfig.subgraph_id = subgraphId
    replaceSelectedConfig(nextConfig)
    setGraphs((prev) => {
      if (prev[subgraphId]) {
        return {
          ...prev,
          [subgraphId]: {
            ...prev[subgraphId],
            name: selectedNodeData.label,
            description: subgraphDescription,
          },
        }
      }
      return {
        ...prev,
        [subgraphId]: createInitialGraph(subgraphId, selectedNodeData.label, subgraphDescription),
      }
    })
    setGraphOrder((prev) => (prev.includes(subgraphId) ? prev : [...prev, subgraphId]))
    setCurrentGraphId(subgraphId)
    setSelectedNodeId(null)
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
    const availableVariables = allVariables.filter((variable) => !selectedIds.includes(variable.id))
    const fieldLabel = field === 'input_variable_ids' ? '输入变量' : '输出变量'
    const testPrefix = field === 'input_variable_ids' ? 'workflow-input-variable' : 'workflow-output-variable'

    return (
      <div className="space-y-2">
        {allVariables.length === 0 && <div className="text-xs text-slate-500">请先在下方面板创建变量。</div>}
        {selectedIds.length === 0 && allVariables.length > 0 && (
          <div className="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500">
            点击 + 选择变量管理中已维护的变量。
          </div>
        )}
        {selectedIds.map((selectedId, index) => {
          const selectedVariable = allVariables.find((variable) => variable.id === selectedId)
          const rowOptions = selectedVariable ? [selectedVariable, ...availableVariables] : availableVariables
          const selectValue = selectedVariable ? selectedVariable.id : ''

          return (
            <div key={`${field}_${index}_${selectedId || 'empty'}`} className="rounded-lg border border-slate-200 px-3 py-2">
              <div className="flex items-start gap-2">
                <select
                  value={selectValue}
                  onChange={(event) => updateSelectedVariable(field, index, event.target.value)}
                  className="min-w-0 flex-1 rounded-lg border border-slate-200 px-2 py-2 text-sm"
                  data-testid={`${testPrefix}-select`}
                  aria-label={`${fieldLabel}变量`}
                >
                  {!selectedVariable && <option value="">请选择变量</option>}
                  {rowOptions.map((variable) => (
                    <option key={variable.id} value={variable.id}>
                      {variable.name}（{displayVariableScope(variable.scope)} / {displayVariableType(variable.type)}）
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="rounded-lg border border-rose-200 px-3 py-2 text-sm font-semibold text-rose-500 hover:bg-rose-50"
                  onClick={() => removeSelectedVariable(field, index)}
                  data-testid={`${testPrefix}-remove`}
                  aria-label={`移除${fieldLabel}`}
                >
                  -
                </button>
              </div>
              {selectedVariable && (
                <div className="mt-2 text-xs text-slate-500">
                  <span className="font-medium text-slate-600">{selectedVariable.name}</span>
                  <span className="ml-2 text-slate-400">
                    {displayVariableScope(selectedVariable.scope)} / {displayVariableType(selectedVariable.type)}
                  </span>
                  {selectedVariable.description && <div className="mt-1">{selectedVariable.description}</div>}
                </div>
              )}
            </div>
          )
        })}
        {allVariables.length > 0 && (
          <button
            type="button"
            className="flex w-full items-center justify-center rounded-lg border border-dashed border-sky-300 px-3 py-2 text-sm font-semibold text-sky-600 hover:bg-sky-50 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300 disabled:hover:bg-transparent"
            onClick={() => addSelectedVariable(field)}
            disabled={availableVariables.length === 0}
            data-testid={`${testPrefix}-add`}
            aria-label={`增加${fieldLabel}`}
          >
            + 变量
          </button>
        )}
      </div>
    )
  }

  const updateApiSchemaVariableMapping = (field: ApiSchemaMappingField, parameterName: string, variableId: string) => {
    if (!selectedNodeData) return
    const selectedVariable = variableNameMap.get(variableId)
    const nextMapping = { ...ensureObject(selectedNodeData.config[field]) }
    if (selectedVariable) {
      nextMapping[parameterName] = formatVariableReference(selectedVariable)
    } else {
      delete nextMapping[parameterName]
    }
    updateSelectedConfigField(field, nextMapping)
  }

  const renderApiSchemaVariableMapping = (
    rawSchema: string,
    requestUrl: string,
    field: ApiSchemaMappingField,
    emptyText: string,
    testPrefix: string
  ) => {
    const parameters = field === 'payload_mapping'
      ? extractApiInputParameters(rawSchema, requestUrl)
      : extractSchemaParameters(rawSchema)
    if (parameters.length === 0) {
      return <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-400">{emptyText}</div>
    }

    const currentMapping = ensureObject(selectedNodeData?.config[field])

    return (
      <div className="space-y-2">
        {parameters.map((parameter) => {
          const selectedVariable = resolveMappedVariable(currentMapping[parameter.name], allVariables)
          const missingRequired = parameter.required && !selectedVariable
          return (
            <div key={parameter.name} className={`grid grid-cols-[minmax(0,1fr)_minmax(0,1.35fr)] items-center gap-2 rounded-xl border px-3 py-2 text-xs ${missingRequired ? 'border-rose-300 bg-rose-50/70 text-rose-700' : 'border-slate-200 bg-white text-slate-600'}`}>
              <div className="min-w-0">
                <div className={`truncate font-semibold ${missingRequired ? 'text-rose-700' : 'text-slate-700'}`} title={parameter.name}>
                  {parameter.name}
                </div>
                {missingRequired && <div className="mt-1 text-[11px] text-rose-500">必填，请选择变量</div>}
              </div>
              <select
                value={selectedVariable?.id || ''}
                onChange={(event) => updateApiSchemaVariableMapping(field, parameter.name, event.target.value)}
                className={`min-w-0 rounded-lg border px-2 py-2 text-sm text-slate-700 ${missingRequired ? 'border-rose-400 bg-white ring-1 ring-rose-200' : 'border-slate-200'}`}
                data-testid={`${testPrefix}-${parameter.name}-variable-select`}
                aria-label={`${parameter.name}变量`}
                aria-invalid={missingRequired}
              >
                <option value="">选择全局/临时变量</option>
                {allVariables.map((variable) => (
                  <option key={variable.id} value={variable.id}>
                    {variable.name}（{displayVariableScope(variable.scope)}）
                  </option>
                ))}
              </select>
            </div>
          )
        })}
        {allVariables.length === 0 && <div className="text-xs text-slate-500">请先在右侧变量面板创建全局变量或临时变量。</div>}
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
                  data-testid="workflow-current-graph-name-input"
                  placeholder={currentGraphId === MAIN_GRAPH_ID ? '流程名称' : '子流程名称'}
                />
              </label>
              <label className="block space-y-2">
                <span className="text-xs font-medium text-slate-500">流程描述</span>
                <textarea
                  value={currentGraph.description}
                  onChange={(event) => updateCurrentGraphDescription(event.target.value)}
                  className="min-h-[96px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  data-testid="workflow-current-graph-description-input"
                  placeholder={currentGraphId === MAIN_GRAPH_ID ? '流程描述' : '子流程描述'}
                />
              </label>
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
        <label className="block space-y-2">
          <span className="text-xs font-medium text-slate-500">
            {nodeType === 'sub_agent' ? '流程名称' : '节点名称'}
          </span>
          <input
            value={selectedNodeData.label}
            onChange={(event) =>
              nodeType === 'sub_agent'
                ? updateSelectedSubAgentFlowName(event.target.value)
                : updateSelectedNodeLabel(event.target.value)
            }
            className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
            placeholder={nodeType === 'sub_agent' ? '流程名称' : '节点名称'}
          />
        </label>
        {!['sub_agent', 'start', 'end'].includes(nodeType) && (
          <div className="text-xs text-slate-400">节点类型：{displayNodeType(nodeType)}</div>
        )}

        {(nodeType === 'coordinator' || nodeType === 'sub_agent') && (
          <label className="block space-y-2">
            <span className="text-xs font-medium text-slate-500">
              {nodeType === 'sub_agent' ? '流程描述' : '节点描述'}
            </span>
            <textarea
              value={String(config.description || '')}
              onChange={(event) =>
                nodeType === 'sub_agent'
                  ? updateSelectedSubAgentFlowDescription(event.target.value)
                  : updateSelectedNodeDescription(event.target.value)
              }
              className="min-h-[100px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              data-testid="workflow-node-description-input"
              placeholder={nodeType === 'sub_agent' ? '流程描述' : '节点描述'}
            />
          </label>
        )}

        {nodeType === 'start' && (
          <div>
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">输入变量</div>
            {renderVariableListSelector('input_variable_ids')}
          </div>
        )}

        {nodeType === 'sub_agent' && (
          <button className="prompt-secondary w-full" type="button" onClick={bindAndOpenSubgraph} data-testid="workflow-open-subgraph">
            进入子流程画布
          </button>
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
          <div className="space-y-3">
            <label className="block space-y-2">
              <span className="text-xs font-medium text-slate-500">函数名称</span>
              <input
                value={String(config.function_name || '')}
                onChange={(event) => updateSelectedConfigField('function_name', event.target.value)}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                data-testid="workflow-function-name-input"
                placeholder="处理订单变量"
              />
            </label>
            <label className="block space-y-2">
              <span className="text-xs font-medium text-slate-500">函数(Python)</span>
              <textarea
                value={String(config.code || '')}
                onChange={(event) => updateSelectedConfigField('code', event.target.value)}
                className="min-h-[180px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs leading-5"
                data-testid="workflow-function-code-input"
                placeholder={`print('开始处理')\nctx['local']['result'] = 'ok'`}
                spellCheck={false}
              />
            </label>
            <label className="block space-y-2">
              <span className="text-xs font-medium text-slate-500">超时时间 ms</span>
              <input
                type="number"
                min={1}
                max={30000}
                value={selectedFunctionTimeoutMs}
                onChange={(event) => updateSelectedConfigField('timeout_ms', normalizeFunctionTimeoutMs(event.target.value))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                data-testid="workflow-function-timeout-input"
              />
            </label>
            {functionValidation.message && (
              <div
                className={`rounded-xl border px-3 py-2 text-xs ${
                  functionValidation.status === 'valid'
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : functionValidation.status === 'invalid' || functionValidation.status === 'error'
                      ? 'border-rose-200 bg-rose-50 text-rose-700'
                      : 'border-slate-200 bg-slate-50 text-slate-500'
                }`}
                data-testid="workflow-function-validation-status"
              >
                <span>{functionValidation.message}</span>
                {functionValidation.line != null && (
                  <span className="ml-2">
                    行 {functionValidation.line}
                    {functionValidation.column != null ? ` 列 ${functionValidation.column}` : ''}
                  </span>
                )}
              </div>
            )}
            <button
              className="prompt-secondary w-full"
              type="button"
              onClick={openFunctionTestDialog}
              disabled={isFunctionTestRunning || !String(config.code || '').trim()}
              data-testid="workflow-function-test-run"
            >
              测试运行
            </button>
          </div>
        )}

        {nodeType === 'api' && (
          <>
            <select
              value={String(config.group_id || '')}
              onChange={(event) => selectApiGroup(event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              data-testid="workflow-api-group-select"
            >
              <option value="">选择API组</option>
              {apiGroups.map((group) => (
                <option key={group.id} value={group.id}>
                  {group.groupName}
                </option>
              ))}
            </select>
            <select
              value={String(config.api_id || '')}
              onChange={(event) => selectApiItem(event.target.value)}
              className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
              data-testid="workflow-api-item-select"
            >
              <option value="">选择 API</option>
              {apiItems.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.apiName}
                </option>
              ))}
            </select>

            <div data-testid="workflow-api-input-parameters">
              <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">输入参数</div>
              {renderApiSchemaVariableMapping(
                selectedApiItem?.inputSchema ?? String(config.input_schema || ''),
                selectedApiItem?.requestUrl ?? String(config.request_url || ''),
                'payload_mapping',
                '暂无输入参数',
                'workflow-api-input-parameter'
              )}
            </div>
            <div data-testid="workflow-api-output-parameters">
              <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">输出参数</div>
              {renderApiSchemaVariableMapping(
                selectedApiItem?.outputSchema ?? String(config.output_schema || ''),
                '',
                'output_mapping',
                '暂无输出参数',
                'workflow-api-output-parameter'
              )}
            </div>
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

  const renderWorkflowResizeHandle = (
    leftKey: WorkflowEditorColumnKey,
    rightKey: WorkflowEditorColumnKey,
    testId: string,
    label: string
  ) => (
    <div
      className="flex min-h-0 cursor-col-resize items-stretch justify-center px-1"
      role="separator"
      aria-label={label}
      aria-orientation="vertical"
      data-testid="workflow-resize-handle"
      onMouseDown={(event) => startWorkflowColumnResize(leftKey, rightKey, event)}
    >
      <div
        className="h-full w-1 rounded-full bg-slate-200 transition hover:bg-sky-300"
        data-testid={testId}
      />
    </div>
  )

  const renderVariableManager = (scope: VariableScope, items: VariableDefinition[]) => {
    const totalPages = Math.max(1, Math.ceil(items.length / variablePageSize))
    const currentPage = Math.min(variablePages[scope], totalPages)
    const startIndex = (currentPage - 1) * variablePageSize
    const pageItems = items.slice(startIndex, startIndex + variablePageSize)

    return (
      <div className="space-y-2" data-testid={`workflow-variable-list-${scope}`}>
        {items.length === 0 && (
          <div className="text-xs text-slate-500">{scope === 'global' ? '暂无全局变量。' : '暂无临时变量。'}</div>
        )}
        {pageItems.map((item) => {
          const selected = selectedVariable?.scope === scope && selectedVariable.variableId === item.id
          return (
            <button
              key={item.id}
              className={`w-full truncate rounded-lg border px-3 py-2 text-left text-sm transition ${
                selected ? 'border-sky-300 bg-sky-50 text-sky-700' : 'border-slate-200 bg-white text-slate-700'
              }`}
              type="button"
              data-testid="workflow-variable-name-item"
              aria-selected={selected}
              onClick={() => {
                setSelectedVariable({ scope, variableId: item.id })
                beginEditVariable(scope, item.id)
              }}
              onContextMenu={(event) => {
                event.preventDefault()
                event.stopPropagation()
                setSelectedVariable({ scope, variableId: item.id })
                setVariableContextMenu({ scope, variableId: item.id, x: event.clientX, y: event.clientY })
              }}
            >
              {item.name}
            </button>
          )
        })}
        {items.length > variablePageSize && (
          <div className="flex items-center justify-between gap-2 text-xs text-slate-500">
            <button
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 disabled:opacity-40"
              type="button"
              disabled={currentPage <= 1}
              data-testid={`workflow-variable-page-prev-${scope}`}
              onClick={() => setVariablePages((prev) => ({ ...prev, [scope]: Math.max(1, currentPage - 1) }))}
            >
              上一页
            </button>
            <span data-testid={`workflow-variable-page-summary-${scope}`}>
              {currentPage} / {totalPages}
            </span>
            <button
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 disabled:opacity-40"
              type="button"
              disabled={currentPage >= totalPages}
              data-testid={`workflow-variable-page-next-${scope}`}
              onClick={() => setVariablePages((prev) => ({ ...prev, [scope]: Math.min(totalPages, currentPage + 1) }))}
            >
              下一页
            </button>
          </div>
        )}
      </div>
    )
  }

  const renderWorkflowInfoPanel = () => (
    <div
      className="h-full min-h-0 rounded-3xl border border-slate-200 bg-white/95 p-4"
      data-testid="workflow-info-panel"
    >
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="panel-title">工作流信息</div>
          <div className="mt-2 text-sm text-slate-500">维护名称并发布版本</div>
        </div>
      </div>
      <div className="space-y-3">
        <input
          value={workflowName}
          onChange={(event) => updateWorkflowName(event.target.value)}
          className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
          data-testid="workflow-name-input"
          placeholder="工作流名称"
        />
        <textarea
          value={workflowDescription}
          onChange={(event) => updateWorkflowDescription(event.target.value)}
          className="min-h-[80px] w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
          data-testid="workflow-description-input"
          placeholder="工作流描述"
        />
        <button
          className="prompt-primary w-full"
          type="button"
          onClick={() => void handlePublish()}
          disabled={isPublishing}
          data-testid="workflow-publish"
        >
          {isPublishing ? '发布中...' : '发布版本'}
        </button>
        <div className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-xs text-slate-500">
          <div>最新发布：{workflowMeta.publishedVersion || '尚未发布'}</div>
          <div>保存状态：{saveStatus || '尚未保存'}</div>
          {validationIssues.length > 0 && (
            <div className="mt-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-amber-700">
              <div>当前还有 {validationIssues.length} 项待处理问题</div>
              <div className="mt-1 space-y-1" data-testid="workflow-validation-issues">
                {validationIssues.slice(0, 3).map((issue, index) => (
                  <div key={`${issue.field}_${index}`}>{issue.message || issue.field}</div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )

  const renderVariableManagementPanel = () => (
    <aside
      className="flex h-full min-h-0 min-w-0 flex-col rounded-3xl border border-slate-200 bg-slate-50/90 p-4"
      data-testid="workflow-variable-panel"
    >
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
          data-testid="workflow-variable-name-input"
        />
        <div className="grid gap-2 sm:grid-cols-[1fr_120px] xl:grid-cols-1 2xl:grid-cols-[1fr_120px]">
          <select
            value={variableForm.type}
            onChange={(event) => setVariableForm((prev) => ({ ...prev, type: event.target.value as VariableType }))}
            className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
            data-testid="workflow-variable-type-select"
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
            disabled={Boolean(editingVariable)}
            data-testid="workflow-variable-scope-select"
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
          data-testid="workflow-variable-description-input"
        />
        <div className="flex gap-2">
          <button
            className="prompt-primary flex-1"
            type="button"
            onClick={submitVariable}
            data-testid={editingVariable ? 'workflow-variable-save' : 'workflow-variable-add'}
          >
            {editingVariable ? '保存变量' : '添加变量'}
          </button>
          {editingVariable && (
            <button className="prompt-secondary" type="button" onClick={resetVariableForm} data-testid="workflow-variable-cancel">
              取消
            </button>
          )}
        </div>
      </div>

      <div className="grid flex-1 min-h-0 gap-4 overflow-auto pr-1">
        <div className="space-y-2">
          <div className="text-xs font-semibold text-slate-400">全局变量</div>
          {renderVariableManager('global', globalVariables)}
        </div>
        <div className="space-y-2">
          <div className="text-xs font-semibold text-slate-400">临时变量</div>
          {renderVariableManager('temp', tempVariables)}
        </div>
      </div>
      {variableContextMenu && (
        <div
          className="fixed z-50 w-28 overflow-hidden rounded-xl border border-slate-200 bg-white py-1 text-sm shadow-xl"
          style={{ left: variableContextMenu.x, top: variableContextMenu.y }}
          data-testid="workflow-variable-context-menu"
          onClick={(event) => event.stopPropagation()}
        >
          <button
            className="block w-full px-3 py-2 text-left text-slate-700 hover:bg-slate-50"
            type="button"
            data-testid="workflow-variable-context-edit"
            onClick={() => beginEditVariable(variableContextMenu.scope, variableContextMenu.variableId)}
          >
            编辑
          </button>
          <button
            className="block w-full px-3 py-2 text-left text-red-600 hover:bg-red-50"
            type="button"
            data-testid="workflow-variable-context-delete"
            onClick={() => removeVariable(variableContextMenu.scope, variableContextMenu.variableId)}
          >
            删除
          </button>
        </div>
      )}
    </aside>
  )

  const renderFunctionTestDialog = () => {
    if (!functionTestDialogOpen || !selectedFunctionConfig) return null
    const variableSummary = formatFunctionTestVariableReferences(functionTestVariableReferences)

    return (
      <div className="form-overlay" data-testid="workflow-function-test-dialog" role="dialog" aria-modal="true">
        <div className="api-center-modal">
          <div className="mb-5 flex items-start justify-between gap-4">
            <div>
              <div className="panel-title">函数测试</div>
              <div className="mt-2 text-sm text-slate-500">
                {selectedFunctionName.trim() || '未命名函数'}
              </div>
            </div>
            <button
              type="button"
              className="text-sm text-slate-500 hover:text-slate-700"
              onClick={() => setFunctionTestDialogOpen(false)}
            >
              关闭
            </button>
          </div>
          <div className="api-center-modal-body space-y-4">
            <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
              {functionTestRequiresVariables
                ? `检测到变量：${variableSummary}`
                : '当前函数未检测到需要输入的测试变量，可直接运行测试。'}
            </div>
            {functionTestRequiresVariables && (
              <label className="block space-y-2">
                <span className="text-xs font-medium text-slate-500">测试变量</span>
                <textarea
                  value={functionTestVariablesText}
                  onChange={(event) => setFunctionTestVariablesText(event.target.value)}
                  className="min-h-[160px] w-full rounded-xl border border-slate-200 px-3 py-2 font-mono text-xs leading-5"
                  data-testid="workflow-function-test-variables-input"
                  spellCheck={false}
                />
              </label>
            )}
            <div
              className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600"
              data-testid="workflow-function-test-result"
            >
              {functionTestError && (
                <div className="mb-2 rounded-lg border border-rose-200 bg-rose-50 px-2 py-1 text-rose-700">
                  {functionTestError}
                </div>
              )}
              {functionTestResult ? (
                <div className="space-y-2">
                  <div className={functionTestResult.success ? 'font-semibold text-emerald-700' : 'font-semibold text-rose-700'}>
                    {functionTestResult.success ? '成功' : '失败'} · {functionTestResult.duration_ms} ms
                  </div>
                  <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-lg bg-white p-2 font-mono text-[11px] text-slate-700">
                    {JSON.stringify(functionTestResult.variables, null, 2)}
                  </pre>
                  {functionTestResult.stdout && (
                    <pre className="max-h-28 overflow-auto whitespace-pre-wrap rounded-lg bg-white p-2 font-mono text-[11px] text-slate-700">
                      {functionTestResult.stdout}
                    </pre>
                  )}
                </div>
              ) : (
                <div>尚未运行测试。</div>
              )}
            </div>
          </div>
          <div className="mt-5 flex flex-wrap justify-end gap-2">
            <button
              className="prompt-secondary"
              type="button"
              onClick={() => setFunctionTestDialogOpen(false)}
              disabled={isFunctionTestRunning}
            >
              取消
            </button>
            <button
              className="prompt-primary"
              type="button"
              onClick={() => void handleFunctionTestRun()}
              disabled={isFunctionTestRunning}
              data-testid="workflow-function-test-submit"
            >
              {isFunctionTestRunning ? '测试中...' : '运行测试'}
            </button>
          </div>
        </div>
      </div>
    )
  }

  useImperativeHandle(
    ref,
    () => ({
      setWorkflowName: updateWorkflowName,
      validateDraft: handleValidateDraft,
      saveDraft: handleSaveDraft,
      publish: handlePublish,
    }),
    [handlePublish, handleSaveDraft, handleValidateDraft]
  )

  return (
    <>
      <div className="panel-card flex h-[calc(100vh-168px)] min-h-[720px] flex-col overflow-hidden">
        <div className="flex min-h-0 flex-1 flex-col gap-4">
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

        <div
          className="min-h-0 flex-1 overflow-auto overscroll-contain pr-1"
          data-testid="workflow-editor-workspace"
        >
          <div className="grid min-h-[900px] w-full max-w-full items-stretch" style={workflowEditorGridStyle}>
          <aside
            className="flex min-h-0 min-w-0 flex-col rounded-3xl border border-slate-200 bg-white/95 p-4"
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
                    data-testid={`workflow-graph-nav-${graphId}`}
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
          </aside>
          {renderWorkflowResizeHandle('graph', 'canvas', 'workflow-resize-handle-graph-canvas', '调整流程导航区和画布编辑区宽度')}

          <section
            className="flex min-h-0 min-w-0 flex-col rounded-3xl border border-slate-200 bg-white/95 p-4"
            data-testid="workflow-canvas-panel"
          >
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
                defaultViewport={workflowCanvasDefaultViewport}
                data-testid="workflow-reactflow"
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onNodeClick={handleNodeClick}
                onPaneClick={() => setSelectedNodeId(null)}
                panOnDrag
                panOnScroll
                panOnScrollMode={PanOnScrollMode.Free}
                zoomOnScroll={false}
              >
                <Background gap={12} size={1} />
                <MiniMap pannable zoomable />
                <Controls />
              </ReactFlow>
            </div>
          </section>
          {renderWorkflowResizeHandle('canvas', 'properties', 'workflow-resize-handle-canvas-properties', '调整画布编辑区和右侧属性列宽度')}

          <aside
            className="grid min-h-0 min-w-0 gap-4 xl:grid-rows-[minmax(0,3fr)_minmax(0,7fr)]"
            data-testid="workflow-properties-panel"
          >
            {renderWorkflowInfoPanel()}
            <div
              className="flex min-h-0 flex-col rounded-3xl border border-slate-200 bg-white/95 p-4"
              data-testid="workflow-process-properties-card"
            >
              <div className="mb-3">
                <div className="panel-title">流程属性</div>
                <div className="mt-2 text-sm text-slate-500">
                  {selectedNodeData?.nodeType === 'sub_agent'
                    ? '正在编辑该子流程的名称和描述。'
                    : selectedNodeData
                    ? '正在编辑节点属性。'
                    : '当前显示流程级属性，可直接调整当前画布名称。'}
                </div>
              </div>
              <div className="min-h-0 flex-1 overflow-auto pr-1">{renderNodeEditor()}</div>
            </div>
          </aside>
          {renderWorkflowResizeHandle('properties', 'variables', 'workflow-resize-handle-properties-variables', '调整流程属性和变量管理区宽度')}
          {renderVariableManagementPanel()}
          </div>
        </div>
        </div>
      </div>
      {renderFunctionTestDialog()}
    </>
  )
})

function toFlowType(nodeType: DesignerNodeType): 'input' | 'default' | 'output' {
  if (nodeType === 'start') return 'input'
  if (nodeType === 'end') return 'output'
  return 'default'
}

function createInitialGraph(graphId: string, name?: string, description?: string): WorkflowGraphState {
  const isMainGraph = graphId === MAIN_GRAPH_ID
  return {
    id: graphId,
    name: name || '',
    description: description || '',
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
          description: ['start', 'end'].includes(data.nodeType) ? '' : String(data.config.description || data.config.prompt || ''),
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
    graph_name: graph.name.trim(),
    graph_description: graph.description.trim(),
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
        initial_variables: mapVariableIdsToObject(config.input_variable_ids, variableNameMap, '', true),
        input_variables: mapVariableIdsToDefinitions(config.input_variable_ids, variableNameMap),
      }
    case 'coordinator':
    case 'sub_agent': {
      const description = String(config.description || config.prompt || '')
      const base: Record<string, unknown> = {
        prompt: description,
        user_prompt: description,
        description,
      }
      if (nodeType === 'sub_agent') {
        const subgraphId = String(config.subgraph_id || '').trim()
        if (subgraphId) {
          base.subgraph_id = subgraphId
        }
      }
      return {
        ...base,
      }
    }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'function':
      return {
        language: 'python',
        function_name: String(config.function_name || ''),
        code: String(config.code || ''),
        timeout_ms: normalizeFunctionTimeoutMs(config.timeout_ms),
      }
    case 'api':
      return {
        invoke_type: 'api',
        group_id: Number(config.group_id || 0) || '',
        api_id: Number(config.api_id || 0) || '',
        tool_code: String(config.api_id || ''),
        request_url: String(config.request_url || ''),
        input_schema: String(config.input_schema || ''),
        output_schema: String(config.output_schema || ''),
        payload_mapping: ensureObject(config.payload_mapping),
        output_mapping: ensureObject(config.output_mapping),
      }
    case 'end':
      return {
        output_format: mapVariableIdsToReferences(config.output_variable_ids, variableNameMap),
      }
    default:
      return config
  }
}

function mapVariableIdsToReferences(source: unknown, variableNameMap: Map<string, VariableDefinition>) {
  const ids = Array.isArray(source) ? (source as string[]) : []
  const entries = ids
    .map((id) => variableNameMap.get(id))
    .filter((item): item is VariableDefinition => Boolean(item))
    .map((item) => [item.name, formatVariableReference(item)])
  return Object.fromEntries(entries)
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

function mapVariableIdsToDefinitions(source: unknown, variableNameMap: Map<string, VariableDefinition>) {
  const ids = Array.isArray(source) ? (source as string[]) : []
  return ids
    .map((id) => variableNameMap.get(id))
    .filter((item): item is VariableDefinition => Boolean(item))
    .map((item) => ({
      name: item.name,
      type: item.type,
      scope: item.scope,
      description: item.description,
      default: '',
    }))
}

function ensureObject(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function normalizeFunctionTimeoutMs(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(parsed)) {
    return 3000
  }
  return Math.max(1, Math.min(30000, Math.trunc(parsed)))
}

function normalizeFunctionTestVariables(value: unknown) {
  const source = asRecord(value)
  return {
    global: ensureObject(source.global),
    local: ensureObject(source.local),
  }
}

function extractFunctionTestVariableReferences(code: string): FunctionTestVariableReference[] {
  const references = new Map<string, FunctionTestVariableReference>()
  const addReference = (scope: FunctionTestVariableScope, rawName: string) => {
    const name = rawName.trim()
    if (!name) return
    references.set(`${scope}.${name}`, { scope, name })
  }

  const bracketPattern = /ctx\s*\[\s*(['"])(global|local)\1\s*]\s*\[\s*(['"])([^'"\]]+)\3\s*]/g
  let bracketMatch: RegExpExecArray | null
  while ((bracketMatch = bracketPattern.exec(code)) !== null) {
    const scope = bracketMatch[2] as FunctionTestVariableScope
    const name = bracketMatch[4]
    if (!isFunctionVariableAssignmentTarget(code, bracketPattern.lastIndex)) {
      addReference(scope, name)
    }
  }

  const getPattern = /ctx\s*\[\s*(['"])(global|local)\1\s*]\s*\.get\s*\(\s*(['"])([^'"]+)\3/g
  let getMatch: RegExpExecArray | null
  while ((getMatch = getPattern.exec(code)) !== null) {
    addReference(getMatch[2] as FunctionTestVariableScope, getMatch[4])
  }

  return Array.from(references.values())
}

function isFunctionVariableAssignmentTarget(code: string, referenceEndIndex: number) {
  const following = code.slice(referenceEndIndex).trimStart()
  return (
    following.startsWith('=') && !following.startsWith('==')
  ) || ['+=', '-=', '*=', '/=', '//=', '%=', '**='].some((operator) => following.startsWith(operator))
}

function buildFunctionTestVariablesText(references: FunctionTestVariableReference[], currentText: string) {
  let current = normalizeFunctionTestVariables(null)
  try {
    current = normalizeFunctionTestVariables(JSON.parse(currentText))
  } catch {
    current = normalizeFunctionTestVariables(null)
  }

  const next: Record<FunctionTestVariableScope, Record<string, unknown>> = {
    global: { ...current.global },
    local: { ...current.local },
  }
  references.forEach((reference) => {
    if (!(reference.name in next[reference.scope])) {
      next[reference.scope][reference.name] = ''
    }
  })

  return JSON.stringify({ global: next.global, local: next.local }, null, 2)
}

function formatFunctionTestVariableReferences(references: FunctionTestVariableReference[]) {
  return references.map((reference) => `${reference.scope}.${reference.name}`).join('、')
}

function formatVariableReference(variable: VariableDefinition) {
  return `$${variable.scope === 'global' ? 'session' : 'execution'}.${variable.name}`
}

function resolveMappedVariable(value: unknown, variables: VariableDefinition[]) {
  const mappedValue = String(value || '').trim()
  if (!mappedValue) return null

  const scope = mappedValue.startsWith('$global.') || mappedValue.startsWith('$session.')
    ? 'global'
    : mappedValue.startsWith('$temp.') || mappedValue.startsWith('$execution.')
      ? 'temp'
      : null
  const referencedName = mappedValue.startsWith('$') ? mappedValue.slice(1).split('.').pop() : mappedValue
  return (
    variables.find((variable) =>
      (scope ? variable.scope === scope : true) && (variable.name === referencedName || variable.id === mappedValue)
    ) ?? variables.find((variable) => variable.id === mappedValue) ?? null
  )
}

function extractSchemaParameters(rawSchema: string) {
  const schema = parseJsonObject(rawSchema)
  const properties = asRecord(schema.properties)
  const requiredNames = new Set(
    (Array.isArray(schema.required) ? schema.required : [])
      .map((item) => String(item || '').trim())
      .filter(Boolean)
  )
  return Object.entries(properties).map(([name, value]) => {
    const field = asRecord(value)
    return {
      name,
      type: String(field.type || 'object'),
      description: String(field.description || ''),
      required: requiredNames.has(name),
    }
  })
}

function extractApiInputParameters(rawSchema: string, requestUrl: string) {
  const schemaParameters = extractSchemaParameters(rawSchema)
  const parametersByName = new Map(schemaParameters.map((parameter) => [parameter.name, parameter]))
  extractUrlTemplateParameters(requestUrl).forEach((name) => {
    const current = parametersByName.get(name)
    parametersByName.set(name, current ? { ...current, required: true } : {
      name,
      type: 'string',
      description: '',
      required: true,
    })
  })
  return Array.from(parametersByName.values())
}

function extractUrlTemplateParameters(rawUrl: string) {
  const url = rawUrl || ''
  const names = new Set<string>()
  for (const match of url.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) {
    names.add(match[1])
  }
  const pathPart = url.split(/[?#]/)[0] || ''
  for (const match of pathPart.matchAll(/(?:^|\/):([A-Za-z_][A-Za-z0-9_]*)\b/g)) {
    names.add(match[1])
  }
  return Array.from(names)
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
    case 'api':
      return 'API节点'
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

function collectLocalWorkflowValidationIssues(graphs: Record<string, WorkflowGraphState>): WorkflowValidationIssue[] {
  return [
    ...collectGraphMetadataValidationIssues(graphs),
    ...collectRequiredApiSchemaMappingValidationIssues(graphs),
  ]
}

function collectRequiredApiSchemaMappingValidationIssues(graphs: Record<string, WorkflowGraphState>): WorkflowValidationIssue[] {
  return Object.values(graphs).flatMap((graph) =>
    graph.nodes.flatMap((node) => {
      const data = node.data as CanvasNodeData
      if (data.nodeType !== 'api') return []

      const config = data.config || {}
      const inputIssues = collectRequiredSchemaMappingIssues(
        String(config.input_schema || ''),
        String(config.request_url || ''),
        ensureObject(config.payload_mapping),
        node.id,
        'config.payload_mapping',
        '输入参数'
      )
      const outputIssues = collectRequiredSchemaMappingIssues(
        String(config.output_schema || ''),
        '',
        ensureObject(config.output_mapping),
        node.id,
        'config.output_mapping',
        '输出参数'
      )
      return [...inputIssues, ...outputIssues]
    })
  )
}

function collectRequiredSchemaMappingIssues(
  rawSchema: string,
  requestUrl: string,
  mapping: Record<string, unknown>,
  nodeId: string,
  fieldPrefix: string,
  label: string
): WorkflowValidationIssue[] {
  return (requestUrl ? extractApiInputParameters(rawSchema, requestUrl) : extractSchemaParameters(rawSchema))
    .filter((parameter) => parameter.required && !String(mapping[parameter.name] || '').trim())
    .map((parameter) => ({
      node_id: nodeId,
      field: `${fieldPrefix}.${parameter.name}`,
      message: `API节点${label} ${parameter.name} 为必填，请选择变量`,
    }))
}

function collectGraphMetadataValidationIssues(graphs: Record<string, WorkflowGraphState>): WorkflowValidationIssue[] {
  return Object.values(graphs).flatMap((graph) => {
    const issues: WorkflowValidationIssue[] = []
    const graphLabel = graph.id === MAIN_GRAPH_ID ? '主流程' : `子流程 ${graph.id}`
    if (!graph.name.trim()) {
      issues.push({
        node_id: null,
        field: `graphs.${graph.id}.graph_name`,
        message: `${graphLabel}缺少名称`,
      })
    }
    if (!graph.description.trim()) {
      issues.push({
        node_id: null,
        field: `graphs.${graph.id}.graph_description`,
        message: `${graphLabel}缺少描述`,
      })
    }
    return issues
  })
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

function buildWorkflowSnapshot(payload: WorkflowDraftPayload): WorkflowSnapshotV1 {
  const workflowName = (payload.workflowName || '').trim()
  return {
    schema_version: 'workflow-snapshot/v1',
    workflow: {
      workflow_code: payload.workflowCode,
      workflow_name: workflowName,
      workflow_description: payload.workflowDescription || '',
      workflow_version: payload.workflowVersion,
    },
    designer: {
      definition: payload.definition,
      entry_rule: {},
      workflow_config: payload.workflowConfig,
      editor_meta: asRecord(payload.definition.editor_meta),
    },
  }
}

function hydrateWorkflowSelection(selection: WorkflowEditorSelection): HydratedWorkflowState {
  const snapshot = parseWorkflowSnapshot(selection.version.workflowSnapshot)
  const snapshotDesigner = snapshot ? asRecord(snapshot.designer) : {}
  const snapshotDefinition = asRecord(snapshotDesigner.definition)
  const hasSnapshotDefinition = Object.keys(snapshotDefinition).length > 0

  const legacyDefinition = parseJsonObject(selection.version.definition)
  const definition = hasSnapshotDefinition ? snapshotDefinition : legacyDefinition
  const versionConfig = parseJsonObject(selection.version.config)
  const snapshotWorkflowConfig = asRecord(snapshotDesigner.workflow_config)
  const effectiveConfig = Object.keys(snapshotWorkflowConfig).length > 0 ? snapshotWorkflowConfig : versionConfig
  const versionEditorMeta = parseJsonObject(selection.version.editorMeta)
  const snapshotEditorMeta = asRecord(snapshotDesigner.editor_meta)
  const definitionEditorMeta = asRecord(definition.editor_meta)
  const editorMeta =
    Object.keys(snapshotEditorMeta).length > 0
      ? snapshotEditorMeta
      : Object.keys(definitionEditorMeta).length > 0
      ? definitionEditorMeta
      : versionEditorMeta

  const variablesSource = resolveVariableSource(definition, effectiveConfig)
  const globalVariables = toVariableDefinitions(variablesSource.global, 'global')
  const tempVariables = toVariableDefinitions(variablesSource.temporary, 'temp')
  const variableNameToId = new Map(
    [...globalVariables, ...tempVariables].map((variable) => [variable.name, variable.id])
  )
  const hydratedGraphs = hydrateGraphs(definition, editorMeta, variableNameToId)
  const synchronizedGraphs = syncAllLinkedSubAgentMetadata(hydratedGraphs.graphs)

  return {
    workflowId: selection.version.workflowId ?? null,
    workflowCode: selection.workflowCode,
    workflowName:
      stringValue(definition.workflow_name) ||
      selection.workflowName ||
      selection.version.workflowName ||
      selection.workflowCode,
    workflowDescription:
      stringValue(definition.workflow_description) ||
      selection.version.workflowDescription ||
      '',
    draftVersion:
      String(selection.version.status || '').toLowerCase() === 'draft' ? selection.version.version : DRAFT_VERSION,
    publishedVersion:
      selection.publishedVersion ||
      (String(selection.version.status || '').toLowerCase() === 'published' ? selection.version.version : null),
    globalVariables,
    tempVariables,
    graphs: synchronizedGraphs,
    graphOrder: hydratedGraphs.graphOrder,
    currentGraphId: hydratedGraphs.currentGraphId,
  }
}

function parseWorkflowSnapshot(source?: string | null): WorkflowSnapshotV1 | null {
  const snapshot = parseJsonObject(source)
  if (stringValue(snapshot.schema_version) !== 'workflow-snapshot/v1') {
    return null
  }
  const designer = asRecord(snapshot.designer)
  const definition = asRecord(designer.definition)
  if (Object.keys(definition).length === 0) {
    return null
  }
  return snapshot as unknown as WorkflowSnapshotV1
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
        name: stringValue(item.graph_name) || stringValue(item.name) || '',
        description: stringValue(item.graph_description) || stringValue(item.description) || '',
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
    name: '',
    description: '',
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
        config: {
          ...config,
          description: stringValue(node.description) || stringValue(asRecord(node.config).description) || String(config.description || config.prompt || ''),
        },
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
        input_variable_ids: mapObjectKeysToVariableIds(config.initial_variables, variableNameToId),
      }
    case 'coordinator':
      return {
        prompt: String(config.prompt || config.user_prompt || ''),
        description: String(config.description || config.prompt || config.user_prompt || ''),
      }
    case 'sub_agent':
      return {
        prompt: String(config.prompt || config.user_prompt || ''),
        description: String(config.description || config.prompt || config.user_prompt || ''),
        subgraph_id: String(config.subgraph_id || ''),
      }
    case 'message':
      return {
        message_text: String(config.message_text || ''),
      }
    case 'function':
      return {
        language: 'python',
        function_name: String(config.function_name || ''),
        code: String(config.code || ''),
        timeout_ms: normalizeFunctionTimeoutMs(config.timeout_ms),
      }
    case 'api':
      return {
        invoke_type: 'api',
        group_id: String(config.group_id || ''),
        api_id: String(config.api_id || ''),
        request_url: String(config.request_url || ''),
        input_schema: String(config.input_schema || ''),
        output_schema: String(config.output_schema || ''),
        payload_mapping: ensureObject(config.payload_mapping),
        output_mapping: ensureObject(config.output_mapping),
      }
    case 'end':
      return {
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
  const rawValue = (value || '').trim()
  if (!rawValue) return 'String'
  const matchedType = variableTypeOptions.find((option) => option.value === rawValue)
  if (matchedType) return matchedType.value

  const normalizedTypeMap: Record<string, VariableType> = {
    string: 'String',
    number: 'Number',
    boolean: 'Boolean',
    object: 'Object',
    array: 'Array',
  }
  return normalizedTypeMap[rawValue.toLowerCase()] || 'String'
}

function normalizeDesignerNodeType(value: string | null): DesignerNodeType {
  switch (value) {
    case 'start':
    case 'coordinator':
    case 'sub_agent':
    case 'api':
    case 'message':
    case 'function':
    case 'end':
      return value
    case 'tool':
      return 'api'
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

function syncLinkedSubAgentMetadata(
  graphs: Record<string, WorkflowGraphState>,
  graphId: string,
  metadata: { name?: string; description?: string }
) {
  if (graphId === MAIN_GRAPH_ID) return graphs
  for (const [parentGraphId, graph] of Object.entries(graphs)) {
    const nextNodes = graph.nodes.map((node) => {
      const data = node.data as CanvasNodeData
      if (data.nodeType !== 'sub_agent') return node
      const linkedSubgraphId = String(data.config.subgraph_id || '').trim()
      if (linkedSubgraphId !== graphId) return node
      const nextConfig =
        metadata.description === undefined
          ? data.config
          : {
              ...data.config,
              description: metadata.description,
            }
      return {
        ...node,
        data: {
          ...data,
          label: metadata.name === undefined ? data.label : metadata.name,
          config: nextConfig,
        },
      }
    })
    if (nextNodes.some((node, index) => node !== graph.nodes[index])) {
      return {
        ...graphs,
        [parentGraphId]: {
          ...graph,
          nodes: nextNodes,
        },
      }
    }
  }
  return graphs
}

function syncAllLinkedSubAgentMetadata(graphs: Record<string, WorkflowGraphState>) {
  return Object.entries(graphs).reduce((nextGraphs, [graphId, graph]) => {
    if (graphId === MAIN_GRAPH_ID) return nextGraphs
    return syncLinkedSubAgentMetadata(nextGraphs, graphId, {
      name: graph.name,
      description: graph.description,
    })
  }, graphs)
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
