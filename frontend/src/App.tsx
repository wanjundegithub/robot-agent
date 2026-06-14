import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import ChatInput from './components/ChatInput'
import ApiCenterPanel from './components/ApiCenterPanel'
import ExecutionPanel from './components/ExecutionPanel'
import FormDialog from './components/FormDialog'
import KnowledgeCenterPanel from './components/KnowledgeCenterPanel'
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
import { downloadCallLogs, logGatewayEvent } from './services/callLogger'
import { createInitFrame, createInteractiveFrame, isUserFrameEnvelope, toInteractiveEventType } from './services/frameProtocol'
import { displayExecutionStatus, displaySessionStatus, displaySocketState, displayUserLabel } from './utils/displayText'
import type {
  ExecutionDetail,
  ExecutionEventEnvelope,
  ExecutionEventView,
  FormDefinition,
  LegacyAckEnvelope,
  LegacyErrorEnvelope,
  IntentCandidate,
  Message,
  MessageDeltaEnvelope,
  SessionSummary,
  SendMessageResponse,
  SocketState,
  UserFrameEnvelope,
  WebSocketEnvelope,
  WorkflowEditorSelection,
  WorkflowSummary,
} from './types'
import type { WorkflowVersionMutation } from './components/Orchestrator'

type PageKey = 'chat' | 'workflow' | 'execution' | 'models' | 'api-center' | 'knowledge'
type WorkflowPageMode = 'list' | 'editor'

const WORKFLOW_LIST_PAGE_SIZE = 10
const AUTO_ROUTE_WORKFLOW_MODE = '__AUTO_ROUTE__'

const buildSocketBindingKey = (activeSessionId: string, workflow: WorkflowSummary | null) => {
  if (!activeSessionId) return ''
  if (!workflow?.workflowCode || !workflow.currentVersion) {
    return `${activeSessionId}|base`
  }
  return `${activeSessionId}|${workflow.workflowCode}|${workflow.currentVersion}`
}

const formatSendFailureMessage = (error: unknown) => {
  const fallback = '消息发送失败，请稍后再试。'
  const detail = error instanceof Error ? error.message : String(error ?? '')
  if (!detail.trim()) return fallback
  return `消息发送失败：${detail}`
}

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
    logGatewayEvent(`gateway.${event}`, 'event', details)
    return
  }
  console.info(`[gateway] ${event}`)
  logGatewayEvent(`gateway.${event}`, 'event')
}

const isVisibleConversationMessage = (message: Message): boolean =>
  message.type !== 'system' && message.id !== 'welcome'

