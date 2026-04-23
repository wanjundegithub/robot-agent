import React, { useCallback, useEffect, useRef, useState } from 'react'
import ChatInput from './components/ChatInput'
import ExecutionPanel from './components/ExecutionPanel'
import FormDialog from './components/FormDialog'
import MessageList from './components/MessageList'
import ModelConfigPanel from './components/ModelConfigPanel'
import Orchestrator from './components/Orchestrator'
import ReplayPanel from './components/ReplayPanel'
import SessionReplayPanel from './components/SessionReplayPanel'
import WorkflowPanel from './components/WorkflowPanel'
import { createSession, getPublishedWorkflows, getSession, getSessionExecutions, getSessionMessages, getSessionsByUserId } from './services/api'
import type {
  ExecutionDetail,
  ExecutionEventEnvelope,
  ExecutionEventView,
  FormDefinition,
  GatewayAckEnvelope,
  GatewayErrorEnvelope,
  Message,
  MessageDeltaEnvelope,
  SessionSummary,
  SendMessageResponse,
  SocketState,
  WebSocketEnvelope,
  WorkflowEditorSelection,
  WorkflowSummary,
} from './types'
import type { OrchestratorHandle, WorkflowSidebarState, WorkflowVersionMutation } from './components/Orchestrator'

interface WorkflowDraftState {
  workflowCode: string
  workflowVersion: string
  definition: Record<string, unknown>
  entryRule: Record<string, unknown>
  workflowConfig: Record<string, unknown>
}

type PageKey = 'chat' | 'workflow' | 'execution' | 'models'

const gatewayLog = (event: string, details?: Record<string, unknown>) => {
  if (details) {
    console.info(`[gateway] ${event}`, details)
    return
  }
  console.info(`[gateway] ${event}`)
}

const createWelcomeMessage = (): Message => ({
  id: 'welcome',
  type: 'system',
  content: 'Welcome. You can start a conversation directly or select a published workflow for targeted testing.',
  timestamp: new Date().toISOString(),
})

