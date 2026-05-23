import type { MessageType, SocketState } from '../types'

export function displaySocketState(value: SocketState): string {
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

export function displayExecutionStatus(value?: string | null): string {
  switch ((value || '').toLowerCase()) {
    case 'running':
      return '执行中'
    case 'completed':
      return '已完成'
    case 'failed':
      return '执行失败'
    case 'waiting_user':
      return '等待用户'
    case 'waiting_tool':
      return '等待工具'
    case 'suspended':
      return '已挂起'
    case 'switch_required':
      return '需要切换'
    case 'confirmation_required':
      return '需要确认'
    case 'clarification_required':
      return '未匹配服务'
    case 'confirmation_cancelled':
      return '已取消确认'
    case 'permission_denied':
      return '权限不足'
    case 'rate_limited':
      return '已限流'
    case 'degraded':
      return '已降级'
    default:
      return value ? value : '空闲'
  }
}

export function displaySessionStatus(value?: string | null): string {
  switch ((value || '').toLowerCase()) {
    case 'active':
      return '进行中'
    case 'closed':
      return '已关闭'
    default:
      return value || '未知'
  }
}

export function displayUserLabel(value: string): string {
  switch (value) {
    case 'demo-admin':
      return '演示管理员'
    case 'anonymous':
      return '匿名用户'
    default:
      return '演示用户'
  }
}

export function displayMessageType(value?: MessageType | string | null): string {
  switch (value) {
    case 'user':
      return '用户'
    case 'ai':
      return '机器人'
    case 'system':
      return '系统'
    case 'error':
      return '错误'
    default:
      return value || '未知'
  }
}

export function displayEventType(value?: string | null): string {
  const labels: Record<string, string> = {
    'routing.decided': '路由决策完成',
    'plan.created': '已生成执行计划',
    'plan.replanned': '已重新规划',
    'branch.decided': '分支决策完成',
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
    'node.skipped': '节点跳过',
    'node.completed': '节点完成',
    'node.failed': '节点失败',
    'form.requested': '请求表单',
    'tool.called': '调用工具',
    'tool.returned': '工具返回',
    'security.prompt_sanitized': '提示词已清洗',
    'security.output_rejected': '输出已拦截',
    'cost.recorded': '成本已记录',
    'budget.alert': '预算预警',
    'replay.snapshot_ready': '回放快照已就绪',
    'confirmation.required': '需要二次确认',
    'protection.rate_limited': '触发限流',
    'protection.degraded': '已降级执行',
    'protection.circuit_open': '熔断已打开',
    'optimization.vector_access': '向量访问优化',
    'workflow.validation_failed': '流程校验失败',
  }
  return labels[value || ''] || (value || '未知事件')
}

export function displayNodeRuntimeStatus(value?: string | null): string {
  switch ((value || '').toLowerCase()) {
    case 'pending':
      return '待执行'
    case 'running':
      return '执行中'
    case 'completed':
      return '已完成'
    case 'failed':
      return '失败'
    case 'skipped':
      return '已跳过'
    default:
      return value || '未知'
  }
}

export function displayNodeKind(value?: string | null): string {
  switch ((value || '').toLowerCase()) {
    case 'tool':
      return '工具'
    case 'message':
      return '消息'
    case 'start':
      return '开始'
    case 'end':
      return '结束'
    case 'coordinate':
      return '协调'
    case 'sub_agent':
      return '子代理'
    default:
      return value || '未知'
  }
}