const normalizeSessionMessages = (items: Message[]): Message[] =>
  items.filter(isVisibleConversationMessage)

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
  const [messages, setMessages] = useState<Message[]>([])
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
  const [pendingIntentCandidate, setPendingIntentCandidate] = useState<{
    content: string
    messageId: string
    candidate: IntentCandidate
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
  const [chatWorkflowMode, setChatWorkflowMode] = useState('')
  const [workflowVersionMutation, setWorkflowVersionMutation] = useState<WorkflowVersionMutation | null>(null)
  const [workflowEditorSelection, setWorkflowEditorSelection] = useState<WorkflowEditorSelection | null>(null)
  const [workflowEditorInstance, setWorkflowEditorInstance] = useState(0)
  const [workflowPageMode, setWorkflowPageMode] = useState<WorkflowPageMode>('list')
  const [workflowListPage, setWorkflowListPage] = useState(1)
  const [workflowListStatus, setWorkflowListStatus] = useState('')
  const wsRef = useRef<WebSocket | null>(null)
  const activeSocketSessionIdRef = useRef<string | null>(null)
  const activeSocketBindingKeyRef = useRef<string | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const reconnectAttemptsRef = useRef(0)
  const streamMessageIdsRef = useRef(new Map<string, string>())
  const pendingStreamMessageIdsRef = useRef<string[]>([])
  const pendingRequestsRef = useRef(
    new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void; timeoutId: number }>()
  )

  const loadPublishedWorkflowOptions = useCallback(async () => {
    try {
      const items = await getPublishedWorkflows()
      const filteredItems = items.filter(isDisplayableWorkflow)
      setPublishedWorkflowOptions(filteredItems)
      setChatWorkflowMode((current) =>
        current === '' || current === AUTO_ROUTE_WORKFLOW_MODE
          ? current
          : filteredItems.some((item) => item.workflowCode === current)
            ? current
            : ''
      )
    } catch (error) {
      console.error('Failed to load published workflow options:', error)
    }
  }, [])

  const selectedChatWorkflow = useMemo(() => {
    if (chatWorkflowMode === '' || chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE) {
      return null
    }
    return publishedWorkflowOptions.find((item) => item.workflowCode === chatWorkflowMode) ?? null
  }, [chatWorkflowMode, publishedWorkflowOptions])

  const selectedChatWorkflowVersion = selectedChatWorkflow?.currentVersion || ''
  const chatWorkflowModeLabel =
    chatWorkflowMode === ''
      ? '请选择工作流模式'
      : chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE
        ? '无固定工作流'
        : selectedChatWorkflow?.workflowCode || chatWorkflowMode
  const chatWorkflowModeDetail =
    chatWorkflowMode === ''
      ? '先选择后再发送'
      : chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE
        ? '由路由自动决定'
        : selectedChatWorkflowVersion || '未知版本'
  const chatWorkflowModeFooter =
    chatWorkflowMode === ''
      ? '请选择工作流模式'
      : chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE
        ? '无固定工作流'
        : `${selectedChatWorkflow?.workflowCode || chatWorkflowMode}@${selectedChatWorkflowVersion || 'latest'}`
  const chatWorkflowConnectionKey = useMemo(() => {
    return buildSocketBindingKey(socketSessionId, selectedChatWorkflow)
  }, [selectedChatWorkflow, selectedChatWorkflowVersion, socketSessionId])

  const resetSessionView = useCallback(() => {
    setMessages([])
    setEvents([])
    setExecutions([])
    setExecutionId(null)
    setExecutionStatus('idle')
    setPendingSwitch(null)
    setPendingConfirmation(null)
    setPendingIntentCandidate(null)
    setResumeOffer(null)
    setPendingForm(null)
    pendingStreamMessageIdsRef.current = []
    setIsLoading(false)
  }, [])

  const cacheSessionMessages = useCallback((targetSessionId: string, nextMessages: Message[]) => {
    if (!targetSessionId) return
    setSessionMessagesById((prev) => ({
      ...prev,
      [targetSessionId]: normalizeSessionMessages(nextMessages),
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
      setMessages((prev) => {
        if (!hasUserMessage(normalizedHistory) && hasUserMessage(prev)) {
          return prev
        }
        return normalizedHistory
      })
    } catch (error) {
      console.error('Failed to load session messages:', error)
      cacheSessionMessages(activeSessionId, [])
      removePersistedSession(activeSessionId)
      setMessages([])
    }
  }, [cacheSessionMessages, markSessionPersisted, removePersistedSession])

  const createAndSelectSession = useCallback(async (userId: string) => {
    const created = await createSession({ userId })
    cacheSessionMessages(created.id, [])
    setCurrentSession(created)
    setSessionId(created.id)
    setSocketSessionId(created.id)
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
      activeSocketBindingKeyRef.current = null
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
      if (
        value === 'workflow' ||
        value === 'execution' ||
        value === 'models' ||
        value === 'chat' ||
        value === 'api-center' ||
        value === 'knowledge'
      ) {
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
        activeSocketBindingKeyRef.current = null
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

    const wsUrl = buildWsUrl(socketSessionId, selectedChatWorkflow)
    let isCancelled = false

    const connect = (attempt = 0) => {
      if (isCancelled) return
      setSocketState(attempt === 0 ? 'connecting' : 'reconnecting')
      const socket = new WebSocket(wsUrl)
      wsRef.current = socket
      gatewayLog('ws.connecting', { attempt, wsUrl, sessionId: socketSessionId, binding_key: chatWorkflowConnectionKey })

      socket.onopen = () => {
        if (wsRef.current !== socket) {
          gatewayLog('ws.stale_open_ignored', { sessionId: socketSessionId, wsUrl })
          return
        }
        reconnectAttemptsRef.current = 0
        activeSocketSessionIdRef.current = socketSessionId
        activeSocketBindingKeyRef.current = chatWorkflowConnectionKey
        setSocketState('connected')
        gatewayLog('ws.open', { sessionId: socketSessionId, wsUrl, binding_key: chatWorkflowConnectionKey })
        const initFrame = createInitFrame({
          requestId: createId('init'),
          userId: currentUserId,
          sessionId: socketSessionId,
          workflow: selectedChatWorkflow,
        })
        socket.send(JSON.stringify(initFrame))
        gatewayLog('ws.init_frame_sent', {
          request_id: initFrame.request_id,
          frame: initFrame.frame,
          session_id: initFrame.session_id,
          user_id: initFrame.user_id,
        })
      }

      socket.onmessage = (event) => {
        if (wsRef.current !== socket) {
          gatewayLog('ws.stale_message_ignored', { sessionId: socketSessionId, wsUrl })
          return
        }
        try {
          const rawPayload = JSON.parse(event.data) as WebSocketEnvelope | UserFrameEnvelope
          if (isUserFrameEnvelope(rawPayload)) {
            handleUserFrame(rawPayload)
            return
          }
          const payload = rawPayload as any
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
            handleLegacyAck(payload)
          } else if (payload.type === 'error') {
            handleLegacyError(payload)
          }
        } catch (error) {
          console.error('Invalid WebSocket payload:', error)
        }
      }

      socket.onerror = () => {
        if (wsRef.current !== socket) {
          gatewayLog('ws.stale_error_ignored', { sessionId: socketSessionId, wsUrl })
          return
        }
        activeSocketSessionIdRef.current = null
        activeSocketBindingKeyRef.current = null
        setSocketState('reconnecting')
        gatewayLog('ws.error', { sessionId: socketSessionId, wsUrl })
      }

      socket.onclose = () => {
        if (wsRef.current !== socket) {
          gatewayLog('ws.stale_close_ignored', { sessionId: socketSessionId, wsUrl })
          return
        }
        wsRef.current = null
        activeSocketSessionIdRef.current = null
        activeSocketBindingKeyRef.current = null
        gatewayLog('ws.close', { sessionId: socketSessionId, wsUrl, attempts: reconnectAttemptsRef.current })
        pendingRequestsRef.current.forEach(({ reject, timeoutId }) => {
          window.clearTimeout(timeoutId)
          reject(new Error('WebSocket disconnected'))
        })
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
      activeSocketSessionIdRef.current = null
      activeSocketBindingKeyRef.current = null
      wsRef.current?.close()
    }
  }, [activePage, chatWorkflowConnectionKey, currentUserId])

  const buildWsUrl = (activeSessionId: string, workflow: WorkflowSummary | null) => {
    const base = import.meta.env.VITE_NETTY_WS_BASE_URL || import.meta.env.VITE_WS_BASE_URL
    const origin =
      base ||
      `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}`
    const url = new URL(`${origin}/ws/robot`)
    url.searchParams.set('session_id', activeSessionId)
    if (workflow?.workflowCode && workflow.currentVersion) {
      url.searchParams.set('workflow_code', workflow.workflowCode)
      url.searchParams.set('workflow_version', workflow.currentVersion)
    }
    gatewayLog('workflow.ws_url_built', {
      session_id: activeSessionId,
      workflow_mode:
        chatWorkflowMode === ''
          ? 'unselected'
          : chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE
            ? 'auto_route'
            : 'fixed',
      has_workflow: Boolean(workflow?.workflowCode && workflow.currentVersion),
    })
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
    (targetSessionId: string, targetBindingKey: string, timeoutMs = 8000) =>
      new Promise<void>((resolve, reject) => {
        const start = Date.now()

        const check = () => {
          const socket = wsRef.current
          const isReady =
            socket?.readyState === WebSocket.OPEN &&
            activeSocketSessionIdRef.current === targetSessionId &&
            activeSocketBindingKeyRef.current === targetBindingKey

          if (isReady) {
            gatewayLog('ws.ready', {
              session_id: targetSessionId,
              binding_key: targetBindingKey,
              waited_ms: Date.now() - start,
            })
            resolve()
            return
          }

          if (Date.now() - start >= timeoutMs) {
            gatewayLog('ws.ready_timeout', {
              session_id: targetSessionId,
              binding_key: targetBindingKey,
              socket_ready_state: socket?.readyState ?? null,
              active_session_id: activeSocketSessionIdRef.current,
              active_binding_key: activeSocketBindingKeyRef.current,
            })
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
    activeSocketBindingKeyRef.current = null
    pendingRequestsRef.current.forEach(({ reject, timeoutId }) => {
      window.clearTimeout(timeoutId)
      reject(new Error('WebSocket disconnected'))
    })
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

  const appendSystemMessage = (_content: string) => {
  }

  const pushGovernanceNotice = (title: string, detail: string) => {
    appendSystemMessage(`${title}: ${detail}`)
  }

  const removePendingStreamingMessage = (pendingMessageId: string) => {
    pendingStreamMessageIdsRef.current = pendingStreamMessageIdsRef.current.filter((id) => id !== pendingMessageId)
    setMessages((prev) =>
      prev.filter((message) => (
        message.id !== pendingMessageId ||
        message.content.trim().length > 0 ||
        Boolean(message.executionId)
      ))
    )
  }

  const summarizeExecutionEvent = (payload: ExecutionEventEnvelope): string | null => {
    const data = payload.data || {}
    const nodeType = String((data as any).node_type || '').toLowerCase()
    switch (payload.event_type) {
      case 'routing.decided':
        return '\u6b63\u5728\u8bc6\u522b\u610f\u56fe'
      case 'node.started':
        if (nodeType === 'start') return '\u6b63\u5728\u68c0\u67e5\u5f00\u59cb\u8282\u70b9\u53d8\u91cf'
        if (nodeType === 'llm') return '\u6b63\u5728\u7b49\u5f85\u6a21\u578b\u751f\u6210\u56de\u590d'
        if (nodeType === 'api') return '\u6b63\u5728\u51c6\u5907\u8c03\u7528 API'
        if (nodeType === 'tool') return '\u6b63\u5728\u51c6\u5907\u8c03\u7528\u5de5\u5177'
        return '\u6b63\u5728\u8bc6\u522b\u610f\u56fe'
      case 'tool.called':
        return '\u6b63\u5728\u8c03\u7528\u5de5\u5177'
      case 'execution.waiting_user':
        return '\u6b63\u5728\u7b49\u5f85\u7528\u6237\u8865\u5145\u4fe1\u606f'
      default:
        return null
    }
  }

  const resolveStreamingMessageId = (activeExecutionId: string, createWhenMissing: boolean): string | null => {
    let existingMessageId = streamMessageIdsRef.current.get(activeExecutionId)
    if (!existingMessageId) {
      existingMessageId = pendingStreamMessageIdsRef.current.shift()
      if (existingMessageId) {
        streamMessageIdsRef.current.set(activeExecutionId, existingMessageId)
      }
    }
    if (!existingMessageId && !createWhenMissing) {
      return null
    }
    const messageId = existingMessageId || `stream_${activeExecutionId}_${createId('part')}`
    if (!existingMessageId) {
      streamMessageIdsRef.current.set(activeExecutionId, messageId)
    }
    setMessages((prev) => {
      if (prev.some((message) => message.id === messageId)) {
        return prev.map((message) =>
          message.id === messageId && !message.executionId
            ? { ...message, executionId: activeExecutionId }
            : message
        )
      }
      return [
        ...prev,
        {
          id: messageId,
          type: 'ai',
          content: '',
          streaming: true,
          executionId: activeExecutionId,
          timestamp: new Date().toISOString(),
        },
      ]
    })
    return messageId
  }

  const ensureStreamingMessage = (activeExecutionId: string): string => (
    resolveStreamingMessageId(activeExecutionId, true) as string
  )

  const appendProcessStep = (activeExecutionId: string, label: string, detail?: string) => {
    const messageId = resolveStreamingMessageId(activeExecutionId, false)
    if (!messageId) return
    setMessages((prev) =>
      prev.map((message) => {
        if (message.id !== messageId) return message
        const processSteps = message.processSteps ?? []
        const lastStep = processSteps[processSteps.length - 1]
        if (lastStep?.label === label && lastStep?.detail === detail) return message
        return {
          ...message,
          processSteps: [
            ...processSteps,
            {
              id: createId('step'),
              label,
              detail,
              timestamp: new Date().toISOString(),
            },
          ],
        }
      })
    )
  }

  const finalizeStreamingMessage = (activeExecutionId: string) => {
    const messageId = streamMessageIdsRef.current.get(activeExecutionId) || `stream_${activeExecutionId}`
    setMessages((prev) =>
      prev.map((message) =>
        message.id === messageId
          ? { ...message, streaming: false }
          : message
      )
    )
    streamMessageIdsRef.current.delete(activeExecutionId)
  }

  const handleMessageDelta = (payload: MessageDeltaEnvelope) => {
    if (payload.session_id && payload.session_id !== sessionId) return

    const messageId = ensureStreamingMessage(payload.execution_id)
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
          executionId: payload.execution_id,
          timestamp: new Date().toISOString(),
        },
      ]
    })

    if (payload.is_complete) {
      streamMessageIdsRef.current.delete(payload.execution_id)
      setIsLoading(false)
      setExecutionStatus('completed')
    }
  }

  const handleLegacyAck = (payload: LegacyAckEnvelope) => {
    gatewayLog('ack', {
      request_id: payload.request_id,
      action: payload.action,
      status: payload.status,
    })
    const pending = pendingRequestsRef.current.get(payload.request_id)
    if (!pending) return
    window.clearTimeout(pending.timeoutId)
    pendingRequestsRef.current.delete(payload.request_id)
    pending.resolve((payload.data as Record<string, unknown> | undefined) ?? undefined)
  }

  const handleLegacyError = (payload: LegacyErrorEnvelope) => {
    gatewayLog('error', {
      request_id: payload.request_id,
      error_code: payload.error_code,
      message: payload.message,
    })
    if (payload.request_id) {
      const pending = pendingRequestsRef.current.get(payload.request_id)
      if (pending) {
        window.clearTimeout(pending.timeoutId)
        pendingRequestsRef.current.delete(payload.request_id)
        pending.reject(new Error(payload.message))
        return
      }
    }
    appendSystemMessage(`网关错误：${payload.message}`)
  }

  const resolvePendingFrameRequest = (frame: UserFrameEnvelope): boolean => {
    if (!frame.request_id) return false
    const pending = pendingRequestsRef.current.get(frame.request_id)
    if (!pending) return false
    window.clearTimeout(pending.timeoutId)
    pendingRequestsRef.current.delete(frame.request_id)
    const payload = frame.payload ?? {}
    pending.resolve((payload as any).data ?? payload)
    return true
  }

  const handleUserFrame = (frame: UserFrameEnvelope) => {
    const eventType = frame.event_type || ''
    const framePayload = frame.payload ?? {}
    gatewayLog('frame.message', {
      frame: frame.frame,
      event_type: eventType,
      request_id: frame.request_id,
      session_id: frame.session_id,
      execution_id: frame.execution_id,
    })

    if (eventType.startsWith('error.')) {
      handleLegacyError({
        type: 'error',
        request_id: frame.request_id,
        error_code: String((framePayload as any).code ?? eventType.replace('error.', '')),
        message: String((framePayload as any).message ?? eventType),
      })
      return
    }

    if (frame.frame === 8) {
      if (eventType === 'connection.replaced') {
        gatewayLog('frame.connection_replaced', {
          request_id: frame.request_id,
          session_id: frame.session_id,
          reason: (framePayload as any).reason,
        })
        if (wsRef.current) {
          pendingRequestsRef.current.forEach(({ reject, timeoutId }) => {
            window.clearTimeout(timeoutId)
            reject(new Error('WebSocket connection replaced'))
          })
          pendingRequestsRef.current.clear()
          reconnectAttemptsRef.current = 0
          activeSocketSessionIdRef.current = null
          activeSocketBindingKeyRef.current = null
          wsRef.current.onclose = null
          wsRef.current.close()
          wsRef.current = null
        }
        setSocketState('disconnected')
        return
      }
      resolvePendingFrameRequest(frame)
      return
    }

    if (eventType === 'message.delta') {
      handleMessageDelta({
        type: 'message_delta',
        execution_id: frame.execution_id || String((framePayload as any).execution_id ?? ''),
        session_id: frame.session_id,
        content: String((framePayload as any).content ?? ''),
        is_complete: Boolean((framePayload as any).is_complete),
      })
      return
    }

    if (
      eventType === 'message.accepted' ||
      eventType === 'form.submitted' ||
      eventType === 'execution.resumed' ||
      eventType === 'request.ack' ||
      eventType === 'heartbeat.pong'
    ) {
      if (resolvePendingFrameRequest(frame)) return
    }

    if (
      eventType.startsWith('execution.') ||
      eventType.startsWith('node.') ||
      eventType.startsWith('tool.') ||
      eventType.startsWith('security.') ||
      eventType.startsWith('protection.') ||
      eventType.startsWith('workflow.') ||
      eventType === 'form.requested' ||
      eventType === 'routing.decided' ||
      eventType === 'budget.alert' ||
      eventType === 'confirmation.required'
    ) {
      handleExecutionEvent({
        type: 'event',
        event_type: eventType as ExecutionEventEnvelope['event_type'],
        execution_id: frame.execution_id || String((framePayload as any).execution_id ?? ''),
        session_id: frame.session_id,
        data: framePayload,
      })
    }
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
    const processLabel = summarizeExecutionEvent(payload)
    if (processLabel) {
      appendProcessStep(payload.execution_id, processLabel)
    }

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

    if (payload.event_type === 'execution.waiting_user') {
      finalizeStreamingMessage(payload.execution_id)
      setExecutionStatus('waiting_user')
      setIsLoading(false)
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

  const sendInteractiveFrame = (
    requestedEventType: string,
    payload: Record<string, unknown>,
    targetSessionId = sessionId,
    targetWorkflow = selectedChatWorkflow
  ): Promise<unknown> => {
    const requestId = createId('req')
    const targetBindingKey = buildSocketBindingKey(targetSessionId, targetWorkflow)
    const eventType = toInteractiveEventType(requestedEventType)

    return new Promise((resolve, reject) => {
      let timeoutId: number | null = null
      gatewayLog('send.queued', {
        request_id: requestId,
        requested_event_type: requestedEventType,
        event_type: eventType,
        session_id: targetSessionId,
        socket_session_id: socketSessionId,
        target_binding_key: targetBindingKey,
        active_binding_key: activeSocketBindingKeyRef.current,
      })
      if (targetSessionId && targetBindingKey !== chatWorkflowConnectionKey) {
        gatewayLog('send.socket_binding_switch', {
          request_id: requestId,
          from_session_id: socketSessionId,
          to_session_id: targetSessionId,
          from_binding_key: chatWorkflowConnectionKey,
          to_binding_key: targetBindingKey,
        })
        setSocketSessionId(targetSessionId)
      }

      void waitForSocketReady(targetSessionId, targetBindingKey)
        .then(() => {
          const socket = wsRef.current
          if (
            !socket ||
            socket.readyState !== WebSocket.OPEN ||
            activeSocketSessionIdRef.current !== targetSessionId ||
            activeSocketBindingKeyRef.current !== targetBindingKey
          ) {
            throw new Error('WebSocket not connected')
          }

          timeoutId = window.setTimeout(() => {
            if (!pendingRequestsRef.current.has(requestId)) return
            pendingRequestsRef.current.delete(requestId)
            gatewayLog('send.timeout', {
              request_id: requestId,
              requested_event_type: requestedEventType,
              event_type: eventType,
              session_id: targetSessionId,
              binding_key: targetBindingKey,
            })
            reject(new Error(`Frame ack timeout for ${eventType}`))
          }, 45000)
          pendingRequestsRef.current.set(requestId, { resolve, reject, timeoutId })

          gatewayLog('send', {
            request_id: requestId,
            requested_event_type: requestedEventType,
            event_type: eventType,
            session_id: targetSessionId,
            binding_key: targetBindingKey,
            payload,
          })
          const frame = createInteractiveFrame({
            requestId,
            userId: currentUserId,
            sessionId: targetSessionId,
            executionId: typeof payload.execution_id === 'string' ? payload.execution_id : executionId,
            eventType,
            payload,
          })
          socket.send(
            JSON.stringify(frame)
          )
        })
        .catch((error) => {
          if (timeoutId !== null) {
            window.clearTimeout(timeoutId)
          }
          pendingRequestsRef.current.delete(requestId)
          gatewayLog('send.not_ready', {
            request_id: requestId,
            requested_event_type: requestedEventType,
            event_type: eventType,
            session_id: targetSessionId,
            binding_key: targetBindingKey,
            active_session_id: activeSocketSessionIdRef.current,
            active_binding_key: activeSocketBindingKeyRef.current,
            error: error instanceof Error ? error.message : String(error),
          })
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
      intentCandidateAction?: 'accept' | 'reject'
      intentCandidateTargetCode?: string | null
    },
    fixedMessageId?: string
  ) => {
    if (!content.trim()) return
    if (chatWorkflowMode === '') {
      appendSystemMessage('请先选择工作流模式：无固定工作流，或指定固定工作流。')
      gatewayLog('workflow.send_blocked', {
        reason: 'mode_unselected',
        session_id: sessionId || null,
      })
      return
    }
    if (chatWorkflowMode !== AUTO_ROUTE_WORKFLOW_MODE && !selectedChatWorkflow) {
      appendSystemMessage('当前选择的固定工作流不可用，请重新选择工作流模式。')
      gatewayLog('workflow.send_blocked', {
        reason: 'fixed_workflow_unavailable',
        session_id: sessionId || null,
      })
      return
    }

    const messageId = fixedMessageId || createId('msg')
    const shouldAppendUserMessage =
      !options?.confirmSwitch &&
      !options?.confirmationId &&
      !options?.cancelConfirmation &&
      !options?.intentCandidateAction
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
          processSteps: [
            {
              id: createId('step'),
              label: '\u6b63\u5728\u8bc6\u522b\u610f\u56fe',
              timestamp: new Date().toISOString(),
            },
          ],
          timestamp: new Date().toISOString(),
        },
      ])
      pendingStreamMessageIdsRef.current.push(pendingMessageId)
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

      const shouldSendFixedWorkflow = Boolean(
        selectedChatWorkflow?.workflowCode && selectedChatWorkflow.currentVersion
      )
      const chatSendPayload: Record<string, unknown> = {
        session_id: activeSessionId,
        message_id: messageId,
        content,
        attachments: [],
        user_id: currentUserId,
        confirm_switch: options?.confirmSwitch ?? false,
        requested_tool_code: options?.requestedToolCode ?? null,
        confirmation_id: options?.confirmationId ?? null,
        cancel_confirmation: options?.cancelConfirmation ?? false,
        intent_candidate_action: options?.intentCandidateAction ?? null,
        intent_candidate_target_code: options?.intentCandidateTargetCode ?? null,
      }
      if (shouldSendFixedWorkflow) {
        chatSendPayload.workflow_id = selectedChatWorkflow?.id ?? null
        chatSendPayload.workflow_code = selectedChatWorkflow?.workflowCode
        chatSendPayload.workflow_version = selectedChatWorkflow?.currentVersion
      }

      gatewayLog('workflow.chat_send_payload', {
        session_id: activeSessionId,
        workflow_mode:
          chatWorkflowMode === AUTO_ROUTE_WORKFLOW_MODE ? 'auto_route' : 'fixed',
        has_workflow: shouldSendFixedWorkflow,
      })
      const response = (await sendInteractiveFrame('message.text', chatSendPayload, activeSessionId)) as unknown as SendMessageResponse
      gatewayLog('send_message.response', {
        session_id: activeSessionId,
        execution_id: response.execution_id,
        status: response.status,
        workflow_code: response.workflow_code,
        workflow_version: response.workflow_version,
      })

      if (response.status === 'permission_denied') {
        removePendingStreamingMessage(pendingMessageId)
        setExecutionStatus('permission_denied')
        setPendingConfirmation(null)
        setPendingIntentCandidate(null)
        pushGovernanceNotice('权限不足', response.permission_reason || '当前操作被策略阻止。')
        appendSystemMessage(`权限不足：${response.permission_reason || '当前用户无权执行此操作。'}`)
        return
      }

      if (response.status === 'confirmation_required') {
        removePendingStreamingMessage(pendingMessageId)
        setPendingConfirmation({ content, messageId, response })
        setPendingIntentCandidate(null)
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
        setPendingIntentCandidate(null)
        removePendingStreamingMessage(pendingMessageId)
        setExecutionStatus('idle')
        pushGovernanceNotice('已取消确认', '敏感操作已取消，流程不会继续执行。')
        appendSystemMessage('敏感操作已取消。')
        return
      }

      if (response.status === 'rate_limited' || response.status === 'degraded') {
        removePendingStreamingMessage(pendingMessageId)
        setExecutionStatus(response.status)
        setPendingConfirmation(null)
        setPendingIntentCandidate(null)
        const detail =
          response.degradation_message ||
          response.protection_reason ||
          `请在 ${response.retry_after_seconds || 0} 秒后重试。`
        pushGovernanceNotice(response.status === 'rate_limited' ? '已限流' : '已降级', detail)
        appendSystemMessage(detail)
        return
      }

      if (response.status === 'switch_required') {
        removePendingStreamingMessage(pendingMessageId)
        setPendingSwitch({ content, messageId, response })
        setPendingIntentCandidate(null)
        setExecutionStatus('switch_required')
        appendSystemMessage(
          `检测到新的意图，将切换到工作流 ${response.workflow_code}。请确认是否保存当前流程并切换。`
        )
        return
      }

      if (response.status === 'clarification_required') {
        setMessages((prev) => {
          const next = prev.filter((message) => (
            message.id !== pendingMessageId ||
            message.content.trim().length > 0 ||
            Boolean(message.executionId)
          ))
          const clarification = (response.clarification_question || '').trim()
          if (!clarification) {
            return next
          }
          return [
            ...next,
            {
              id: createId('clarify'),
              type: 'ai',
              content: clarification,
              timestamp: new Date().toISOString(),
            },
          ]
        })
        setPendingSwitch(null)
        setPendingConfirmation(null)
        setPendingIntentCandidate(null)
        setExecutionStatus('clarification_required')
        return
      }

      setPendingSwitch(null)
      setPendingConfirmation(null)
      const intentCandidateQueue = Array.isArray(response.intent_candidate_queue)
        ? response.intent_candidate_queue
        : []
      const shouldPromptIntentCandidate =
        !response.execution_id &&
        normalizeStatus(response.status || '') === 'candidate_confirmation_required' &&
        intentCandidateQueue.length > 0
      if (shouldPromptIntentCandidate) {
        setPendingIntentCandidate({
          content,
          messageId,
          candidate: intentCandidateQueue[0],
        })
      } else {
        setPendingIntentCandidate(null)
      }
      if (response.execution_id) {
        setExecutionId(response.execution_id)
      }
      setExecutionStatus(response.status ? normalizeStatus(response.status) : 'running')
      setEvents([])
      setResumeOffer(null)
      setMessages((prev) =>
        prev.map((message) =>
          message.id === pendingMessageId && response.execution_id
            ? { ...message, id: `stream_${response.execution_id}_${messageId}`, executionId: response.execution_id }
            : message
        )
      )
      if (response.execution_id) {
        streamMessageIdsRef.current.set(response.execution_id, `stream_${response.execution_id}_${messageId}`)
        pendingStreamMessageIdsRef.current = pendingStreamMessageIdsRef.current.filter((id) => id !== pendingMessageId)
      }
      markSessionPersisted(activeSessionId)
      await refreshExecutions(activeSessionId)
      await refreshSessionDetail(activeSessionId)
    } catch (error) {
      console.error('Failed to send message:', error)
      gatewayLog('send_message.failed', {
        session_id: activeSessionId || null,
        message_id: messageId,
        error: error instanceof Error ? error.message : String(error),
        socket_session_id: socketSessionId,
        active_socket_session_id: activeSocketSessionIdRef.current,
        active_binding_key: activeSocketBindingKeyRef.current,
      })
      removePendingStreamingMessage(pendingMessageId)
      if (activeSessionId) {
        cacheSessionMessages(activeSessionId, baselineMessages)
      }
      setMessages((prev) => [
        ...prev,
        {
          id: createId('err'),
          type: 'error',
          content: formatSendFailureMessage(error),
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
      const response = (await sendInteractiveFrame('execution.resume', {
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
    setSocketSessionId(nextSessionId)
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
    const previousMode = chatWorkflowMode
    setChatWorkflowMode(workflowCode)
    console.info('[chat] workflow mode changed', {
      workflow_mode:
        workflowCode === ''
          ? 'unselected'
          : workflowCode === AUTO_ROUTE_WORKFLOW_MODE
            ? 'auto_route'
            : 'fixed',
    })
    gatewayLog('workflow.mode_changed', {
      workflow_mode:
        workflowCode === ''
          ? 'unselected'
          : workflowCode === AUTO_ROUTE_WORKFLOW_MODE
            ? 'auto_route'
            : 'fixed',
    })
    if (workflowCode === AUTO_ROUTE_WORKFLOW_MODE && previousMode && previousMode !== AUTO_ROUTE_WORKFLOW_MODE) {
      void handleCreateNewSession()
    }
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
        workflowDescription: workflow.description,
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
      setChatWorkflowMode((current) => (current === workflow.workflowCode ? '' : current))
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

      {pendingIntentCandidate && (
        <div className="panel-card prompt-card">
          <div className="panel-title mb-2">候选意图确认</div>
          <div className="text-sm text-slate-700">
            检测到一个候选流程：
            {' '}
            <strong>{pendingIntentCandidate.candidate.target_code || '未知'}</strong>
          </div>
          <div className="mt-2 text-xs text-slate-500">
            意图：{pendingIntentCandidate.candidate.intent_code || '未知'} / 置信度：
            {' '}
            {pendingIntentCandidate.candidate.confidence != null
              ? pendingIntentCandidate.candidate.confidence.toFixed(2)
              : '未知'}
            {' '} / 来源：{pendingIntentCandidate.candidate.source || '未知'}
          </div>
          {pendingIntentCandidate.candidate.evidence && (
            <div className="mt-2 text-xs text-slate-500">
              证据：{pendingIntentCandidate.candidate.evidence}
            </div>
          )}
          <div className="prompt-actions">
            <button
              className="prompt-secondary"
              onClick={() =>
                void handleSendMessage(
                  pendingIntentCandidate.content,
                  {
                    intentCandidateAction: 'reject',
                    intentCandidateTargetCode: pendingIntentCandidate.candidate.target_code || null,
                  }
                )
              }
            >
              跳过
            </button>
            <button
              className="prompt-primary"
              onClick={() =>
                void handleSendMessage(
                  pendingIntentCandidate.content,
                  {
                    intentCandidateAction: 'accept',
                    intentCandidateTargetCode: pendingIntentCandidate.candidate.target_code || null,
                  }
                )
              }
            >
              继续办理
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

    if (activePage === 'api-center') {
      return (
        <section className="page-api-center">
          <ApiCenterPanel currentUserId={currentUserId} />
        </section>
      )
    }

    if (activePage === 'knowledge') {
      return (
        <section className="page-knowledge-center">
          <KnowledgeCenterPanel currentUserId={currentUserId} />
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
                请先选择工作流模式：无固定工作流，或指定固定工作流。
              </div>
              <div className="mt-3 grid gap-2 md:grid-cols-[minmax(0,1fr)_auto]">
                <select
                  value={chatWorkflowMode}
                  onChange={(event) => handleChatWorkflowSelect(event.target.value)}
                  className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700"
                  data-testid="chat-workflow-select"
                >
                  <option value="">请选择工作流模式</option>
                  <option value={AUTO_ROUTE_WORKFLOW_MODE}>无固定工作流</option>
                  {publishedWorkflowOptions.map((workflow) => (
                    <option key={workflow.workflowCode} value={workflow.workflowCode}>
                      {workflow.name} ({workflow.workflowCode} / {workflow.currentVersion || '未知版本'})
                    </option>
                  ))}
                </select>
                <div className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500" data-testid="chat-workflow-target">
                  当前目标：
                  {' '}
                  {chatWorkflowModeLabel} / {chatWorkflowModeDetail}
                </div>
              </div>
            </div>
            <MessageList messages={messages} isLoading={isLoading} />
            <div className="panel-footer">
              <div className="mb-2 text-xs text-slate-400">
                用户：{displayUserLabel(currentUserId)} / 当前模式：{chatWorkflowModeFooter} / 已启用流式输出
              </div>
              <fieldset className="min-w-0 border-0 p-0" disabled={isLoading || chatWorkflowMode === ''}>
                <ChatInput onSendMessage={(content) => void handleSendMessage(content)} isLoading={isLoading} />
              </fieldset>
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
            <button className={`nav-tab ${activePage === 'api-center' ? 'active' : ''}`} onClick={() => navigateToPage('api-center')}>
              API中心
            </button>
            <button className={`nav-tab ${activePage === 'knowledge' ? 'active' : ''}`} onClick={() => navigateToPage('knowledge')}>
              知识库
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
          <button
            className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600"
            onClick={() => downloadCallLogs()}
            type="button"
          >
            下载调用日志
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
              await sendInteractiveFrame('form.submit', {
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
