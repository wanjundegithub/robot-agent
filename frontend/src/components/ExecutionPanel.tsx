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
  const displaySocketState = (value: SocketState) => {
    switch (value) {
      case 'connecting':
        return '连接中'
      case 'connected':
        return '已连接'
      case 'reconnecting':
        return '重连中'
      case 'disconnected':
        return '已断开'
      default:
        return '空闲'
    }
  }

  const displayEventType = (value: string) => {
    const labels: Record<string, string> = {
      'routing.decided': '路由决策完成',
      'execution.started': '执行开始',
      'execution.completed': '执行完成',
      'execution.failed': '执行失败',
      'execution.suspended': '执行挂起',
      'execution.waiting_user': '等待用户输入',
      'execution.waiting_tool': '等待工具结果',
      'execution.resumed': '执行恢复',
      'execution.switch_requested': '请求切换流程',
      'execution.resume_offered': '提示恢复流程',
      'node.started': '节点开始',
      'node.completed': '节点完成',
      'node.failed': '节点失败',
      'form.requested': '请求表单',
      'tool.called': '调用工具',
      'tool.returned': '工具返回',
      'security.prompt_sanitized': '提示词已清洗',
      'security.output_rejected': '输出已拦截',
      'budget.alert': '预算预警',
      'confirmation.required': '需要二次确认',
      'protection.rate_limited': '触发限流',
      'protection.degraded': '已降级执行',
      'protection.circuit_open': '熔断已打开',
      'optimization.vector_access': '向量访问优化',
      'workflow.validation_failed': '流程校验失败',
    }
    return labels[value] || value
  }

  const displayStatus = (value?: string) => {
    switch ((value || '').toLowerCase()) {
      case 'running':
        return '运行中'
      case 'completed':
        return '已完成'
      case 'failed':
        return '失败'
      case 'waiting_user':
        return '等待用户'
      case 'waiting_tool':
        return '等待工具'
      case 'suspended':
        return '已挂起'
      default:
        return value || '空闲'
    }
  }

  return (
    <div className="panel-card h-full flex flex-col">
      <div className="panel-header">
        <div>
          <div className="panel-title">执行面板</div>
          <div className="text-xs text-slate-500">状态：{displayStatus(status)} · 连接：{displaySocketState(socketState)}</div>
        </div>
        <div className="text-xs text-slate-400 truncate max-w-[180px]">
          {executionId || '会话级视图'}
        </div>
      </div>
      <div className="panel-body space-y-4 overflow-y-auto">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">当前执行栈</div>
          {executions.length === 0 && (
            <div className="text-sm text-slate-500">当前会话还没有执行实例。</div>
          )}
          <ul className="space-y-2">
            {executions.map((execution) => (
              <li key={execution.execution_id} className="rounded-xl border border-slate-200 bg-slate-50/80 px-3 py-2">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <div className="text-sm font-medium text-slate-800">{execution.workflow_code}</div>
                    <div className="text-xs text-slate-500">
                      {execution.workflow_version} · {execution.current_node_id || '待定'}
                    </div>
                  </div>
                  <div className="text-xs font-medium text-slate-600">{displayStatus(execution.status)}</div>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400 mb-2">最近事件</div>
          {events.length === 0 && (
            <div className="text-sm text-slate-500">等待执行事件...</div>
          )}
          <ul className="space-y-2">
            {events.map((event) => (
              <li key={event.id} className="text-sm text-slate-700">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{displayEventType(event.event_type)}</span>
                  <span className="text-xs text-slate-400">{event.timestamp}</span>
                </div>
                {(event.node_id || event.node_type || event.status) && (
                  <div className="text-xs text-slate-500">
                    {event.execution_id ? `执行：${event.execution_id}` : ''}
                    {event.node_id ? ` · 节点：${event.node_id}` : ''}
                    {event.node_type ? ` · 类型：${event.node_type}` : ''}
                    {event.tool_code ? ` · 工具：${event.tool_code}` : ''}
                    {event.status ? ` · 状态：${displayStatus(event.status)}` : ''}
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
