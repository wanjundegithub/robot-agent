import React from 'react'
import ReactFlow, { Background, Controls, MiniMap, type Edge, type Node } from 'reactflow'
import 'reactflow/dist/style.css'

const nodes: Node[] = [
  { id: 'start', type: 'input', position: { x: 40, y: 40 }, data: { label: 'start' } },
  { id: 'llm', position: { x: 40, y: 140 }, data: { label: 'llm' } },
  { id: 'condition', position: { x: 40, y: 240 }, data: { label: 'condition' } },
  { id: 'form', position: { x: 220, y: 240 }, data: { label: 'form' } },
  { id: 'tool', position: { x: 40, y: 340 }, data: { label: 'tool' } },
  { id: 'subflow', position: { x: 220, y: 340 }, data: { label: 'subflow' } },
  { id: 'knowledge', position: { x: 40, y: 440 }, data: { label: 'knowledge' } },
  { id: 'end', type: 'output', position: { x: 220, y: 440 }, data: { label: 'end' } }
]

const edges: Edge[] = [
  { id: 'e1', source: 'start', target: 'llm' },
  { id: 'e2', source: 'llm', target: 'condition' },
  { id: 'e3', source: 'condition', target: 'form' },
  { id: 'e4', source: 'form', target: 'tool' },
  { id: 'e5', source: 'tool', target: 'subflow' },
  { id: 'e6', source: 'knowledge', target: 'end' },
  { id: 'e7', source: 'subflow', target: 'end' }
]

const Orchestrator: React.FC = () => {
  const validationRules = [
    { label: '包含 start / end 节点', valid: nodes.some((node) => node.id === 'start') && nodes.some((node) => node.id === 'end') },
    { label: 'tool 节点可接入重试策略', valid: nodes.some((node) => node.id === 'tool') },
    { label: 'knowledge 节点可绑定知识库', valid: nodes.some((node) => node.id === 'knowledge') },
    { label: 'subflow 节点已声明', valid: nodes.some((node) => node.id === 'subflow') },
    { label: '支持多流程切换与恢复提示', valid: true }
  ]

  return (
    <div className="panel-card h-full">
      <div className="panel-header">
        <div>
          <div className="panel-title">Orchestrator</div>
          <div className="text-xs text-slate-500">Phase 3 routing / safety readiness</div>
        </div>
      </div>
      <div className="panel-body h-[420px]">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          fitView
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          zoomOnScroll={false}
          panOnScroll
        >
          <Background gap={12} size={1} />
          <MiniMap pannable zoomable />
          <Controls />
        </ReactFlow>
      </div>
      <div className="border-t border-slate-200 px-4 py-3">
        <div className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-400">Validation</div>
        <ul className="space-y-1">
          {validationRules.map((rule) => (
            <li key={rule.label} className={`text-sm ${rule.valid ? 'text-emerald-600' : 'text-amber-600'}`}>
              {rule.valid ? '通过' : '待补充'} · {rule.label}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

export default Orchestrator