const App: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([createWelcomeMessage()])
  const [isLoading, setIsLoading] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [currentSession, setCurrentSession] = useState<SessionSummary | null>(null)
  const [currentUserId, setCurrentUserId] = useState('demo-user')
  const [executionId, setExecutionId] = useState<string | null>(null)
  const [executions, setExecutions] = useState<ExecutionDetail[]>([])
  const [events, setEvents] = useState<ExecutionEventView[]>([])
  const [executionStatus, setExecutionStatus] = useState('idle')
  const [socketState, setSocketState] = useState<SocketState>('idle')
  const [socketSessionId, setSocketSessionId] = useState('')
  const [activePage, setActivePage] = useState<PageKey>('chat')
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
  const [workflowDraft, setWorkflowDraft] = useState<WorkflowDraftState | null>(null)
  const [publishedWorkflowOptions, setPublishedWorkflowOptions] = useState<WorkflowSummary[]>([])
  const [selectedPublishedWorkflowCode, setSelectedPublishedWorkflowCode] = useState('')
  const [workflowSidebarState, setWorkflowSidebarState] = useState<WorkflowSidebarState | null>(null)
  const [workflowVersionMutation, setWorkflowVersionMutation] = useState<WorkflowVersionMutation | null>(null)
  const [workflowNameInput, setWorkflowNameInput] = useState('')
  const [workflowEditorSelection, setWorkflowEditorSelection] = useState<WorkflowEditorSelection | null>(null)
  const orchestratorRef = useRef<OrchestratorHandle | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const activeSocketSessionIdRef = useRef<string | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectAttemptsRef = useRef(0)
  const pendingRequestsRef = useRef(
    new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>()
  )

  const loadPublishedWorkflowOptions = useCallback(async () => {
    try {
      const items = await getPublishedWorkflows()
      const filteredItems = items.filter((item) => item.createdBy !== 'system')
      setPublishedWorkflowOptions(filteredItems)
      setSelectedPublishedWorkflowCode((current) =>
        filteredItems.some((item) => item.workflowCode === current) ? current : ''
      )
    } catch (error) {
      console.error('鑾峰彇宸插彂甯冩祦绋嬪け璐?', error)
    }
  }, [])

  const resetSessionView = useCallback(() => {
    setMessages([createWelcomeMessage()])
    setEvents([])
    setExecutions([])
    setExecutionId(null)
    setExecutionStatus('idle')
    setPendingSwitch(null)
    setPendingConfirmation(null)
    setResumeOffer(null)
    setPendingForm(null)
    setIsLoading(false)
  }, [])

  const refreshSessionDetail = useCallback(async (activeSessionId: string) => {
    try {
      const detail = await getSession(activeSessionId)
      setCurrentSession(detail)
      setSessions((prev) => [detail, ...prev.filter((session) => session.id !== detail.id)])
    } catch (error) {
      console.error('鑾峰彇浼氳瘽璇︽儏澶辫触:', error)
    }
  }, [])

  const loadSessionMessages = useCallback(async (activeSessionId: string) => {
    try {
      const history = await getSessionMessages(activeSessionId)
      setMessages(history.length > 0 ? history : [createWelcomeMessage()])
    } catch (error) {
      console.error('鑾峰彇浼氳瘽娑堟伅澶辫触:', error)
      setMessages([createWelcomeMessage()])
    }
  }, [])

  const createAndSelectSession = useCallback(async (userId: string) => {
    const created = await createSession({ userId })
    setSessions((prev) => [created, ...prev.filter((session) => session.id !== created.id)])
    setCurrentSession(created)
    setSessionId(created.id)
    return created
  }, [])

  const loadUserSessions = useCallback(async (userId: string) => {
    try {
      const items = await getSessionsByUserId(userId)
      if (items.length === 0) {
        await createAndSelectSession(userId)
        return
      }
      setSessions(items)
      setCurrentSession(items[0])
      setSessionId(items[0]?.id ?? '')
    } catch (error) {
      console.error('鑾峰彇浼氳瘽鍒楄〃澶辫触:', error)
    }
  }, [createAndSelectSession])
  void loadUserSessions

  useEffect(() => {
    void loadPublishedWorkflowOptions()
  }, [loadPublishedWorkflowOptions])

  useEffect(() => {
    let cancelled = false

    const initializeSession = async () => {
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      activeSocketSessionIdRef.current = null
      wsRef.current?.close()
      wsRef.current = null
      setSocketSessionId('')
      setSocketState('idle')
      resetSessionView()
      setCurrentSession(null)
      setSessionId('')
      try {
        const created = await createSession({ userId: currentUserId })
        if (cancelled) return
        setCurrentSession(created)
        setSessionId(created.id)
      } catch (error) {
        if (!cancelled) {
          console.error('閸掓繂顫愰崠鏍︾窗鐠囨繂銇戠拹?', error)
        }
      }
    }

    void initializeSession()

    return () => {
      cancelled = true
    }
  }, [currentUserId, resetSessionView])

  useEffect(() => {
    setWorkflowNameInput(workflowSidebarState?.workflowName ?? '')
  }, [workflowSidebarState?.workflowName])

  useEffect(() => {
    if (!workflowVersionMutation) return
    void loadPublishedWorkflowOptions()
  }, [loadPublishedWorkflowOptions, workflowVersionMutation])

  useEffect(() => {
    if (!sessionId) return
    resetSessionView()
    void loadSessionMessages(sessionId)
    void refreshSessionDetail(sessionId)
  }, [loadSessionMessages, refreshSessionDetail, resetSessionView, sessionId])

  useEffect(() => {
    const syncPageFromHash = () => {
      const value = window.location.hash.replace('#', '')
      if (value === 'workflow' || value === 'execution' || value === 'models' || value === 'chat') {
        setActivePage(value)
        return
      }
      window.location.hash = 'chat'
      setActivePage('chat')
    }

    syncPageFromHash()
    window.addEventListener('hashchange', syncPageFromHash)
    return () => window.removeEventListener('hashchange', syncPageFromHash)
  }, [])

  useEffect(() => {
    if (activePage !== 'chat' || !socketSessionId) {
      if (reconnectTimerRef.current !== null) {
        window.clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      activeSocketSessionIdRef.current = null
      wsRef.current?.close()
      wsRef.current = null
      setSocketState('idle')
      return
    }
    void (async () => {
      try {
        const latest = await getSessionExecutions(socketSessionId)
        setExecutions(latest)
        if (latest[0]) {
          setExecutionStatus(normalizeStatus(latest[0].status))
          setExecutionId(latest[0].execution_id)
        } else {
          setExecutionStatus('idle')
          setExecutionId(null)
        }
      } catch (error) {
        console.error('Failed to load execution list:', error)
      }
    })()

    const wsUrl = buildWsUrl(socketSessionId)
    let isCancelled = false

    const connect = (attempt = 0) => {
      if (isCancelled) return
      setSocketState(attempt === 0 ? 'connecting' : 'reconnecting')
      const socket = new WebSocket(wsUrl)
      wsRef.current = socket
      gatewayLog('ws.connecting', { attempt, wsUrl, sessionId: socketSessionId })

      socket.onopen = () => {
        reconnectAttemptsRef.current = 0
        activeSocketSessionIdRef.current = socketSessionId
        setSocketState('connected')
        gatewayLog('ws.open', { sessionId: socketSessionId, wsUrl })
      }

      socket.onmessage = (event) => {
        try {
          const payload = JSON.parse(event.data) as WebSocketEnvelope
          gatewayLog('ws.message', {
            type: payload.type,
            session_id: (payload as any).session_id,
            execution_id: (payload as any).execution_id,
            request_id: (payload as any).request_id,
            event_type: (payload as any).event_type,
          })
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
        activeSocketSessionIdRef.current = null
        setSocketState('reconnecting')
        gatewayLog('ws.error', { sessionId: socketSessionId, wsUrl })
      }

      socket.onclose = () => {
        wsRef.current = null
        activeSocketSessionIdRef.current = null
        gatewayLog('ws.close', { sessionId: socketSessionId, wsUrl, attempts: reconnectAttemptsRef.current })
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
        reconnectTimerRef.current = null
      }
      wsRef.current?.close()
    }
  }, [activePage, socketSessionId])

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
        return 'Connecting'
      case 'connected':
        return 'Connected'
      case 'reconnecting':
        return 'Reconnecting'
      case 'disconnected':
        return 'Disconnected'
      default:
        return 'Idle'
    }
  }

  const waitForSocketReady = useCallback(
    (targetSessionId: string, timeoutMs = 8000) =>
      new Promise<void>((resolve, reject) => {
        const start = Date.now()

        const check = () => {
          const socket = wsRef.current
          const isReady =
            socket?.readyState === WebSocket.OPEN &&
            activeSocketSessionIdRef.current === targetSessionId

          if (isReady) {
            resolve()
            return
          }

          if (Date.now() - start >= timeoutMs) {
            reject(new Error('WebSocket connection timeout'))
            return
          }

          window.setTimeout(check, 120)
        }

        check()
      }),
    []
  )

  const disconnectSocket = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
    }
    reconnectAttemptsRef.current = 0
    activeSocketSessionIdRef.current = null
    pendingRequestsRef.current.forEach(({ reject }) => reject(new Error('WebSocket disconnected')))
    pendingRequestsRef.current.clear()
    wsRef.current?.close()
    wsRef.current = null
    setSocketSessionId('')
    setSocketState('idle')
  }, [])

  const displayExecutionStatus = (value: string) => {
    switch ((value || '').toLowerCase()) {
      case 'running':
        return 'Running'
      case 'completed':
        return 'Completed'
      case 'failed':
        return 'Failed'
      case 'waiting_user':
        return '绛夊緟鐢ㄦ埛'
      case 'waiting_tool':
        return '绛夊緟宸ュ叿'
      case 'suspended':
        return 'Suspended'
      case 'switch_required':
        return 'Switch required'
      case 'confirmation_required':
        return '绛夊緟纭'
      case 'permission_denied':
        return '鏉冮檺鎷掔粷'
      case 'rate_limited':
        return 'Rate limited'
      case 'degraded':
        return 'Degraded'
      default:
        return 'Idle'
    }
  }

  const displaySessionStatus = (value?: string | null) => {
    switch ((value || '').toLowerCase()) {
      case 'active':
        return 'Active'
      case 'closed':
        return 'Closed'
      default:
        return value || 'Unknown'
    }
  }

  const displayUserLabel = (value: string) => {
    switch (value) {
      case 'demo-admin':
        return 'Demo Admin'
      case 'anonymous':
        return '鍖垮悕鐢ㄦ埛'
      default:
        return '婕旂ず鐢ㄦ埛'
    }
  }

  const refreshExecutions = async (activeSessionId: string) => {
    try {
      const latest = await getSessionExecutions(activeSessionId)
      setExecutions(latest)
      if (latest[0]) {
        setExecutionStatus(normalizeStatus(latest[0].status))
        setExecutionId(latest[0].execution_id)
      } else {
        setExecutionStatus('idle')
        setExecutionId(null)
      }
    } catch (error) {
      console.error('鑾峰彇 execution 鍒楄〃澶辫触:', error)
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

  const pushGovernanceNotice = (title: string, detail: string) => {
    appendSystemMessage(`${title}: ${detail}`)
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
    gatewayLog('ack', {
      request_id: payload.request_id,
      action: payload.action,
      status: payload.status,
    })
    const pending = pendingRequestsRef.current.get(payload.request_id)
    if (!pending) return
    pendingRequestsRef.current.delete(payload.request_id)
    pending.resolve((payload.data as Record<string, unknown> | undefined) ?? undefined)
  }

  const handleGatewayError = (payload: GatewayErrorEnvelope) => {
    gatewayLog('error', {
      request_id: payload.request_id,
      error_code: payload.error_code,
      message: payload.message,
    })
    if (payload.request_id) {
      const pending = pendingRequestsRef.current.get(payload.request_id)
      if (pending) {
        pendingRequestsRef.current.delete(payload.request_id)
        pending.reject(new Error(payload.message))
        return
      }
    }
    appendSystemMessage(`缃戝叧閿欒锛?{payload.message}`)
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
      void refreshSessionDetail(sessionId)
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
      appendSystemMessage('The current workflow was suspended and is switching to a new target workflow.')
    }

    if (payload.event_type === 'execution.resume_offered') {
      setResumeOffer({
        executionId: payload.execution_id,
        workflowCode: String((data as any).workflow_code || ''),
        workflowVersion: String((data as any).workflow_version || ''),
        currentNodeId: (data as any).current_node_id ? String((data as any).current_node_id) : undefined,
      })
      appendSystemMessage(`Workflow ${String((data as any).workflow_code || payload.execution_id)} can be resumed. Continue?`)
    }

    if (payload.event_type === 'security.prompt_sanitized') {
      appendSystemMessage('A high-risk prompt fragment was detected and sanitized before continuing.')
    }

    if (payload.event_type === 'security.output_rejected') {
      appendSystemMessage('Model output did not pass validation and was blocked.')
    }

    if (payload.event_type === 'budget.alert') {
      appendSystemMessage(`Budget alert: ${String((data as any).message || 'Cost reached the alert threshold')}`)
    }

    if (payload.event_type === 'protection.degraded') {
      const detail = String((data as any).reason || '渚濊禆鏈嶅姟杩涘叆闄嶇骇鎵ц')
      pushGovernanceNotice('Runtime degraded', detail)
      appendSystemMessage(`The system is running in degraded mode: ${detail}`)
    }

    if (payload.event_type === 'protection.circuit_open') {
      const detail = `tool=${String((data as any).tool_code || 'unknown')} 路 failures=${String((data as any).failures || 0)}`
      pushGovernanceNotice('渚濊禆鐔旀柇宸叉墦寮€', detail)
    }

    if (payload.event_type === 'protection.rate_limited') {
      const detail = String((data as any).scope || 'runtime')
      pushGovernanceNotice('Rate limit triggered', detail)
    }

    if (payload.event_type === 'workflow.validation_failed') {
      pushGovernanceNotice('Workflow validation failed', 'Please fix the configuration issues in the canvas first.')
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
    const requestId = createId('req')

    return new Promise((resolve, reject) => {
      pendingRequestsRef.current.set(requestId, { resolve, reject })
      if (targetSessionId && targetSessionId !== socketSessionId) {
        setSocketSessionId(targetSessionId)
      }

      void waitForSocketReady(targetSessionId)
        .then(() => {
          const socket = wsRef.current
          if (!socket || socket.readyState !== WebSocket.OPEN) {
            throw new Error('WebSocket not connected')
          }

          gatewayLog('send', {
            request_id: requestId,
            action,
            session_id: targetSessionId,
            payload,
          })
          socket.send(
            JSON.stringify({
              type: 'action',
              request_id: requestId,
              action,
              session_id: targetSessionId,
              payload,
            })
          )
        })
        .catch((error) => {
          pendingRequestsRef.current.delete(requestId)
          reject(error instanceof Error ? error : new Error('WebSocket not connected'))
        })
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
        const createdSession = await createAndSelectSession(currentUserId)
        activeSessionId = createdSession.id
      }

      const selectedPublishedWorkflow = publishedWorkflowOptions.find(
        (item) => item.workflowCode === selectedPublishedWorkflowCode
      )
      const boundWorkflowId = selectedPublishedWorkflow?.id
      const boundWorkflowCode = selectedPublishedWorkflow?.workflowCode
      const boundWorkflowVersion = selectedPublishedWorkflow?.currentVersion

      const response = (await sendGatewayAction('chat.send', {
        session_id: activeSessionId,
        message_id: messageId,
        content,
        attachments: [],
        user_id: currentUserId,
        confirm_switch: options?.confirmSwitch ?? false,
        requested_tool_code: options?.requestedToolCode ?? null,
        confirmation_id: options?.confirmationId ?? null,
        cancel_confirmation: options?.cancelConfirmation ?? false,
        workflow_id: boundWorkflowId ?? null,
        workflow_code: boundWorkflowCode,
        workflow_version: boundWorkflowVersion,
      }, activeSessionId)) as unknown as SendMessageResponse
      gatewayLog('send_message.response', {
        session_id: activeSessionId,
        execution_id: response.execution_id,
        status: response.status,
        workflow_code: response.workflow_code,
        workflow_version: response.workflow_version,
      })

      if (response.status === 'permission_denied') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus('permission_denied')
        setPendingConfirmation(null)
        pushGovernanceNotice('Permission denied', response.permission_reason || 'The current operation was blocked by policy.')
        appendSystemMessage(`Permission denied: ${response.permission_reason || 'The current user is not allowed to perform this action.'}`)
        return
      }

      if (response.status === 'confirmation_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingConfirmation({ content, messageId, response })
        setExecutionStatus('confirmation_required')
        pushGovernanceNotice(
          'Confirmation required',
          `${response.requested_tool_code || 'unknown'} requires confirmation before execution`
        )
        appendSystemMessage(`High-risk operation detected: ${response.requested_tool_code}. Please confirm before continuing.`)
        return
      }

      if (response.status === 'confirmation_cancelled') {
        setPendingConfirmation(null)
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus('idle')
        pushGovernanceNotice('Confirmation cancelled', 'The sensitive operation was cancelled and will not continue.')
        appendSystemMessage('The sensitive operation was cancelled.')
        return
      }

      if (response.status === 'rate_limited' || response.status === 'degraded') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus(response.status)
        setPendingConfirmation(null)
        const detail =
          response.degradation_message ||
          response.protection_reason ||
          `Please retry after ${response.retry_after_seconds || 0} seconds.`
        pushGovernanceNotice(response.status === 'rate_limited' ? 'Rate limited' : 'Degraded', detail)
        appendSystemMessage(detail)
        return
      }

      if (response.status === 'switch_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingSwitch({ content, messageId, response })
        setExecutionStatus('switch_required')
        appendSystemMessage(
          `A new intent will switch to workflow ${response.workflow_code}. Please confirm whether to save the current flow and switch.`
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
      await refreshSessionDetail(activeSessionId)
    } catch (error) {
      console.error('鍙戦€佹秷鎭け璐?', error)
      setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
      setMessages((prev) => [
        ...prev,
        {
          id: createId('err'),
          type: 'error',
          content: 'Sending the message failed. Please try again later.',
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
      const response = (await sendGatewayAction('execution.resume', {
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
      await refreshSessionDetail(sessionId)
    } catch (error) {
      console.error('鎭㈠ execution 澶辫触:', error)
      setExecutionStatus('failed')
    }
  }

  const handleCreateNewSession = useCallback(async () => {
    disconnectSocket()
    resetSessionView()
    setCurrentSession(null)
    setSessionId('')
    navigateToPage('chat')
    try {
      await createAndSelectSession(currentUserId)
    } catch (error) {
      console.error('閺傛澘缂撴导姘崇樈婢惰精瑙?', error)
    }
  }, [createAndSelectSession, currentUserId, disconnectSocket, resetSessionView])

  const handleSessionChange = useCallback((nextSessionId: string) => {
    const nextSession = sessions.find((session) => session.id === nextSessionId) ?? null
    disconnectSocket()
    setCurrentSession(nextSession)
    setSessionId(nextSessionId)
  }, [disconnectSocket, sessions])

  const navigateToPage = (page: PageKey) => {
    window.location.hash = page
    setActivePage(page)
  }

/*  const renderPromptCards = () => (
    <>
      {pendingSwitch && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">闇€瑕佸垏鎹㈡祦绋?/div>
          <div className="text-sm text-slate-700">
            褰撳墠杩愯娴佺▼涓庢柊鎰忓浘鍐茬獊锛岀洰鏍囨祦绋嬫槸 <strong>{pendingSwitch.response.workflow_code}</strong>銆?          </div>
          <div className="text-xs text-slate-500 mt-2">
            鍐崇瓥锛歿pendingSwitch.response.route_decision} 路 缃俊搴︼細{' '}
            {pendingSwitch.response.route_confidence?.toFixed(2) ?? '鏈煡'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setPendingSwitch(null)}>
              淇濇寔褰撳墠娴佺▼
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
              鏆傚瓨骞跺垏鎹?            </button>
	            </div>
            </div>
	          </div>
      )}

      {resumeOffer && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">鎭㈠鎻愮ず</div>
          <div className="text-sm text-slate-700">
            宸叉湁鎸傝捣娴佺▼ <strong>{resumeOffer.workflowCode}</strong> 寰呮仮澶嶃€?          </div>
          <div className="text-xs text-slate-500 mt-2">
            鐗堟湰锛歿resumeOffer.workflowVersion} 路 鑺傜偣锛歿resumeOffer.currentNodeId || '寰呭畾'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setResumeOffer(null)}>
              绋嶅悗鎭㈠
            </button>
            <button className="prompt-primary" onClick={() => void handleResume()}>
              绔嬪嵆鎭㈠
            </button>
          </div>
        </div>
      )}

      {pendingConfirmation && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">楂橀闄╀簩娆＄‘璁?/div>
          <div className="text-sm text-slate-700">
            鎿嶄綔 <strong>{pendingConfirmation.response.requested_tool_code}</strong> 闇€瑕佷簩娆＄‘璁ゅ悗鎵嶈兘缁х画銆?          </div>
          <div className="text-xs text-slate-500 mt-2">
            澶辨晥鏃堕棿锛歿pendingConfirmation.response.confirmation_expires_at || '5 鍒嗛挓鍐?}
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
              鍙栨秷鎿嶄綔
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
              纭缁х画
            </button>
          </div>
        </div>
      )}
    </>
  )

*/
  const renderPromptCards = () => (
    <>
      {pendingSwitch && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">Switch Required</div>
          <div className="text-sm text-slate-700">
            The current execution conflicts with the new intent. Target workflow:
            {' '}
            <strong>{pendingSwitch.response.workflow_code}</strong>
          </div>
          <div className="mt-2 text-xs text-slate-500">
            Decision: {pendingSwitch.response.route_decision || 'unknown'} 路 Confidence:{' '}
            {pendingSwitch.response.route_confidence?.toFixed(2) ?? 'unknown'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setPendingSwitch(null)}>
              Keep Current
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
              Save And Switch
            </button>
          </div>
        </div>
      )}

      {resumeOffer && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">Resume Execution</div>
          <div className="text-sm text-slate-700">
            Suspended workflow:
            {' '}
            <strong>{resumeOffer.workflowCode}</strong>
          </div>
          <div className="mt-2 text-xs text-slate-500">
            Version: {resumeOffer.workflowVersion} 路 Node: {resumeOffer.currentNodeId || 'pending'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setResumeOffer(null)}>
              Later
            </button>
            <button className="prompt-primary" onClick={() => void handleResume()}>
              Resume Now
            </button>
          </div>
        </div>
      )}

      {pendingConfirmation && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">Confirmation Required</div>
          <div className="text-sm text-slate-700">
            Tool{' '}
            <strong>{pendingConfirmation.response.requested_tool_code}</strong>{' '}
            requires explicit confirmation before execution.
          </div>
          <div className="mt-2 text-xs text-slate-500">
            Expires at: {pendingConfirmation.response.confirmation_expires_at || '5 minutes'}
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
              Cancel
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
              Confirm
            </button>
          </div>
        </div>
      )}
    </>
  )

  const renderPageContent = () => {
    if (activePage === 'workflow') {
      return (
        <section className="page-grid page-grid-orchestrator">
          <div className="page-stack">
            <Orchestrator
              ref={orchestratorRef}
              currentUserId={currentUserId}
              editorSelection={workflowEditorSelection}
              onWorkflowDraftChange={setWorkflowDraft}
              onWorkflowSidebarStateChange={setWorkflowSidebarState}
              onWorkflowVersionMutation={setWorkflowVersionMutation}
            />
          </div>
          <div className="page-stack">
            <div className="panel-card">
              <div className="panel-header">
                <div>
                  <div className="panel-title">娴佺▼璁剧疆</div>
                  <div className="text-xs text-slate-500">Save, publish, and design checks are all handled on the right side.</div>
                </div>
                {workflowSidebarState?.workflowId && (
                  <div className="rounded-full border border-slate-200 bg-white px-3 py-1 text-xs text-slate-500">
                    ID {workflowSidebarState.workflowId}
                  </div>
                )}
              </div>
              <div className="panel-body space-y-3">
                <input
                  value={workflowNameInput}
                  onChange={(event) => {
                    const nextValue = event.target.value
                    setWorkflowNameInput(nextValue)
                    orchestratorRef.current?.setWorkflowName(nextValue)
                  }}
                  className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm"
                  placeholder="???"
                />
                <div className="grid gap-2 sm:grid-cols-3">
                  <button className="prompt-secondary" type="button" onClick={() => void orchestratorRef.current?.validateDraft()}>
                    鏍￠獙
                  </button>
                  <button
                    className="prompt-secondary"
                    type="button"
                    onClick={() => void orchestratorRef.current?.saveDraft()}
                    disabled={workflowSidebarState?.isSaving}
                  >
                    {workflowSidebarState?.isSaving ? '淇濆瓨涓?..' : '淇濆瓨鑽夌'}
                  </button>
                  <button
                    className="prompt-primary"
                    type="button"
                    onClick={() => void orchestratorRef.current?.publish()}
                    disabled={workflowSidebarState?.isPublishing}
                  >
                    {workflowSidebarState?.isPublishing ? '鍙戝竷涓?..' : '鍙戝竷鐗堟湰'}
                  </button>
                </div>
                <div className="rounded-xl border border-slate-200 bg-white p-3 text-xs text-slate-500">
                  <div>鑽夌鐗堟湰: {workflowSidebarState?.draftVersion || 'draft'}</div>
                  <div>Current code: {workflowSidebarState?.workflowCode || 'Generated after save'}</div>
                  <div>Latest release: {workflowSidebarState?.publishedVersion || 'Not published yet'}</div>
                  <div className="mt-2 text-slate-400">{workflowSidebarState?.saveStatus || '灏氭湭淇濆瓨'}</div>
                </div>
                <div className="rounded-xl border border-slate-200 bg-white p-3">
                  <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">Design Checks</div>
                  <ul className="space-y-1">
                    {(workflowSidebarState?.summaryRules || []).map((rule) => (
                      <li key={rule.label} className={`text-sm ${rule.valid ? 'text-emerald-600' : 'text-amber-600'}`}>
                        {rule.valid ? 'Pass' : 'Needs work'} / {rule.label}
                      </li>
                    ))}
                  </ul>
                  {(workflowSidebarState?.validationIssues?.length || 0) > 0 && (
                    <div className="mt-3 space-y-2 rounded-xl border border-amber-200 bg-amber-50 p-3">
                      {workflowSidebarState?.validationIssues.map((issue, index) => (
                        <div key={`${issue.field}_${index}`} className="text-xs text-amber-700">
                          {issue.node_id ? `${issue.node_id} / ` : ''}
                          {issue.field} / {issue.message}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>
            <WorkflowPanel
              currentUserId={currentUserId}
              workflowCode={workflowSidebarState?.workflowCode || workflowDraft?.workflowCode}
              refreshSignal={workflowVersionMutation}
              onWorkflowVersionMutation={setWorkflowVersionMutation}
              onEditVersion={(selection) => {
                setWorkflowEditorSelection({ ...selection, version: { ...selection.version } })
              }}
            />
          </div>
        </section>
      )
    }

    if (activePage === 'execution') {
      return (
        <section className="page-grid page-grid-balanced">
          <div className="page-stack">
            <ReplayPanel executionId={executionId} />
          </div>
          <div className="page-stack">
            <ExecutionPanel
              events={events}
              executions={executions}
              status={executionStatus}
              executionId={executionId}
              socketState={socketState}
            />
          </div>
        </section>
      )
    }

    if (activePage === 'models') {
      return (
        <section className="page-single">
          <ModelConfigPanel currentUserId={currentUserId} />
        </section>
      )
    }

    return (
      <section className="page-grid page-grid-chat">
        <div className="page-stack">
        {renderPromptCards()}
        <div className="panel-card flex-1 flex flex-col">
          <div className="panel-header">
            <div>
              <div className="panel-title">瀵硅瘽绐楀彛</div>
              <div className="mt-1 text-xs text-slate-500">
                鎵ц鐘舵€侊細{displayExecutionStatus(executionStatus)}
              </div>
            </div>
            <div className="flex items-start gap-3">
              <button
                className="prompt-secondary"
                onClick={() => void handleCreateNewSession()}
                type="button"
                style={{ fontSize: 0 }}
                data-testid="chat-new-session"
              >
                <span className="text-xs">????</span>
                ????
              </button>
	              <div className="text-right text-xs text-slate-500" data-testid="current-session-meta">
              <div>??ID?{sessionId || '????'}</div>
              <div>
                ?????{displaySessionStatus(currentSession?.status)} ? ???{displaySocketState(socketState)}
              </div>
            </div>
	          </div>
          </div>
	          <div className="mb-4 rounded-2xl border border-sky-100 bg-[linear-gradient(135deg,rgba(240,249,255,0.95),rgba(255,255,255,0.98))] px-4 py-4">
            <div className="text-sm font-semibold text-slate-800">??????????</div>
            <div className="mt-1 text-sm text-slate-600">
              鍙厛閫夋嫨涓€涓凡鍙戝竷娴佺▼杩涜瀹氬悜娴嬭瘯锛涗笉閫夋嫨鏃讹紝绯荤粺浼氭牴鎹秷鎭唴瀹硅嚜鍔ㄨ矾鐢便€?            </div>
            <div className="mt-3 grid gap-2 md:grid-cols-[minmax(0,1fr)_auto]">
              <select
                value={selectedPublishedWorkflowCode}
                onChange={(event) => setSelectedPublishedWorkflowCode(event.target.value)}
                className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700"
              >
                <option value="">?????</option>
                {publishedWorkflowOptions.map((workflow) => (
                  <option key={workflow.workflowCode} value={workflow.workflowCode}>
                    {workflow.name} ({workflow.workflowCode} ? {workflow.currentVersion || 'Unknown version'})
                  </option>
                ))}
              </select>
              <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500">
                ?????{' '}
                {selectedPublishedWorkflowCode
                  ? `${selectedPublishedWorkflowCode} / ${publishedWorkflowOptions.find((item) => item.workflowCode === selectedPublishedWorkflowCode)?.currentVersion || 'Unknown version'}`
                  : '?????'}
              </div>
            </div>
          </div>
          <MessageList messages={messages} isLoading={isLoading} />
          <div className="panel-footer">
            <div className="mb-2 text-xs text-slate-400">
              ?????{displayUserLabel(currentUserId)} ? ???????{' '}
              {selectedPublishedWorkflowCode || '?????'} ? ???????? / ????
            </div>
            <ChatInput onSendMessage={(content) => void handleSendMessage(content)} isLoading={isLoading} />
          </div>
        </div>
        </div>
        <div className="page-stack">
          <SessionReplayPanel
            currentUserId={currentUserId}
            activeSessionId={sessionId}
            connectedSessionId={socketState === 'connected' ? sessionId : ''}
            currentMessages={messages}
            currentExecutions={executions}
          />
        </div>
      </section>
    )
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">?????</h1>
          <div className="text-sm text-slate-500">????????</div>
        </div>
        <div className="flex items-center gap-3 text-xs text-slate-400">
          <nav className="nav-tabs">
            <button className={`nav-tab ${activePage === 'chat' ? 'active' : ''}`} onClick={() => navigateToPage('chat')}>
              ??
            </button>
            <button className={`nav-tab ${activePage === 'workflow' ? 'active' : ''}`} onClick={() => navigateToPage('workflow')}>
              ????
            </button>
            <button className={`nav-tab ${activePage === 'execution' ? 'active' : ''}`} onClick={() => navigateToPage('execution')}>
              ????
            </button>
            <button className={`nav-tab ${activePage === 'models' ? 'active' : ''}`} onClick={() => navigateToPage('models')}>
              ?? Profile
            </button>
          </nav>
          <label className="flex items-center gap-2">
            <span>??</span>
            <select
              value={currentUserId}
              onChange={(event) => {
                setCurrentUserId(event.target.value)
              }}
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            >
              <option value="demo-user">????</option>
              <option value="demo-admin">?????</option>
              <option value="anonymous">????</option>
            </select>
          </label>
          <label className="flex items-center gap-2">
            <span>??</span>
            <select
              value={sessionId}
              onChange={(event) => {
                handleSessionChange(event.target.value)
              }}
              className="max-w-[220px] rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            >
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id.slice(0, 8)} ? {displaySessionStatus(session.status)}
                </option>
              ))}
            </select>
          </label>
          <button
            className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            onClick={() => void handleCreateNewSession()}
            type="button"
          >
            ????
          </button>
        </div>
      </header>

      <main className="page-shell">
        {renderPageContent()}
      </main>

      {pendingForm && (
        <FormDialog
          form={pendingForm.form}
          onClose={() => setPendingForm(null)}
          onSubmit={async (data) => {
            try {
              await sendGatewayAction('form.submit', {
                execution_id: pendingForm.executionId,
                submit_id: createId('submit'),
                form_data: data,
              })
              setPendingForm(null)
              setExecutionStatus('running')
              setIsLoading(true)
              await refreshExecutions(sessionId)
              await refreshSessionDetail(sessionId)
            } catch (error) {
              console.error('Form submission failed:', error)
              setExecutionStatus('failed')
            }
          }}
        />
      )}
    </div>
  )
}

export default App
