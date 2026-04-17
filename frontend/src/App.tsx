import React, { useEffect, useRef, useState } from 'react'
import AnalyticsPanel from './components/AnalyticsPanel'
import ChatInput from './components/ChatInput'
import ExecutionPanel from './components/ExecutionPanel'
import FormDialog from './components/FormDialog'
import GovernancePanel from './components/GovernancePanel'
import InsightsPanel from './components/InsightsPanel'
import MessageList from './components/MessageList'
import Orchestrator from './components/Orchestrator'
import ReplayPanel from './components/ReplayPanel'
import WorkflowPanel from './components/WorkflowPanel'
import { getSessionExecutions } from './services/api'
import type {
  ExecutionDetail,
  ExecutionEventEnvelope,
  ExecutionEventView,
  FormDefinition,
  GatewayAckEnvelope,
  GatewayErrorEnvelope,
  GovernanceNotice,
  Message,
  MessageDeltaEnvelope,
  SendMessageResponse,
  SocketState,
  WebSocketEnvelope,
} from './types'

const App: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [currentUserId, setCurrentUserId] = useState(() => window.localStorage.getItem('robot-user-id') || 'demo-user')
  const [executionId, setExecutionId] = useState<string | null>(null)
  const [executions, setExecutions] = useState<ExecutionDetail[]>([])
  const [events, setEvents] = useState<ExecutionEventView[]>([])
  const [executionStatus, setExecutionStatus] = useState('idle')
  const [socketState, setSocketState] = useState<SocketState>('idle')
  const [pendingSwitch, setPendingSwitch] = useState<{
    content: string
    messageId: string
    response: SendMessageResponse
  } | null>(null)
  const [pendingConfirmation, setPendingConfirmation] = useState<{
    content: string
    messageId: string
    response: SendMessageResponse
  } | null>(null)
  const [resumeOffer, setResumeOffer] = useState<{
    executionId: string
    workflowCode: string
    workflowVersion: string
    currentNodeId?: string
  } | null>(null)
  const [pendingForm, setPendingForm] = useState<{
    executionId: string
    form: FormDefinition
  } | null>(null)
  const [governanceNotice, setGovernanceNotice] = useState<GovernanceNotice | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectAttemptsRef = useRef(0)
  const pendingRequestsRef = useRef(
    new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>()
  )

  useEffect(() => {
    const existing = window.localStorage.getItem('robot-session-id')
    if (existing) {
      setSessionId(existing)
    } else {
      const created = createId('sess')
      window.localStorage.setItem('robot-session-id', created)
      setSessionId(created)
    }

    setMessages([
      {
        id: 'welcome',
        type: 'system',
        content: '欢迎使用服务机器人。第五阶段已启用 ABAC、二次确认、限流/降级和日志归档可见性。输入“取消订单”或“更新权限”可触发高风险确认。',
        timestamp: new Date().toISOString(),
      },
    ])
  }, [])

  useEffect(() => {
    if (!sessionId) return
    void refreshExecutions(sessionId)

    const wsUrl = buildWsUrl(sessionId)
    let isCancelled = false

    const connect = (attempt = 0) => {
      if (isCancelled) return
      setSocketState(attempt === 0 ? 'connecting' : 'reconnecting')
      const socket = new WebSocket(wsUrl)
      wsRef.current = socket

      socket.onopen = () => {
        reconnectAttemptsRef.current = 0
        setSocketState('connected')
      }

      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(event.data) as WebSocketEnvelope
          if (payload.type === 'message_delta') {
            handleMessageDelta(payload)
          } else if (payload.type === 'event') {
            handleExecutionEvent(payload)
          } else if (payload.type === 'ack') {
            handleGatewayAck(payload)
          } else if (payload.type === 'error') {
            handleGatewayError(payload)
          }
        } catch (error) {
          console.error('Invalid WebSocket payload:', error)
        }
      }

      socket.onerror = () => {
        setSocketState('reconnecting')
      }

      socket.onclose = () => {
        wsRef.current = null
        pendingRequestsRef.current.forEach(({ reject }) => reject(new Error('WebSocket disconnected')))
        pendingRequestsRef.current.clear()
        if (isCancelled) {
          setSocketState('idle')
          return
        }
        reconnectAttemptsRef.current += 1
        if (reconnectAttemptsRef.current <= 5) {
          const delay = Math.min(1000 * (2 ** (reconnectAttemptsRef.current - 1)), 5000)
          reconnectTimerRef.current = window.setTimeout(() => connect(reconnectAttemptsRef.current), delay)
          setSocketState('reconnecting')
          return
        }
        setSocketState('disconnected')
      }
    }

    connect()

    return () => {
      isCancelled = true
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current)
      }
      wsRef.current?.close()
    }
  }, [sessionId])

  const buildWsUrl = (activeSessionId: string) => {
    const base = import.meta.env.VITE_NETTY_WS_BASE_URL || import.meta.env.VITE_WS_BASE_URL
    const origin =
      base ||
      `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.hostname}:8091`
    const url = new URL(`${origin}/ws/robot`)
    url.searchParams.set('session_id', activeSessionId)
    return url.toString()
  }

  const createId = (prefix: string) => {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
      return `${prefix}_${crypto.randomUUID()}`
    }
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  }

  const normalizeStatus = (value: string) => value.toLowerCase()

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

  const displayExecutionStatus = (value: string) => {
    switch ((value || '').toLowerCase()) {
      case 'running':
        return '运行中'
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
        return '等待确认'
      case 'permission_denied':
        return '权限拒绝'
      case 'rate_limited':
        return '已限流'
      case 'degraded':
        return '已降级'
      default:
        return '空闲'
    }
  }

  const displayUserLabel = (value: string) => {
    switch (value) {
      case 'demo-admin':
        return '演示管理员'
      case 'anonymous':
        return '匿名用户'
      default:
        return '演示用户'
    }
  }

  const refreshExecutions = async (activeSessionId: string) => {
    try {
      const latest = await getSessionExecutions(activeSessionId)
      setExecutions(latest)
      if (latest[0]) {
        setExecutionStatus(normalizeStatus(latest[0].status))
        setExecutionId(latest[0].execution_id)
      }
    } catch (error) {
      console.error('获取 execution 列表失败:', error)
    }
  }

  const appendSystemMessage = (content: string) => {
    setMessages((prev) => [
      ...prev,
      {
        id: createId('sys'),
        type: 'system',
        content,
        timestamp: new Date().toISOString(),
      },
    ])
  }

  const pushGovernanceNotice = (
    title: string,
    detail: string,
    tone: GovernanceNotice['tone'] = 'info'
  ) => {
    setGovernanceNotice({
      title,
      detail,
      tone,
      updated_at: new Date().toISOString(),
    })
  }

  const finalizeStreamingMessage = (activeExecutionId: string) => {
    setMessages((prev) =>
      prev.map((message) =>
        message.id === `stream_${activeExecutionId}`
          ? { ...message, streaming: false }
          : message
      )
    )
  }

  const handleMessageDelta = (payload: MessageDeltaEnvelope) => {
    if (payload.session_id && payload.session_id !== sessionId) return

    const messageId = `stream_${payload.execution_id}`
    setMessages((prev) => {
      const index = prev.findIndex((message) => message.id === messageId)
      if (index >= 0) {
        const updated = [...prev]
        const current = updated[index]
        updated[index] = {
          ...current,
          content: `${current.content}${payload.content}`,
          streaming: !payload.is_complete,
        }
        return updated
      }

      return [
        ...prev,
        {
          id: messageId,
          type: 'ai',
          content: payload.content,
          streaming: !payload.is_complete,
          timestamp: new Date().toISOString(),
        },
      ]
    })

    if (payload.is_complete) {
      setIsLoading(false)
      setExecutionStatus('completed')
    }
  }

  const handleGatewayAck = (payload: GatewayAckEnvelope) => {
    const pending = pendingRequestsRef.current.get(payload.request_id)
    if (!pending) return
    pendingRequestsRef.current.delete(payload.request_id)
    pending.resolve((payload.data as Record<string, unknown> | undefined) ?? undefined)
  }

  const handleGatewayError = (payload: GatewayErrorEnvelope) => {
    if (payload.request_id) {
      const pending = pendingRequestsRef.current.get(payload.request_id)
      if (pending) {
        pendingRequestsRef.current.delete(payload.request_id)
        pending.reject(new Error(payload.message))
        return
      }
    }
    appendSystemMessage(`网关错误：${payload.message}`)
  }

  const handleExecutionEvent = (payload: ExecutionEventEnvelope) => {
    if (payload.session_id && payload.session_id !== sessionId) return

    const data = payload.data || {}
    const rawStatus = (data as any).status
    const event: ExecutionEventView = {
      id: createId('evt'),
      execution_id: payload.execution_id,
      session_id: payload.session_id,
      event_type: payload.event_type,
      node_id: (data as any).node_id,
      node_type: (data as any).node_type,
      tool_code: (data as any).tool_code,
      status: typeof rawStatus === 'string' ? normalizeStatus(rawStatus) : rawStatus,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
    }

    setEvents((prev) => [event, ...prev])

    if (
      payload.event_type === 'execution.started' ||
      payload.event_type === 'execution.completed' ||
      payload.event_type === 'execution.failed' ||
      payload.event_type === 'execution.suspended' ||
      payload.event_type === 'execution.waiting_user' ||
      payload.event_type === 'execution.resumed'
    ) {
      setExecutionId(payload.execution_id)
      void refreshExecutions(sessionId)
    }

    if (payload.event_type === 'form.requested') {
      finalizeStreamingMessage(payload.execution_id)
      const definition = (data as any).form_definition as FormDefinition | undefined
      if (definition) {
        setPendingForm({ executionId: payload.execution_id, form: definition })
        setExecutionStatus('waiting_user')
      }
    }

    if (payload.event_type === 'execution.switch_requested') {
      appendSystemMessage('当前流程已暂存，正在切换到新的目标流程。')
    }

    if (payload.event_type === 'execution.resume_offered') {
      setResumeOffer({
        executionId: payload.execution_id,
        workflowCode: String((data as any).workflow_code || ''),
        workflowVersion: String((data as any).workflow_version || ''),
        currentNodeId: (data as any).current_node_id ? String((data as any).current_node_id) : undefined,
      })
      appendSystemMessage(`流程 ${String((data as any).workflow_code || payload.execution_id)} 可恢复，是否继续？`)
    }

    if (payload.event_type === 'security.prompt_sanitized') {
      appendSystemMessage('检测到高风险 Prompt 片段，系统已自动清洗后继续处理。')
    }

    if (payload.event_type === 'security.output_rejected') {
      appendSystemMessage('模型输出未通过结构化校验，已阻断异常结果。')
    }

    if (payload.event_type === 'budget.alert') {
      appendSystemMessage(`预算预警：${String((data as any).message || '成本达到告警阈值')}`)
    }

    if (payload.event_type === 'protection.degraded') {
      const detail = String((data as any).reason || '依赖服务进入降级执行')
      pushGovernanceNotice('运行时已降级', detail, 'warning')
      appendSystemMessage(`系统已降级执行：${detail}`)
    }

    if (payload.event_type === 'protection.circuit_open') {
      const detail = `tool=${String((data as any).tool_code || 'unknown')} · failures=${String((data as any).failures || 0)}`
      pushGovernanceNotice('依赖熔断已打开', detail, 'warning')
    }

    if (payload.event_type === 'protection.rate_limited') {
      const detail = String((data as any).scope || 'runtime')
      pushGovernanceNotice('运行时限流触发', detail, 'warning')
    }

    if (payload.event_type === 'workflow.validation_failed') {
      pushGovernanceNotice('流程草稿校验失败', '请先修复画布中的配置问题。', 'warning')
    }

    if (payload.event_type === 'execution.completed') {
      finalizeStreamingMessage(payload.execution_id)
      setExecutionStatus('completed')
      setIsLoading(false)
    }

    if (payload.event_type === 'execution.failed') {
      finalizeStreamingMessage(payload.execution_id)
      setExecutionStatus('failed')
      setIsLoading(false)
    }
  }

  const sendGatewayAction = (
    action: string,
    payload: Record<string, unknown>,
    targetSessionId = sessionId
  ): Promise<unknown> => {
    const socket = wsRef.current
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('WebSocket not connected'))
    }

    const requestId = createId('req')
    socket.send(
      JSON.stringify({
        type: 'action',
        request_id: requestId,
        action,
        session_id: targetSessionId,
        payload,
      })
    )

    return new Promise((resolve, reject) => {
      pendingRequestsRef.current.set(requestId, { resolve, reject })
    })
  }

  const handleSendMessage = async (
    content: string,
    options?: {
      confirmSwitch?: boolean
      confirmationId?: string
      cancelConfirmation?: boolean
      requestedToolCode?: string
    },
    fixedMessageId?: string
  ) => {
    if (!content.trim()) return

    const messageId = fixedMessageId || createId('msg')
    const shouldAppendUserMessage = !options?.confirmSwitch && !options?.confirmationId && !options?.cancelConfirmation

    if (shouldAppendUserMessage) {
      const userMessage: Message = {
        id: createId('msg'),
        type: 'user',
        content,
        timestamp: new Date().toISOString(),
      }
      setMessages((prev) => [...prev, userMessage])
    }

    const pendingMessageId = `stream_pending_${messageId}`
    if (shouldAppendUserMessage) {
      setMessages((prev) => [
        ...prev,
        {
          id: pendingMessageId,
          type: 'ai',
          content: '',
          streaming: true,
          timestamp: new Date().toISOString(),
        },
      ])
    }

    setIsLoading(true)

    try {
      let activeSessionId = sessionId
      if (!activeSessionId) {
        activeSessionId = createId('sess')
        window.localStorage.setItem('robot-session-id', activeSessionId)
        setSessionId(activeSessionId)
      }

      const response = (await sendGatewayAction('send_message', {
        message_id: messageId,
        content,
        attachments: [],
        user_id: currentUserId,
        confirm_switch: options?.confirmSwitch ?? false,
        requested_tool_code: options?.requestedToolCode ?? null,
        confirmation_id: options?.confirmationId ?? null,
        cancel_confirmation: options?.cancelConfirmation ?? false,
      }, activeSessionId)) as unknown as SendMessageResponse

      if (response.status === 'permission_denied') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus('permission_denied')
        setPendingConfirmation(null)
        pushGovernanceNotice('权限拒绝', response.permission_reason || 'ABAC 拒绝当前操作', 'danger')
        appendSystemMessage(`权限拒绝：${response.permission_reason || '当前用户不允许执行该操作。'}`)
        return
      }

      if (response.status === 'confirmation_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingConfirmation({ content, messageId, response })
        setExecutionStatus('confirmation_required')
        pushGovernanceNotice(
          '等待高风险确认',
          `${response.requested_tool_code || 'unknown'} 需要在 ${response.confirmation_expires_at || '5 分钟内'} 完成确认`,
          'warning'
        )
        appendSystemMessage(`检测到高风险操作 ${response.requested_tool_code}，请先确认再继续。`)
        return
      }

      if (response.status === 'confirmation_cancelled') {
        setPendingConfirmation(null)
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus('idle')
        pushGovernanceNotice('高风险确认已取消', '本次敏感操作已取消，不会继续执行。', 'info')
        appendSystemMessage('高风险操作已取消。')
        return
      }

      if (response.status === 'rate_limited' || response.status === 'degraded') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus(response.status)
        setPendingConfirmation(null)
        const detail =
          response.degradation_message ||
          response.protection_reason ||
          `请在 ${response.retry_after_seconds || 0} 秒后重试。`
        pushGovernanceNotice(
          response.status === 'rate_limited' ? '请求已限流' : '入口已降级',
          detail,
          'warning'
        )
        appendSystemMessage(detail)
        return
      }

      if (response.status === 'switch_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingSwitch({ content, messageId, response })
        setExecutionStatus('switch_required')
        appendSystemMessage(
          `检测到新意图将切换到 ${response.workflow_code}，当前流程仍在执行。请确认是否暂存并切换。`
        )
        return
      }

      setPendingSwitch(null)
      setPendingConfirmation(null)
      if (response.execution_id) {
        setExecutionId(response.execution_id)
      }
      setExecutionStatus(response.status ? normalizeStatus(response.status) : 'running')
      setEvents([])
      setResumeOffer(null)
      setMessages((prev) =>
        prev.map((message) =>
          message.id === pendingMessageId && response.execution_id
            ? { ...message, id: `stream_${response.execution_id}` }
            : message
        )
      )
      await refreshExecutions(activeSessionId)
    } catch (error) {
      console.error('发送消息失败:', error)
      setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
      setMessages((prev) => [
        ...prev,
        {
          id: createId('err'),
          type: 'error',
          content: '发送消息失败，请稍后重试。',
          timestamp: new Date().toISOString(),
        },
      ])
      setExecutionStatus('failed')
    } finally {
      setIsLoading(false)
    }
  }

  const handleResume = async () => {
    if (!resumeOffer) return
    try {
      const response = (await sendGatewayAction('resume_execution', {
        execution_id: resumeOffer.executionId,
      })) as unknown as {
        execution_id: string
        status: string
        form_definition?: string | null
      }
      setExecutionId(response.execution_id)
      setExecutionStatus(normalizeStatus(response.status))
      setResumeOffer(null)
      if (response.form_definition) {
        setPendingForm({
          executionId: response.execution_id,
          form: JSON.parse(response.form_definition) as FormDefinition,
        })
      }
      await refreshExecutions(sessionId)
    } catch (error) {
      console.error('恢复 execution 失败:', error)
      setExecutionStatus('failed')
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">服务机器人</h1>
          <div className="text-sm text-slate-500">第五阶段 生产优化与治理</div>
        </div>
        <div className="flex items-center gap-3 text-xs text-slate-400">
          <label className="flex items-center gap-2">
            <span>用户</span>
            <select
              value={currentUserId}
              onChange={(event) => {
                const value = event.target.value
                window.localStorage.setItem('robot-user-id', value)
                setCurrentUserId(value)
              }}
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            >
              <option value="demo-user">演示用户</option>
              <option value="demo-admin">演示管理员</option>
              <option value="anonymous">匿名用户</option>
            </select>
          </label>
          <span>会话：{sessionId || '加载中'} · 连接：{displaySocketState(socketState)}</span>
        </div>
      </header>

      <main className="app-main">
        <section className="app-chat">
          <div className="panel-card flex-1 flex flex-col">
            <div className="panel-header">
              <div className="panel-title">对话窗口</div>
              <div className="text-xs text-slate-500">执行状态：{displayExecutionStatus(executionStatus)}</div>
            </div>
            <MessageList messages={messages} isLoading={isLoading} />
            <div className="panel-footer">
              <div className="mb-2 text-xs text-slate-400">
                当前用户：{displayUserLabel(currentUserId)} · 高风险短语示例：取消订单 / 更新权限
              </div>
              <ChatInput onSendMessage={(content) => void handleSendMessage(content)} isLoading={isLoading} />
            </div>
          </div>
        </section>

        <section className="app-side">
          <div className="side-stack">
            {pendingSwitch && (
              <div className="panel-card prompt-card">
                <div className="panel-title mb-2">需要切换流程</div>
                <div className="text-sm text-slate-700">
                  当前运行流程与新意图冲突，目标流程是 <strong>{pendingSwitch.response.workflow_code}</strong>。
                </div>
                <div className="text-xs text-slate-500 mt-2">
                  决策：{pendingSwitch.response.route_decision} · 置信度：{' '}
                  {pendingSwitch.response.route_confidence?.toFixed(2) ?? '未知'}
                </div>
                <div className="prompt-actions">
                  <button
                    className="prompt-secondary"
                    onClick={() => setPendingSwitch(null)}
                  >
                    保持当前流程
                  </button>
                  <button
                    className="prompt-primary"
                    onClick={() =>
                      void handleSendMessage(
                        pendingSwitch.content,
                        { confirmSwitch: true },
                        pendingSwitch.messageId
                      )
                    }
                  >
                    暂存并切换
                  </button>
                </div>
              </div>
            )}

            {resumeOffer && (
              <div className="panel-card prompt-card">
                <div className="panel-title mb-2">恢复提示</div>
                <div className="text-sm text-slate-700">
                  已有挂起流程 <strong>{resumeOffer.workflowCode}</strong> 待恢复。
                </div>
                <div className="text-xs text-slate-500 mt-2">
                  版本：{resumeOffer.workflowVersion} · 节点：{resumeOffer.currentNodeId || '待定'}
                </div>
                <div className="prompt-actions">
                  <button
                    className="prompt-secondary"
                    onClick={() => setResumeOffer(null)}
                  >
                    稍后恢复
                  </button>
                  <button className="prompt-primary" onClick={() => void handleResume()}>
                    立即恢复
                  </button>
                </div>
              </div>
            )}

            {pendingConfirmation && (
              <div className="panel-card prompt-card">
                <div className="panel-title mb-2">高风险二次确认</div>
                <div className="text-sm text-slate-700">
                  操作 <strong>{pendingConfirmation.response.requested_tool_code}</strong> 需要二次确认后才能继续。
                </div>
                <div className="text-xs text-slate-500 mt-2">
                  失效时间：{pendingConfirmation.response.confirmation_expires_at || '5 分钟内'}
                </div>
                <div className="prompt-actions">
                  <button
                    className="prompt-secondary"
                    onClick={() =>
                      void handleSendMessage(
                        pendingConfirmation.content,
                        {
                          requestedToolCode: pendingConfirmation.response.requested_tool_code,
                          confirmationId: pendingConfirmation.response.confirmation_id,
                          cancelConfirmation: true,
                        },
                        pendingConfirmation.messageId
                      )
                    }
                  >
                    取消操作
                  </button>
                  <button
                    className="prompt-primary"
                    onClick={() =>
                      void handleSendMessage(
                        pendingConfirmation.content,
                        {
                          requestedToolCode: pendingConfirmation.response.requested_tool_code,
                          confirmationId: pendingConfirmation.response.confirmation_id,
                        },
                        pendingConfirmation.messageId
                      )
                    }
                  >
                    确认继续
                  </button>
                </div>
              </div>
            )}

            <Orchestrator currentUserId={currentUserId} sendGatewayAction={sendGatewayAction} />
            <GovernancePanel sessionId={sessionId} notice={governanceNotice} />
            <WorkflowPanel />
            <InsightsPanel workflowCode={executions[0]?.workflow_code} />
            <AnalyticsPanel sessionId={sessionId} />
            <ReplayPanel executionId={executionId} />
            <ExecutionPanel
              events={events}
              executions={executions}
              status={executionStatus}
              executionId={executionId}
              socketState={socketState}
            />
          </div>
        </section>
      </main>

      {pendingForm && (
        <FormDialog
          form={pendingForm.form}
          onClose={() => setPendingForm(null)}
          onSubmit={async (data) => {
            try {
              await sendGatewayAction('submit_form', {
                execution_id: pendingForm.executionId,
                submit_id: createId('submit'),
                form_data: data,
              })
              setPendingForm(null)
              setExecutionStatus('running')
              setIsLoading(true)
              await refreshExecutions(sessionId)
            } catch (error) {
              console.error('表单提交失败:', error)
              setExecutionStatus('failed')
            }
          }}
        />
      )}
    </div>
  )
}

export default App
