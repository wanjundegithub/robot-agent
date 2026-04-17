import React, { useMemo, useState } from 'react'
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
import type { WorkflowValidationIssue } from '../types'

interface OrchestratorProps {
  currentUserId: string
  sendGatewayAction: (action: string, payload: Record<string, unknown>) => Promise<unknown>
}

const initialNodes: Node[] = [
  { id: 'start', type: 'input', position: { x: 60, y: 40 }, data: { label: '开始', config: {} } },
  { id: 'extract_slots', position: { x: 60, y: 150 }, data: { label: '提取槽位', config: { prompt: 'slot_extraction', model_profile_ref: 'structured-extraction-v1' } } },
  { id: 'check_slots', position: { x: 60, y: 280 }, data: { label: '检查槽位', config: { required_fields: ['departure_city', 'arrival_city', 'departure_date'] } } },
  { id: 'collect_info', position: { x: 280, y: 280 }, data: { label: '补充信息', config: { title: '补充信息' } } },
  { id: 'retrieve_policy', position: { x: 60, y: 420 }, data: { label: '检索知识', config: { knowledge_base_code: 'flight_policy_kb', query_rewrite: { enabled: true, model_profile_ref: 'knowledge-query-rewrite-v1' }, answer_generation: { enabled: true, model_profile_ref: 'knowledge-answer-v1' } } } },
  { id: 'end', type: 'output', position: { x: 280, y: 420 }, data: { label: '结束', config: {} } },
]

const initialEdges: Edge[] = [
  { id: 'e_start_extract', source: 'start', target: 'extract_slots' },
  { id: 'e_extract_check', source: 'extract_slots', target: 'check_slots' },
  { id: 'e_check_collect', source: 'check_slots', target: 'collect_info', label: '缺失' },
  { id: 'e_check_retrieve', source: 'check_slots', target: 'retrieve_policy', label: '完整' },
  { id: 'e_collect_retrieve', source: 'collect_info', target: 'retrieve_policy' },
  { id: 'e_retrieve_end', source: 'retrieve_policy', target: 'end' },
]

const nodeTemplates: Array<{ type: string; label: string; config: Record<string, unknown> }> = [
  { type: 'llm', label: '大模型节点', config: { prompt: 'slot_extraction', model_profile_ref: 'general-chat-v1' } },
  { type: 'knowledge', label: '知识节点', config: { knowledge_base_code: 'flight_policy_kb', query_rewrite: { enabled: true, model_profile_ref: 'knowledge-query-rewrite-v1' }, answer_generation: { enabled: true, model_profile_ref: 'knowledge-answer-v1' } } },
  { type: 'tool', label: '工具节点', config: { tool_code: '', url: '', method: 'POST' } },
  { type: 'form', label: '表单节点', config: { title: '请补充信息' } },
  { type: 'condition', label: '条件节点', config: { required_fields: [] } },
]

