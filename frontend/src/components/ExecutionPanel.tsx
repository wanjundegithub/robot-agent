import React from 'react'
import type { ExecutionDetail, ExecutionEventView, SocketState } from '../types'

interface ExecutionPanelProps {
  events: ExecutionEventView[]
  executions: ExecutionDetail[]
  status: string
  executionId?: string | null
  socketState: SocketState
}

const ExecutionPanel: React.FC<ExecutionPanelProps> = ({
  events,
  executions,
  status,
  executionId,
  socketState,
}) => {
  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">Execution</div>
          <div className="text-xs text-slate-500">Status: {status || 'IDLE'} · Socket: {socketState}</div>
        </div>
        <div className="text-xs text-slate-400 truncate max-w-[180px]">
          {executionId || 'session scope'}
        </div>
      </div>
      <div className="panel-body space-y-4 overflow-y-auto">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">Active Stack</div>
          {executions.length === 0 && (
            <div className="text-sm text-slate-500">当前 session 还没有 execution。</div>
          )}
          <ul className="space-y-2">
            {executions.map((execution) => (
              <li key={execution.execution_id} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium text-slate-800">{execution.workflow_code}</div>
                    <div className="text-xs text-slate-500">
                      {execution.workflow_version} · {execution.current_node_id || 'pending'}
                    </div>
                  </div>
                  <div className="text-xs font-medium text-slate-600">{execution.status}</div>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">Recent Events</div>
          {events.length === 0 && (
            <div className="text-sm text-slate-500">等待执行事件...</div>
          )}
          <ul className="space-y-2">
            {events.map((event) => (
              <li key={event.id} className="text-sm text-slate-700">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{event.event_type}</span>
                  <span className="text-xs text-slate-400">{event.timestamp}</span>
                </div>
                {(event.node_id || event.node_type || event.status) && (
                  <div className="text-xs text-slate-500">
                    {event.execution_id ? `exec: ${event.execution_id}` : ''}
                    {event.node_id ? ` · node: ${event.node_id}` : ''}
                    {event.node_type ? ` · type: ${event.node_type}` : ''}
                    {event.tool_code ? ` · tool: ${event.tool_code}` : ''}
                    {event.status ? ` · status: ${event.status}` : ''}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  )
}

export default ExecutionPanel
