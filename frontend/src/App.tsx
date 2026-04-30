import React, { useCallback, useEffect, useRef, useState } from 'react'
import ChatInput from './components/ChatInput'
import CapabilityCenterPanel from './components/CapabilityCenterPanel'
import ExecutionPanel from './components/ExecutionPanel'
import FormDialog from './components/FormDialog'
import MessageList from './components/MessageList'
import ModelConfigPanel from './components/ModelConfigPanel'
import Orchestrator from './components/Orchestrator'
import ReplayPanel from './components/ReplayPanel'
import SessionReplayPanel from './components/SessionReplayPanel'
import {
  createSession,
  deleteSession,
  deleteWorkflow,
  getPublishedWorkflows,
  getSession,
  getSessionExecutions,
  getSessionMessages,
  getSessionsByUserId,
  getWorkflowVersions,
} from './services/api'
import { displayExecutionStatus, displaySessionStatus, displaySocketState, displayUserLabel } from './utils/displayText'
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
import type { WorkflowVersionMutation } from './components/Orchestrator'

interface ChatWorkflowBinding {
  workflowCode: string
  workflowVersion: string
}

type PageKey = 'chat' | 'workflow' | 'execution' | 'models' | 'capability-center'
type WorkflowPageMode = 'list' | 'editor'

const WORKFLOW_LIST_PAGE_SIZE = 10

const isDisplayableWorkflow = (workflow: WorkflowSummary) => {
  if (workflow.createdBy === 'system') return false
  const workflowCode = workflow.workflowCode || ''
  if (workflowCode === 'cap_workflow' || workflowCode.startsWith('cap_workflow_')) return false
  if (workflowCode === 'workflow_1776609829026' || workflowCode === 'workflow_1777206095089') return false
  return true
}

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
  content: '欢迎使用。你可以直接开始对话，或选择已发布工作流进行定向测试。',
  timestamp: new Date().toISOString(),
})

const normalizeSessionMessages = (items: Message[]): Message[] =>
  items.filter((message) => message.id !== 'welcome')

const hasUserMessage = (items: Message[]): boolean =>
  items.some((message) => message.type === 'user' && message.content.trim().length > 0)

const dedupeSessions = (items: SessionSummary[]): SessionSummary[] =>
  Array.from(
    items.reduce((sessionsById, session) => {
      if (!sessionsById.has(session.id)) {
        sessionsById.set(session.id, session)
      }
      return sessionsById
    }, new Map<string, SessionSummary>()).values()
  )