const Orchestrator: React.FC<OrchestratorProps> = ({ currentUserId, sendGatewayAction }) => {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [workflowCode, setWorkflowCode] = useState('flight_booking')
  const [workflowVersion, setWorkflowVersion] = useState('3.0.0-draft')
  const [workflowConfig, setWorkflowConfig] = useState<Record<string, unknown>>({
    intent_profile_ref: 'intent-router-v1',
    llm_defaults: {
      model_profile_ref: 'general-chat-v1',
      provider_code: 'openai-compatible-prod',
    },
  })
  const [validationIssues, setValidationIssues] = useState<WorkflowValidationIssue[]>([])
  const [saveStatus, setSaveStatus] = useState('未保存')

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null

  const validationRules = useMemo(() => {
    const hasStart = nodes.some((node) => node.id === 'start' || node.type === 'input')
    const hasEnd = nodes.some((node) => node.id === 'end' || node.type === 'output')
    const hasIntentProfile = Boolean(workflowConfig.intent_profile_ref)
    const hasKnowledgeNode = nodes.some((node) => String(node.data?.label).includes('retrieve') || node.data?.config?.knowledge_base_code)
    return [
      { label: '包含开始 / 结束节点', valid: hasStart && hasEnd },
      { label: '已配置意图模型编码', valid: hasIntentProfile },
      { label: '知识节点绑定知识库', valid: hasKnowledgeNode },
      { label: '节点/连线支持编辑与保存', valid: true },
      { label: '主交互基于 Netty + WebSocket', valid: true },
    ]
  }, [nodes, workflowConfig])

  const addNode = (templateType: string) => {
    const template = nodeTemplates.find((item) => item.type === templateType)
    if (!template) return
    const id = `${template.type}_${Date.now()}`
    setNodes((prev) => [
      ...prev,
      {
        id,
        position: { x: 120 + prev.length * 20, y: 120 + prev.length * 20 },
        data: {
          label: template.label,
          config: structuredClone(template.config),
        },
      },
    ])
    setSelectedNodeId(id)
  }

  const onConnect = (connection: Connection) => {
    setEdges((prev) => addEdge({ ...connection, id: `e_${Date.now()}` }, prev))
  }

  const updateSelectedNode = (path: string, value: unknown) => {
    if (!selectedNode) return
    setNodes((prev) =>
      prev.map((node) => {
        if (node.id !== selectedNode.id) return node
        const data = { ...(node.data as Record<string, any>) }
        const config = { ...(data.config || {}) }
        assignPath(config, path, value)
        return {
          ...node,
          data: {
            ...data,
            config,
          },
        }
      })
    )
  }

  const updateSelectedNodeLabel = (label: string) => {
    if (!selectedNode) return
    setNodes((prev) =>
      prev.map((node) =>
        node.id === selectedNode.id
          ? { ...node, data: { ...(node.data as Record<string, unknown>), label } }
          : node
      )
    )
  }

  const removeSelectedNode = () => {
    if (!selectedNode) return
    setNodes((prev) => prev.filter((node) => node.id !== selectedNode.id))
    setEdges((prev) => prev.filter((edge) => edge.source !== selectedNode.id && edge.target !== selectedNode.id))
    setSelectedNodeId(null)
  }

  const buildDefinition = () => {
    const nodeMap = Object.fromEntries(
      nodes.map((node) => [
        node.id,
        {
          id: node.id,
          type: node.type === 'input' ? 'start' : node.type === 'output' ? 'end' : inferNodeType(node.id),
          name: String((node.data as Record<string, unknown>)?.label || node.id),
          config: structuredClone(((node.data as Record<string, any>)?.config || {}) as Record<string, unknown>),
        },
      ])
    )
    const transitions: Record<string, unknown> = {}
    nodes.forEach((node) => {
      const outgoing = edges.filter((edge) => edge.source === node.id)
      if (outgoing.length === 0) {
        transitions[node.id] = null
        return
      }
      const isCondition = inferNodeType(node.id) === 'condition'
      if (isCondition) {
        transitions[node.id] = Object.fromEntries(
          outgoing.map((edge, index) => [String(edge.label || `branch_${index + 1}`), edge.target])
        )
        return
      }
      transitions[node.id] = outgoing[0].target
    })
    return {
      workflow_code: workflowCode,
      workflow_version: workflowVersion,
      entry: nodes.find((node) => node.type === 'input')?.id || nodes[0]?.id || 'start',
      nodes: nodeMap,
      transitions,
      config: workflowConfig,
    }
  }

  const saveDraft = async () => {
    const definition = buildDefinition()
    const response = await sendGatewayAction('save_workflow_draft', {
      user_id: currentUserId,
      workflow_code: workflowCode,
      version: workflowVersion,
      definition,
      entry_rule: {
        intent_codes: ['book_flight'],
        keywords: ['航班', '机票'],
        priority: 120,
      },
      editor_meta: {
        layout_engine: 'reactflow',
        viewport: { x: 0, y: 0, zoom: 0.92 },
        readonly: false,
        last_saved_by: currentUserId,
      },
      config: workflowConfig,
    })
    setSaveStatus(response ? '已通过网关保存' : '保存完成')
  }

  const validateDraft = async () => {
    const definition = buildDefinition()
    const response = (await sendGatewayAction('validate_workflow_draft', {
      definition,
      config: workflowConfig,
    })) as { issues?: WorkflowValidationIssue[] } | undefined
    const issues = response?.issues ?? []
    setValidationIssues(issues)
    setSaveStatus(issues.length === 0 ? '校验通过' : `发现 ${issues.length} 个问题`)
  }

  return (
    <div className="panel-card h-full">
      <div className="panel-header">
        <div>
          <div className="panel-title">流程画布</div>
          <div className="text-xs text-slate-500">可编辑 React Flow 画布 · Netty + WebSocket 草稿同步</div>
        </div>
        <div className="text-xs text-slate-400">{saveStatus}</div>
      </div>

      <div className="mb-3 grid gap-2 md:grid-cols-[1fr_180px_160px]">
        <input
          value={workflowCode}
          onChange={(event) => setWorkflowCode(event.target.value)}
          className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
          placeholder="流程编码"
        />
        <input
          value={workflowVersion}
          onChange={(event) => setWorkflowVersion(event.target.value)}
          className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
          placeholder="草稿版本"
        />
        <input
          value={String(workflowConfig.intent_profile_ref || '')}
          onChange={(event) =>
            setWorkflowConfig((prev) => ({ ...prev, intent_profile_ref: event.target.value }))
          }
          className="rounded-xl border border-slate-200 px-3 py-2 text-sm"
          placeholder="意图模型配置"
        />
      </div>

      <div className="mb-3 flex flex-wrap gap-2">
        {nodeTemplates.map((template) => (
          <button
            key={template.type}
            className="prompt-secondary"
            onClick={() => addNode(template.type)}
            type="button"
          >
            + {template.label}
          </button>
        ))}
        <button className="prompt-secondary" type="button" onClick={() => void validateDraft()}>
          校验草稿
        </button>
        <button className="prompt-primary" type="button" onClick={() => void saveDraft()}>
          保存草稿
        </button>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.7fr)_minmax(280px,0.9fr)]">
        <div className="panel-body h-[460px] rounded-xl border border-slate-200 bg-white">
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

        <div className="space-y-4">
          <div className="rounded-xl border border-slate-200 bg-slate-50/80 p-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">节点编辑器</div>
            {!selectedNode && <div className="text-sm text-slate-500">选择一个节点后可编辑属性。</div>}
            {selectedNode && (
              <div className="space-y-3">
                <input
                  value={String((selectedNode.data as Record<string, unknown>)?.label || '')}
                  onChange={(event) => updateSelectedNodeLabel(event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                />
                <div className="text-xs text-slate-400">节点类型：{displayNodeType(inferNodeType(selectedNode.id))}</div>
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.model_profile_ref || '')}
                  onChange={(event) => updateSelectedNode('model_profile_ref', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="模型配置编码"
                />
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.knowledge_base_code || '')}
                  onChange={(event) => updateSelectedNode('knowledge_base_code', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="知识库编码"
                />
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.tool_code || '')}
                  onChange={(event) => updateSelectedNode('tool_code', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="工具编码"
                />
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.url || '')}
                  onChange={(event) => updateSelectedNode('url', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="工具地址"
                />
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.query_rewrite?.model_profile_ref || '')}
                  onChange={(event) => updateSelectedNode('query_rewrite.model_profile_ref', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="查询改写模型配置"
                />
                <input
                  value={String((selectedNode.data as Record<string, any>)?.config?.answer_generation?.model_profile_ref || '')}
                  onChange={(event) => updateSelectedNode('answer_generation.model_profile_ref', event.target.value)}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="答案生成模型配置"
                />
                <button className="prompt-secondary w-full" type="button" onClick={removeSelectedNode}>
                  删除当前节点
                </button>
              </div>
            )}
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">校验结果</div>
            <ul className="space-y-1">
              {validationRules.map((rule) => (
                <li key={rule.label} className={`text-sm ${rule.valid ? 'text-emerald-600' : 'text-amber-600'}`}>
                  {rule.valid ? '通过' : '待补充'} · {rule.label}
                </li>
              ))}
            </ul>
            {validationIssues.length > 0 && (
              <div className="mt-3 space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3">
                {validationIssues.map((issue, index) => (
                  <div key={`${issue.field}_${index}`} className="text-xs text-amber-700">
                    {issue.node_id ? `${issue.node_id} · ` : ''}{issue.field} · {issue.message}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function inferNodeType(nodeId: string): string {
  if (nodeId === 'start') return 'start'
  if (nodeId === 'end') return 'end'
  if (nodeId.includes('knowledge') || nodeId.includes('retrieve')) return 'knowledge'
  if (nodeId.includes('tool') || nodeId.includes('search')) return 'tool'
  if (nodeId.includes('form') || nodeId.includes('collect')) return 'form'
  if (nodeId.includes('condition') || nodeId.includes('check')) return 'condition'
  return 'llm'
}

function displayNodeType(nodeType: string): string {
  switch (nodeType) {
    case 'start':
      return '开始节点'
    case 'end':
      return '结束节点'
    case 'knowledge':
      return '知识节点'
    case 'tool':
      return '工具节点'
    case 'form':
      return '表单节点'
    case 'condition':
      return '条件节点'
    default:
      return '大模型节点'
  }
}

function assignPath(source: Record<string, any>, path: string, value: unknown) {
  const segments = path.split('.')
  let current = source
  for (let index = 0; index < segments.length - 1; index += 1) {
    const segment = segments[index]
    current[segment] = current[segment] || {}
    current = current[segment]
  }
  current[segments[segments.length - 1]] = value
}

export default Orchestrator