const App: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([createWelcomeMessage()])
  const [isLoading, setIsLoading] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [currentSession, setCurrentSession] = useState<SessionSummary | null>(null)
  const [currentUserId, setCurrentUserId] = useState('demo-user')
  const [sessionMessagesById, setSessionMessagesById] = useState<Record<string, Message[]>>({})
  const [persistedSessionIds, setPersistedSessionIds] = useState<string[]>([])
  const [selectedHistorySessionId, setSelectedHistorySessionId] = useState('')
  const [selectedHistoryMessages, setSelectedHistoryMessages] = useState<Message[]>([])
  const [isLoadingSessionHistory, setIsLoadingSessionHistory] = useState(false)
  const [isLoadingSelectedHistory, setIsLoadingSelectedHistory] = useState(false)
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
  const [publishedWorkflowOptions, setPublishedWorkflowOptions] = useState<WorkflowSummary[]>([])
  const [selectedPublishedWorkflowCode, setSelectedPublishedWorkflowCode] = useState('')
  const [chatWorkflowBinding, setChatWorkflowBinding] = useState<ChatWorkflowBinding | null>(null)
  const [workflowVersionMutation, setWorkflowVersionMutation] = useState<WorkflowVersionMutation | null>(null)
  const [workflowEditorSelection, setWorkflowEditorSelection] = useState<WorkflowEditorSelection | null>(null)
  const [workflowEditorInstance, setWorkflowEditorInstance] = useState(0)
  const [workflowPageMode, setWorkflowPageMode] = useState<WorkflowPageMode>('list')
  const [workflowListPage, setWorkflowListPage] = useState(1)
  const [workflowListStatus, setWorkflowListStatus] = useState('')
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
      const filteredItems = items.filter(isDisplayableWorkflow)
      setPublishedWorkflowOptions(filteredItems)
      setSelectedPublishedWorkflowCode((current) =>
        filteredItems.some((item) => item.workflowCode === current) ? current : ''
      )
      setChatWorkflowBinding((current) => {
        if (!current) return null
        return filteredItems.some((item) => item.workflowCode === current.workflowCode) ? current : null
      })
    } catch (error) {
      console.error('Failed to load published workflow options:', error)
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

  const cacheSessionMessages = useCallback((targetSessionId: string, nextMessages: Message[]) => {
    if (!targetSessionId) return
    setSessionMessagesById((prev) => ({
      ...prev,
      [targetSessionId]: nextMessages,
    }))
  }, [])

  const removeCachedSessionMessages = useCallback((targetSessionId: string) => {
    setSessionMessagesById((prev) => {
      if (!(targetSessionId in prev)) return prev
      const next = { ...prev }
      delete next[targetSessionId]
      return next
    })
  }, [])

  const markSessionPersisted = useCallback((targetSessionId: string) => {
    if (!targetSessionId) return
    setPersistedSessionIds((prev) => (prev.includes(targetSessionId) ? prev : [...prev, targetSessionId]))
  }, [])

  const removePersistedSession = useCallback((targetSessionId: string) => {
    if (!targetSessionId) return
    setPersistedSessionIds((prev) => prev.filter((sessionId) => sessionId !== targetSessionId))
  }, [])

  const loadRetainedHistory = useCallback(async (userId: string, activeSession: SessionSummary) => {
    setIsLoadingSessionHistory(true)
    try {
      const loadedSessions = dedupeSessions(await getSessionsByUserId(userId)).filter(
        (session) => session.id !== activeSession.id
      )
      const loadedHistory = await Promise.all(
        loadedSessions.map(async (session) => {
          try {
            const history = normalizeSessionMessages(await getSessionMessages(session.id))
            return { session, history }
          } catch (error) {
            console.error('Failed to load session history messages:', error)
            return { session, history: [] as Message[] }
          }
        })
      )

      cacheSessionMessages(activeSession.id, [])
      setSessionMessagesById((prev) => ({
        ...prev,
        ...Object.fromEntries(loadedHistory.map(({ session, history }) => [session.id, history])),
      }))
      const retainedHistory = loadedHistory.filter(({ history }) => hasUserMessage(history))
      setPersistedSessionIds(retainedHistory.map(({ session }) => session.id))
      setSessions([activeSession, ...retainedHistory.map(({ session }) => session)])
    } catch (error) {
      console.error('Failed to load session history:', error)
      setPersistedSessionIds([])
      setSessions([activeSession])
    } finally {
      setIsLoadingSessionHistory(false)
    }
  }, [cacheSessionMessages])

  const refreshSessionDetail = useCallback(async (activeSessionId: string) => {
    try {
      const detail = await getSession(activeSessionId)
      setCurrentSession(detail)
      setSessions((prev) => [detail, ...prev.filter((session) => session.id !== detail.id)])
    } catch (error) {
      console.error('Failed to refresh session detail:', error)
    }
  }, [])

  const loadSessionMessages = useCallback(async (activeSessionId: string) => {
    try {
      const history = await getSessionMessages(activeSessionId)
      const normalizedHistory = normalizeSessionMessages(history)
      cacheSessionMessages(activeSessionId, normalizedHistory)
      if (hasUserMessage(normalizedHistory)) {
        markSessionPersisted(activeSessionId)
      } else {
        removePersistedSession(activeSessionId)
      }
      setMessages(normalizedHistory.length > 0 ? history : [createWelcomeMessage()])
    } catch (error) {
      console.error('Failed to load session messages:', error)
      cacheSessionMessages(activeSessionId, [])
      removePersistedSession(activeSessionId)
      setMessages([createWelcomeMessage()])
    }
  }, [cacheSessionMessages, markSessionPersisted, removePersistedSession])

  const createAndSelectSession = useCallback(async (userId: string) => {
    const created = await createSession({ userId })
    cacheSessionMessages(created.id, [])
    setCurrentSession(created)
    setSessionId(created.id)
    setSelectedHistorySessionId(created.id)
    return created
  }, [cacheSessionMessages])

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
      setSessions([])
      setSessionMessagesById({})
      setPersistedSessionIds([])
      setSelectedHistorySessionId('')
      setSelectedHistoryMessages([])
      try {
        const created = await createAndSelectSession(currentUserId)
        if (cancelled) return
        await loadRetainedHistory(currentUserId, created)
      } catch (error) {
        if (!cancelled) {
          console.error('Failed to initialize session:', error)
        }
      }
    }

    void initializeSession()

    return () => {
      cancelled = true
    }
  }, [createAndSelectSession, currentUserId, loadRetainedHistory, resetSessionView])

  useEffect(() => {
    if (!workflowVersionMutation) return
    void loadPublishedWorkflowOptions()
  }, [loadPublishedWorkflowOptions, workflowVersionMutation])

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(publishedWorkflowOptions.length / WORKFLOW_LIST_PAGE_SIZE))
    setWorkflowListPage((current) => Math.min(Math.max(current, 1), totalPages))
  }, [publishedWorkflowOptions.length])

  useEffect(() => {
    if (!sessionId) return
    resetSessionView()
    void loadSessionMessages(sessionId)
    void refreshSessionDetail(sessionId)
  }, [loadSessionMessages, refreshSessionDetail, resetSessionView, sessionId])

  useEffect(() => {
    if (!sessionId) return
    setSelectedHistorySessionId((current) => current || sessionId)
  }, [sessionId])

  useEffect(() => {
    setSelectedHistorySessionId((current) => {
      if (current && sessions.some((session) => session.id === current)) {
        return current
      }
      return sessionId || sessions[0]?.id || ''
    })
  }, [sessionId, sessions])

  useEffect(() => {
    if (!sessionId) return
    cacheSessionMessages(sessionId, normalizeSessionMessages(messages))
  }, [cacheSessionMessages, messages, sessionId])

  useEffect(() => {
    let cancelled = false

    const loadSelectedHistoryMessages = async () => {
      if (!selectedHistorySessionId) {
        setSelectedHistoryMessages([])
        setIsLoadingSelectedHistory(false)
        return
      }

      if (selectedHistorySessionId === sessionId) {
        setSelectedHistoryMessages(messages)
        setIsLoadingSelectedHistory(false)
        return
      }

      const cachedMessages = sessionMessagesById[selectedHistorySessionId]
      if (cachedMessages) {
        setSelectedHistoryMessages(cachedMessages)
        setIsLoadingSelectedHistory(false)
        return
      }

      setIsLoadingSelectedHistory(true)
      try {
        const history = normalizeSessionMessages(await getSessionMessages(selectedHistorySessionId))
        if (!cancelled) {
          cacheSessionMessages(selectedHistorySessionId, history)
          setSelectedHistoryMessages(history)
        }
      } catch (error) {
        if (!cancelled) {
          console.error('Failed to load selected history messages:', error)
          setSelectedHistoryMessages([])
        }
      } finally {
        if (!cancelled) {
          setIsLoadingSelectedHistory(false)
        }
      }
    }

    void loadSelectedHistoryMessages()

    return () => {
      cancelled = true
    }
  }, [cacheSessionMessages, messages, selectedHistorySessionId, sessionId, sessionMessagesById])

  useEffect(() => {
    const syncPageFromHash = () => {
      const value = window.location.hash.replace('#', '')
      if (value === 'workflow' || value === 'execution' || value === 'models' || value === 'chat' || value === 'capability-center') {
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
      console.error('Failed to refresh execution list:', error)
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
      appendSystemMessage('当前工作流已暂停，准备切换到新的目标工作流。')
    }

    if (payload.event_type === 'execution.resume_offered') {
      setResumeOffer({
        executionId: payload.execution_id,
        workflowCode: String((data as any).workflow_code || ''),
        workflowVersion: String((data as any).workflow_version || ''),
        currentNodeId: (data as any).current_node_id ? String((data as any).current_node_id) : undefined,
      })
      appendSystemMessage(`工作流 ${String((data as any).workflow_code || payload.execution_id)} 可以恢复执行，是否继续？`)
    }

    if (payload.event_type === 'security.prompt_sanitized') {
      appendSystemMessage('检测到高风险提示片段，系统已在继续前完成清洗。')
    }

    if (payload.event_type === 'security.output_rejected') {
      appendSystemMessage('模型输出未通过校验，已被阻止展示。')
    }

    if (payload.event_type === 'budget.alert') {
      appendSystemMessage(`预算预警：${String((data as any).message || '成本已达到预警阈值')}`)
    }

    if (payload.event_type === 'protection.degraded') {
      const detail = String((data as any).reason || '运行时已进入降级模式。')
      pushGovernanceNotice('运行已降级', detail)
      appendSystemMessage(`系统当前以降级模式运行：${detail}`)
    }

    if (payload.event_type === 'protection.circuit_open') {
      const detail = `工具=${String((data as any).tool_code || '未知')} / 失败次数=${String((data as any).failures || 0)}`
      pushGovernanceNotice('保护熔断已开启', detail)
    }
    if (payload.event_type === 'protection.rate_limited') {
      const detail = String((data as any).scope || '运行时')
      pushGovernanceNotice('触发限流', detail)
    }

    if (payload.event_type === 'workflow.validation_failed') {
      pushGovernanceNotice('工作流校验失败', '请先修复画布中的配置问题。')
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
    const baselineMessages = normalizeSessionMessages(messages)
    let appendedUserMessage: Message | null = null
    let activeSessionId = sessionId

    if (shouldAppendUserMessage) {
      const userMessage: Message = {
        id: createId('msg'),
        type: 'user',
        content,
        timestamp: new Date().toISOString(),
      }
      appendedUserMessage = userMessage
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
      if (!activeSessionId) {
        const createdSession = await createAndSelectSession(currentUserId)
        activeSessionId = createdSession.id
      }

      if (shouldAppendUserMessage && appendedUserMessage) {
        const nextHistory = [...baselineMessages, appendedUserMessage]
        cacheSessionMessages(activeSessionId, nextHistory)
      }

      const selectedPublishedWorkflow = publishedWorkflowOptions.find(
        (item) => item.workflowCode === selectedPublishedWorkflowCode
      )
      const linkedBinding =
        chatWorkflowBinding && chatWorkflowBinding.workflowCode === selectedPublishedWorkflowCode
          ? chatWorkflowBinding
          : null
      const boundWorkflowId = selectedPublishedWorkflow?.id
      const boundWorkflowCode = selectedPublishedWorkflow?.workflowCode || linkedBinding?.workflowCode
      const boundWorkflowVersion = linkedBinding?.workflowVersion || selectedPublishedWorkflow?.currentVersion

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
        pushGovernanceNotice('权限不足', response.permission_reason || '当前操作被策略阻止。')
        appendSystemMessage(`权限不足：${response.permission_reason || '当前用户无权执行此操作。'}`)
        return
      }

      if (response.status === 'confirmation_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingConfirmation({ content, messageId, response })
        setExecutionStatus('confirmation_required')
        pushGovernanceNotice(
          '需要确认',
          `${response.requested_tool_code || '未知工具'} 在执行前需要确认`
        )
        appendSystemMessage(`检测到高风险操作：${response.requested_tool_code}。请确认后继续。`)
        return
      }

      if (response.status === 'confirmation_cancelled') {
        setPendingConfirmation(null)
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setExecutionStatus('idle')
        pushGovernanceNotice('已取消确认', '敏感操作已取消，流程不会继续执行。')
        appendSystemMessage('敏感操作已取消。')
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
        pushGovernanceNotice(response.status === 'rate_limited' ? '已限流' : '已降级', detail)
        appendSystemMessage(detail)
        return
      }

      if (response.status === 'switch_required') {
        setMessages((prev) => prev.filter((message) => message.id !== pendingMessageId))
        setPendingSwitch({ content, messageId, response })
        setExecutionStatus('switch_required')
        appendSystemMessage(
          `检测到新的意图，将切换到工作流 ${response.workflow_code}。请确认是否保存当前流程并切换。`
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
      markSessionPersisted(activeSessionId)
      await refreshExecutions(activeSessionId)
      await refreshSessionDetail(activeSessionId)
    } catch (error) {
      console.error('Failed to send message:', error)
      setMessages((prev) =>
        prev.filter((message) => message.id !== pendingMessageId)
      )
      if (activeSessionId) {
        cacheSessionMessages(activeSessionId, baselineMessages)
      }
      setMessages((prev) => [
        ...prev,
        {
          id: createId('err'),
          type: 'error',
          content: '消息发送失败，请稍后再试。',
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
      console.error('Failed to resume execution:', error)
      setExecutionStatus('failed')
    }
  }

  const handleCreateNewSession = useCallback(async () => {
    const previousSession = currentSession
    const previousSessionId = sessionId
    const previousMessages = normalizeSessionMessages(messages)
    const shouldRetainPrevious = Boolean(
      previousSession && previousSessionId && persistedSessionIds.includes(previousSessionId)
    )

    disconnectSocket()
    resetSessionView()
    setCurrentSession(null)
    setSessionId('')
    navigateToPage('chat')
    try {
      const created = await createAndSelectSession(currentUserId)
      setSessions((prev) => {
        const retainedSessions = prev.filter(
          (session) => session.id !== created.id && session.id !== previousSessionId
        )
        return shouldRetainPrevious && previousSession
          ? [
              created,
              {
                ...previousSession,
                lastActivityAt:
                  previousMessages[previousMessages.length - 1]?.timestamp ?? previousSession.lastActivityAt,
              },
              ...retainedSessions,
            ]
          : [created, ...retainedSessions]
      })
      if (!shouldRetainPrevious && previousSessionId) {
        removeCachedSessionMessages(previousSessionId)
      }
    } catch (error) {
      console.error('Failed to create session:', error)
    }
  }, [createAndSelectSession, currentSession, currentUserId, disconnectSocket, messages, persistedSessionIds, removeCachedSessionMessages, resetSessionView, sessionId])

  const handleSessionChange = useCallback((nextSessionId: string) => {
    const nextSession = sessions.find((session) => session.id === nextSessionId) ?? null
    disconnectSocket()
    setCurrentSession(nextSession)
    setSessionId(nextSessionId)
    setSelectedHistorySessionId(nextSessionId)
  }, [disconnectSocket, sessions])

  const handleDeleteSession = useCallback(async (targetSessionId: string) => {
    const isDeletingCurrent = targetSessionId === sessionId
    const remainingSessions = sessions.filter((session) => session.id !== targetSessionId)

    try {
      await deleteSession(targetSessionId)
      removeCachedSessionMessages(targetSessionId)
      removePersistedSession(targetSessionId)
      setSessions(remainingSessions)

      if (!isDeletingCurrent) {
        setSelectedHistorySessionId((current) =>
          current === targetSessionId ? sessionId || remainingSessions[0]?.id || '' : current
        )
        return
      }

      disconnectSocket()
      resetSessionView()
      setCurrentSession(null)
      setSessionId('')
      navigateToPage('chat')

      const created = await createAndSelectSession(currentUserId)
      setSessions([created, ...remainingSessions])
    } catch (error) {
      console.error('Failed to delete session:', error)
    }
  }, [createAndSelectSession, currentUserId, disconnectSocket, removeCachedSessionMessages, removePersistedSession, resetSessionView, sessionId, sessions])

  const navigateToPage = (page: PageKey) => {
    window.location.hash = page
    setActivePage(page)
  }

  const handleChatWorkflowSelect = (workflowCode: string) => {
    setSelectedPublishedWorkflowCode(workflowCode)
    if (!workflowCode) {
      setChatWorkflowBinding(null)
      return
    }
    const selected = publishedWorkflowOptions.find((item) => item.workflowCode === workflowCode)
    if (selected?.currentVersion) {
      setChatWorkflowBinding({
        workflowCode: selected.workflowCode,
        workflowVersion: selected.currentVersion,
      })
      return
    }
    setChatWorkflowBinding(null)
  }

  const openNewWorkflowEditor = () => {
    setWorkflowEditorSelection(null)
    setWorkflowEditorInstance((current) => current + 1)
    setWorkflowPageMode('editor')
    setWorkflowListStatus('')
  }

  const openWorkflowList = () => {
    setWorkflowPageMode('list')
    setWorkflowEditorSelection(null)
    void loadPublishedWorkflowOptions()
  }

  const handleEditPublishedWorkflow = async (workflow: WorkflowSummary) => {
    if (!workflow.currentVersion) {
      setWorkflowListStatus('该工作流暂无当前发布版本，无法编辑。')
      return
    }

    setWorkflowListStatus('正在加载工作流版本...')
    try {
      const versions = await getWorkflowVersions(workflow.workflowCode)
      const selectedVersion = versions.find((item) => item.version === workflow.currentVersion) ?? versions[0]
      if (!selectedVersion) {
        setWorkflowListStatus('未找到可编辑的工作流版本。')
        return
      }
      setWorkflowEditorSelection({
        workflowCode: workflow.workflowCode,
        workflowName: workflow.name,
        publishedVersion: workflow.currentVersion,
        version: { ...selectedVersion },
      })
      setWorkflowEditorInstance((current) => current + 1)
      setWorkflowPageMode('editor')
      setWorkflowListStatus('')
    } catch (error) {
      setWorkflowListStatus(error instanceof Error ? `加载失败：${error.message}` : '加载失败。')
    }
  }

  const handleDeletePublishedWorkflow = async (workflow: WorkflowSummary) => {
    const confirmed = window.confirm(`确定删除工作流 ${workflow.name || workflow.workflowCode} 吗？删除后将不再出现在已发布列表。`)
    if (!confirmed) return

    setWorkflowListStatus('正在删除工作流...')
    try {
      await deleteWorkflow(workflow.workflowCode, currentUserId)
      setPublishedWorkflowOptions((current) => current.filter((item) => item.workflowCode !== workflow.workflowCode))
      setSelectedPublishedWorkflowCode((current) => (current === workflow.workflowCode ? '' : current))
      setChatWorkflowBinding((current) => (current?.workflowCode === workflow.workflowCode ? null : current))
      setWorkflowVersionMutation({
        workflowCode: workflow.workflowCode,
        version: workflow.currentVersion || '',
        action: 'delete',
        refreshAt: Date.now(),
      })
      setWorkflowListStatus('工作流已删除。')
    } catch (error) {
      setWorkflowListStatus(error instanceof Error ? `删除失败：${error.message}` : '删除失败。')
    }
  }

  const renderPromptCards = () => (
    <>
      {pendingSwitch && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">需要切换</div>
          <div className="text-sm text-slate-700">
            当前执行与新意图冲突，目标工作流：
            {' '}
            <strong>{pendingSwitch.response.workflow_code}</strong>
          </div>
          <div className="mt-2 text-xs text-slate-500">
            决策：{pendingSwitch.response.route_decision || '未知'} / 置信度：
            {' '}
            {pendingSwitch.response.route_confidence?.toFixed(2) ?? '未知'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setPendingSwitch(null)}>
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
              保存并切换
            </button>
          </div>
        </div>
      )}

      {resumeOffer && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">恢复执行</div>
          <div className="text-sm text-slate-700">
            已挂起工作流：
            {' '}
            <strong>{resumeOffer.workflowCode}</strong>
          </div>
          <div className="mt-2 text-xs text-slate-500">
            版本：{resumeOffer.workflowVersion} / 节点：{resumeOffer.currentNodeId || '待定'}
          </div>
          <div className="prompt-actions">
            <button className="prompt-secondary" onClick={() => setResumeOffer(null)}>
              稍后处理
            </button>
            <button className="prompt-primary" onClick={() => void handleResume()}>
              立即恢复
            </button>
          </div>
        </div>
      )}

      {pendingConfirmation && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">需要确认</div>
          <div className="text-sm text-slate-700">
            工具
            {' '}
            <strong>{pendingConfirmation.response.requested_tool_code}</strong>{' '}
            在执行前需要显式确认。
          </div>
          <div className="mt-2 text-xs text-slate-500">
            过期时间：{pendingConfirmation.response.confirmation_expires_at || '5 分钟后'}
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
              取消
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
              确认
            </button>
          </div>
        </div>
      )}
    </>
  )

  const renderPageContent = () => {
    if (activePage === 'workflow') {
      const workflowTotalPages = Math.max(1, Math.ceil(publishedWorkflowOptions.length / WORKFLOW_LIST_PAGE_SIZE))
      const normalizedWorkflowListPage = Math.min(workflowListPage, workflowTotalPages)
      const workflowListStart = (normalizedWorkflowListPage - 1) * WORKFLOW_LIST_PAGE_SIZE
      const visibleWorkflows = publishedWorkflowOptions.slice(
        workflowListStart,
        workflowListStart + WORKFLOW_LIST_PAGE_SIZE
      )

      if (workflowPageMode === 'list') {
        return (
          <section className="grid min-h-0 flex-1" data-testid="workflow-list-page">
            <div className="panel-card flex min-h-0 flex-col">
              <div className="panel-header">
                <div>
                  <div className="panel-title">工作流列表</div>
                  <div className="text-xs text-slate-500">仅展示已发布工作流。点击新增或编辑后进入工作流设计页面。</div>
                </div>
                <button
                  className="prompt-primary"
                  type="button"
                  onClick={openNewWorkflowEditor}
                  data-testid="workflow-new-version"
                >
                  新增工作流版本
                </button>
              </div>

              {workflowListStatus && (
                <div className="mb-4 rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
                  {workflowListStatus}
                </div>
              )}

              {visibleWorkflows.length === 0 ? (
                <div className="flex-1 rounded-2xl border border-dashed border-slate-200 bg-slate-50/70 p-6 text-sm text-slate-500">
                  暂无已发布工作流，请点击新增进入设计器。
                </div>
              ) : (
                <div className="flex-1 space-y-3 overflow-auto pr-1">
                  {visibleWorkflows.map((workflow) => (
                    <div
                      key={workflow.workflowCode}
                      className="rounded-2xl border border-slate-200 bg-white px-4 py-4"
                      data-testid="workflow-list-row"
                    >
                      <div
                        className="flex flex-wrap items-center justify-between gap-3"
                        data-testid={`workflow-list-row-${workflow.workflowCode}`}
                      >
                        <div className="min-w-0">
                          <div className="truncate text-base font-semibold text-slate-900">
                            {workflow.name || '未命名工作流'}
                          </div>
                          <div className="mt-1 text-sm text-slate-500">
                            {workflow.workflowCode} / 当前版本 {workflow.currentVersion || '未知版本'}
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <button
                            className="rounded-md border border-sky-200 px-3 py-1.5 text-sm text-sky-700 hover:border-sky-300"
                            type="button"
                            onClick={() => void handleEditPublishedWorkflow(workflow)}
                            data-testid={`workflow-list-edit-${workflow.workflowCode}`}
                          >
                            编辑
                          </button>
                          <button
                            className="rounded-md border border-red-200 px-3 py-1.5 text-sm text-red-600 hover:border-red-300"
                            type="button"
                            onClick={() => void handleDeletePublishedWorkflow(workflow)}
                            data-testid={`workflow-list-delete-${workflow.workflowCode}`}
                          >
                            删除
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm text-slate-600">
                <div data-testid="workflow-list-page-summary">
                  第 {normalizedWorkflowListPage} / {workflowTotalPages} 页 · 共 {publishedWorkflowOptions.length} 个
                </div>
                <div className="flex items-center gap-2">
                  <button
                    className="prompt-secondary"
                    type="button"
                    onClick={() => setWorkflowListPage((current) => Math.max(1, current - 1))}
                    disabled={normalizedWorkflowListPage <= 1}
                    data-testid="workflow-list-prev"
                  >
                    上一页
                  </button>
                  <button
                    className="prompt-secondary"
                    type="button"
                    onClick={() => setWorkflowListPage((current) => Math.min(workflowTotalPages, current + 1))}
                    disabled={normalizedWorkflowListPage >= workflowTotalPages}
                    data-testid="workflow-list-next"
                  >
                    下一页
                  </button>
                </div>
              </div>
            </div>
          </section>
        )
      }

      return (
        <section className="page-grid page-grid-workflow" data-testid="workflow-page-layout">
          <div className="page-stack min-w-0" data-testid="workflow-page-main">
            <div className="flex flex-wrap justify-end gap-2">
              <button
                className="prompt-secondary"
                type="button"
                onClick={openWorkflowList}
                data-testid="workflow-back-list"
              >
                返回工作流列表
              </button>
            </div>
            <Orchestrator
              key={workflowEditorInstance}
              currentUserId={currentUserId}
              editorSelection={workflowEditorSelection}
              onWorkflowVersionMutation={setWorkflowVersionMutation}
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
        <section className="page-model-config" data-testid="models-page-layout">
          <ModelConfigPanel currentUserId={currentUserId} />
        </section>
      )
    }

    if (activePage === 'capability-center') {
      return (
        <section className="page-capability-center">
          <CapabilityCenterPanel currentUserId={currentUserId} />
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
                <div className="panel-title">聊天控制台</div>
                <div className="mt-1 text-xs text-slate-500">
                  执行状态：{displayExecutionStatus(executionStatus)}
                </div>
              </div>
              <div className="flex items-start gap-3">
                <button
                  className="prompt-secondary"
                  onClick={() => void handleCreateNewSession()}
                  type="button"
                  data-testid="chat-new-session"
                >
                  新建会话
                </button>
                <div className="text-right text-xs text-slate-500" data-testid="current-session-meta">
                  <div>会话 ID：{sessionId || '尚未创建'}</div>
                  <div>
                    会话 {displaySessionStatus(currentSession?.status)} / 连接 {displaySocketState(socketState)}
                  </div>
                </div>
              </div>
            </div>
            <div className="mb-4 rounded-2xl border border-sky-100 bg-[linear-gradient(135deg,rgba(240,249,255,0.95),rgba(255,255,255,0.98))] px-4 py-4">
              <div className="text-sm font-semibold text-slate-800">选择已发布工作流</div>
              <div className="mt-1 text-sm text-slate-600">
                你可以固定一个已发布工作流进行定向测试，也可以留空并交由路由自动选择。
              </div>
              <div className="mt-3 grid gap-2 md:grid-cols-[minmax(0,1fr)_auto]">
                <select
                  value={selectedPublishedWorkflowCode}
                  onChange={(event) => handleChatWorkflowSelect(event.target.value)}
                  className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700"
                  data-testid="chat-workflow-select"
                >
                  <option value="">不固定工作流</option>
                  {publishedWorkflowOptions.map((workflow) => (
                    <option key={workflow.workflowCode} value={workflow.workflowCode}>
                      {workflow.name} ({workflow.workflowCode} / {workflow.currentVersion || '未知版本'})
                    </option>
                  ))}
                </select>
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500" data-testid="chat-workflow-target">
                  当前目标：
                  {' '}
                  {selectedPublishedWorkflowCode
                    ? `${selectedPublishedWorkflowCode} / ${
                      (chatWorkflowBinding?.workflowCode === selectedPublishedWorkflowCode
                        ? chatWorkflowBinding.workflowVersion
                        : publishedWorkflowOptions.find((item) => item.workflowCode === selectedPublishedWorkflowCode)?.currentVersion) || '未知版本'
                    }`
                    : '由路由自动决定'}
                </div>
              </div>
            </div>
            <MessageList messages={messages} isLoading={isLoading} />
            <div className="panel-footer">
              <div className="mb-2 text-xs text-slate-400">
                用户：{displayUserLabel(currentUserId)} / 固定工作流：
                {selectedPublishedWorkflowCode
                  ? `${selectedPublishedWorkflowCode}@${
                    (chatWorkflowBinding?.workflowCode === selectedPublishedWorkflowCode
                      ? chatWorkflowBinding.workflowVersion
                      : publishedWorkflowOptions.find((item) => item.workflowCode === selectedPublishedWorkflowCode)?.currentVersion) || 'latest'
                  }`
                  : '无'}
                {' '} / 已启用流式输出
              </div>
              <ChatInput onSendMessage={(content) => void handleSendMessage(content)} isLoading={isLoading} />
            </div>
          </div>
        </div>
        <div className="page-stack">
          <SessionReplayPanel
            sessions={sessions}
            activeSessionId={sessionId}
            connectedSessionId={socketState === 'connected' ? sessionId : ''}
            selectedSessionId={selectedHistorySessionId}
            selectedMessages={selectedHistorySessionId === sessionId ? messages : selectedHistoryMessages}
            sessionMessagesById={sessionMessagesById}
            isLoadingSessions={isLoadingSessionHistory}
            isLoadingMessages={isLoadingSelectedHistory}
            onSelectSession={setSelectedHistorySessionId}
            onDeleteSession={handleDeleteSession}
          />
        </div>
      </section>
    )
  }
  return (
    <div className="app-shell">
      <header className="app-header">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">机器人代理控制台</h1>
          <div className="text-sm text-slate-500">多工作流编排与会话调试</div>
        </div>
        <div className="flex items-center gap-3 text-xs text-slate-400">
          <nav className="nav-tabs">
            <button className={`nav-tab ${activePage === 'chat' ? 'active' : ''}`} onClick={() => navigateToPage('chat')}>
              聊天
            </button>
            <button className={`nav-tab ${activePage === 'workflow' ? 'active' : ''}`} onClick={() => navigateToPage('workflow')}>
              工作流
            </button>
            <button className={`nav-tab ${activePage === 'execution' ? 'active' : ''}`} onClick={() => navigateToPage('execution')}>
              执行
            </button>
            <button className={`nav-tab ${activePage === 'models' ? 'active' : ''}`} onClick={() => navigateToPage('models')}>
              模型
            </button>
            <button className={`nav-tab ${activePage === 'capability-center' ? 'active' : ''}`} onClick={() => navigateToPage('capability-center')}>
              能力中心
            </button>
          </nav>
          <label className="flex items-center gap-2">
            <span>用户</span>
            <select
              value={currentUserId}
              onChange={(event) => {
                setCurrentUserId(event.target.value)
              }}
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            >
              <option value="demo-user">演示用户</option>
              <option value="demo-admin">演示管理员</option>
              <option value="anonymous">匿名用户</option>
            </select>
          </label>
          <label className="flex items-center gap-2">
            <span>会话</span>
            <select
              value={sessionId}
              onChange={(event) => {
                handleSessionChange(event.target.value)
              }}
              className="max-w-[220px] rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            >
              {sessions.map((session) => (
                <option key={session.id} value={session.id}>
                  {session.id} / {displaySessionStatus(session.status)}
                </option>
              ))}
            </select>
          </label>
          <button
            className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            onClick={() => void handleCreateNewSession()}
            type="button"
          >
            新建会话
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
