import { expect, test } from '@playwright/test'

const currentSessionIds = ['session-current-1', 'session-current-2', 'session-current-3'] as const
const failedSendContent = 'This message should fail to send'
const disconnectAfterReplyContent = 'This message gets a reply before the socket disconnects'
const isChatSendFrame = (payload: Record<string, unknown>) =>
  payload.action === 'chat.send' || payload.event_type === 'message.text'

const baseSessionDetails = {
  'session-current-1': {
    id: 'session-current-1',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'active',
    currentExecutionId: null,
    createdAt: '2026-04-21T10:00:00',
    lastActivityAt: '2026-04-21T10:00:00',
  },
  'session-current-2': {
    id: 'session-current-2',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'active',
    currentExecutionId: null,
    createdAt: '2026-04-21T10:05:00',
    lastActivityAt: '2026-04-21T10:05:00',
  },
  'session-current-3': {
    id: 'session-current-3',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'active',
    currentExecutionId: null,
    createdAt: '2026-04-21T10:10:00',
    lastActivityAt: '2026-04-21T10:10:00',
  },
  'session-history-1': {
    id: 'session-history-1',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'closed',
    currentExecutionId: 'exec-history-1',
    createdAt: '2026-04-20T09:00:00',
    lastActivityAt: '2026-04-20T09:15:00',
  },
  'session-history-2': {
    id: 'session-history-2',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'closed',
    currentExecutionId: 'exec-history-2',
    createdAt: '2026-04-19T08:00:00',
    lastActivityAt: '2026-04-19T08:10:00',
  },
  'session-empty-1': {
    id: 'session-empty-1',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'closed',
    currentExecutionId: null,
    createdAt: '2026-04-18T08:00:00',
    lastActivityAt: '2026-04-18T08:10:00',
  },
  'session-empty-2': {
    id: 'session-empty-2',
    workspaceId: 1,
    userId: 'demo-user',
    status: 'closed',
    currentExecutionId: null,
    createdAt: '2026-04-17T08:00:00',
    lastActivityAt: '2026-04-17T08:10:00',
  },
} as const

const baseSessionMessages = {
  'session-current-1': [],
  'session-current-2': [],
  'session-current-3': [],
  'session-history-1': [
    {
      id: 'msg-history-1-user',
      type: 'user',
      content: 'Help me investigate last week order issues',
      timestamp: '2026-04-20T09:00:00',
      executionId: 'exec-history-1',
    },
    {
      id: 'msg-history-1-ai',
      type: 'ai',
      content: 'The main issue came from inventory sync lag.',
      timestamp: '2026-04-20T09:01:00',
      executionId: 'exec-history-1',
    },
  ],
  'session-history-2': [
    {
      id: 'msg-history-2-user',
      type: 'user',
      content: 'Tell me about membership benefits',
      timestamp: '2026-04-19T08:00:00',
      executionId: 'exec-history-2',
    },
    {
      id: 'msg-history-2-ai',
      type: 'ai',
      content: 'Benefits include points, coupons, and priority support.',
      timestamp: '2026-04-19T08:01:00',
      executionId: 'exec-history-2',
    },
  ],
  'session-empty-1': [],
  'session-empty-2': [],
} as const

type SessionSummary = (typeof baseSessionDetails)[keyof typeof baseSessionDetails]
type SessionMessage = (typeof baseSessionMessages)[keyof typeof baseSessionMessages][number]

let createSessionCount = 0
let sessionList: SessionSummary[] = []
let messageStore: Record<string, SessionMessage[]> = {}
let delayedSessionMessageLoads: Record<string, number> = {}
let publishedWorkflows: Array<{
  id: number
  workflowCode: string
  name: string
  description?: string
  status: string
  currentVersion?: string
  createdBy?: string
}> = []

const createSessionList = (): SessionSummary[] => [
  baseSessionDetails['session-current-2'],
  baseSessionDetails['session-history-1'],
  baseSessionDetails['session-history-1'],
  baseSessionDetails['session-history-2'],
  baseSessionDetails['session-empty-1'],
  baseSessionDetails['session-empty-2'],
]

const createMessageStore = (): Record<string, SessionMessage[]> => ({
  'session-current-1': [...baseSessionMessages['session-current-1']],
  'session-current-2': [...baseSessionMessages['session-current-2']],
  'session-current-3': [...baseSessionMessages['session-current-3']],
  'session-history-1': [...baseSessionMessages['session-history-1']],
  'session-history-2': [...baseSessionMessages['session-history-2']],
  'session-empty-1': [...baseSessionMessages['session-empty-1']],
  'session-empty-2': [...baseSessionMessages['session-empty-2']],
})

test.beforeEach(async ({ page }) => {
  createSessionCount = 0
  sessionList = createSessionList()
  messageStore = createMessageStore()
  delayedSessionMessageLoads = {}
  publishedWorkflows = []

  await page.addInitScript(() => {
    class MockWebSocket {
      static CONNECTING = 0
      static OPEN = 1
      static CLOSING = 2
      static CLOSED = 3

      url: string
      readyState = MockWebSocket.CONNECTING
      onopen: ((event: Event) => void) | null = null
      onmessage: ((event: MessageEvent) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      onclose: ((event: CloseEvent) => void) | null = null

      constructor(url: string) {
        this.url = url
        ;(window as Window & { __mockWsUrls?: string[] }).__mockWsUrls ??= []
        ;(window as Window & { __mockWsUrls?: string[] }).__mockWsUrls?.push(url)
        window.setTimeout(() => {
          this.readyState = MockWebSocket.OPEN
          this.onopen?.(new Event('open'))
        }, 0)
      }

      send(data: string) {
        const payload = JSON.parse(data) as {
          frame?: number
          action?: string
          event_type?: string
          request_id?: string
          session_id?: string
          payload?: {
            message_id?: string
            content?: string
          }
        }
        ;(window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }).__mockWsPayloads ??= []
        ;(window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }).__mockWsPayloads?.push(payload)

        const isChatSend = payload.action === 'chat.send' || payload.event_type === 'message.text'
        if (!isChatSend || !payload.session_id || !payload.payload?.content) {
          return
        }

        if (payload.payload.content === 'Send while replacing socket should still reach gateway') {
          window.setTimeout(() => {
            this.onmessage?.(
              new MessageEvent('message', {
                data: JSON.stringify({
                  type: 'ack',
                  request_id: payload.request_id,
                  action: 'chat.send',
                  status: 'ok',
                  data: {
                    session_id: payload.session_id,
                    execution_id: `exec-${payload.session_id}`,
                    workflow_code: 'test-workflow',
                    workflow_version: 'v1',
                    status: 'completed',
                  },
                }),
              })
            )
          }, 0)
          return
        }

        if (payload.payload.content === 'This message should fail to send') {
          window.setTimeout(() => {
            this.onmessage?.(
              new MessageEvent('message', {
                data: JSON.stringify({
                  frame: payload.frame ?? 9,
                  request_id: payload.request_id,
                  session_id: payload.session_id,
                  event_type: 'error.handler_failed',
                  payload: {
                    code: 'handler_failed',
                    message: 'Mock send failure',
                  },
                }),
              })
            )
          }, 0)
          return
        }

        if (payload.payload.content === 'Show execution process and stream reply') {
          window
            .fetch(`/api/sessions/${payload.session_id}/messages`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                message_id: payload.payload.message_id ?? `msg-${Date.now()}`,
                content: payload.payload.content,
              }),
            })
            .catch(() => {})

          const executionId = `exec-${payload.session_id}`
          const sendSocketMessage = (body: Record<string, unknown>) => {
            this.onmessage?.(
              new MessageEvent('message', {
                data: JSON.stringify(body),
              })
            )
          }

          window.setTimeout(() => {
            sendSocketMessage({
              type: 'event',
              event_type: 'routing.decided',
              execution_id: executionId,
              session_id: payload.session_id,
              data: { route_decision: 'workflow_hotel' },
            })
          }, 0)
          window.setTimeout(() => {
            sendSocketMessage({
              type: 'event',
              event_type: 'node.started',
              execution_id: executionId,
              session_id: payload.session_id,
              data: { node_id: 'start_1', node_type: 'start' },
            })
          }, 5)
          window.setTimeout(() => {
            sendSocketMessage({
              type: 'event',
              event_type: 'node.started',
              execution_id: executionId,
              session_id: payload.session_id,
              data: { node_id: 'answer_1', node_type: 'llm' },
            })
          }, 10)
          window.setTimeout(() => {
            sendSocketMessage({
              type: 'message_delta',
              execution_id: executionId,
              session_id: payload.session_id,
              content: 'First streamed ',
              is_complete: false,
            })
          }, 1200)
          window.setTimeout(() => {
            sendSocketMessage({
              type: 'message_delta',
              execution_id: executionId,
              session_id: payload.session_id,
              content: 'answer.',
              is_complete: true,
            })
          }, 1500)
          window.setTimeout(() => {
            sendSocketMessage({
              type: 'ack',
              request_id: payload.request_id,
              action: 'chat.send',
              status: 'ok',
              data: {
                session_id: payload.session_id,
                execution_id: executionId,
                workflow_code: 'test-workflow',
                workflow_version: 'v1',
                status: 'completed',
              },
            })
          }, 1550)
          return
        }

        if (payload.payload.content === 'This message gets a reply before the socket disconnects') {
          window
            .fetch(`/api/sessions/${payload.session_id}/messages`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                message_id: payload.payload.message_id ?? `msg-${Date.now()}`,
                content: payload.payload.content,
              }),
            })
            .catch(() => {})

          window.setTimeout(() => {
            this.onmessage?.(
              new MessageEvent('message', {
                data: JSON.stringify({
                  type: 'message_delta',
                  execution_id: `exec-${payload.session_id}`,
                  session_id: payload.session_id,
                  content: 'Mock assistant reply',
                  is_complete: true,
                }),
              })
            )
          }, 0)

          window.setTimeout(() => {
            this.readyState = MockWebSocket.CLOSED
            this.onclose?.(new CloseEvent('close'))
          }, 10)
          return
        }

        window
          .fetch(`/api/sessions/${payload.session_id}/messages`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              message_id: payload.payload.message_id ?? `msg-${Date.now()}`,
              content: payload.payload.content,
            }),
          })
          .catch(() => {})

        window.setTimeout(() => {
          const includesCandidateQueue = payload.payload?.content === 'Auto route with recognized intent'
          this.onmessage?.(
            new MessageEvent('message', {
              data: JSON.stringify({
                type: 'ack',
                request_id: payload.request_id,
                action: 'chat.send',
                status: 'ok',
                data: {
                  session_id: payload.session_id,
                  execution_id: `exec-${payload.session_id}`,
                  workflow_code: 'test-workflow',
                  workflow_version: 'v1',
                  status: 'completed',
                  ...(includesCandidateQueue
                    ? {
                        intent_candidate_queue: [
                          {
                            intent_code: 'flight_booking',
                            target_type: 'workflow',
                            target_code: 'workflow-flight',
                            confidence: 0.82,
                            source: 'rag',
                            evidence: '用户提到订机票',
                          },
                        ],
                      }
                    : {}),
                },
              }),
            })
          )
        }, 0)
      }

      close() {
        this.readyState = MockWebSocket.CLOSED
        this.onclose?.(new CloseEvent('close'))
      }

      addEventListener() {}
      removeEventListener() {}
      dispatchEvent() {
        return true
      }
    }

    Object.defineProperty(window, 'WebSocket', {
      writable: true,
      value: MockWebSocket,
    })
  })

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url

    if (pathname === '/api/workflows/published') {
      await route.fulfill({ json: publishedWorkflows })
      return
    }

    if (pathname === '/api/sessions' && request.method() === 'POST') {
      const sessionId = currentSessionIds[Math.min(createSessionCount, currentSessionIds.length - 1)]
      const createdSession = baseSessionDetails[sessionId]
      createSessionCount += 1
      sessionList = [createdSession, ...sessionList.filter((session) => session.id !== createdSession.id)]
      messageStore[sessionId] = messageStore[sessionId] ?? []
      await route.fulfill({ json: createdSession })
      return
    }

    if (pathname === '/api/sessions' && request.method() === 'GET') {
      await route.fulfill({ json: sessionList })
      return
    }

    const sessionMessagesMatch = pathname.match(/^\/api\/sessions\/([^/]+)\/messages$/)
    if (sessionMessagesMatch) {
      const sessionId = sessionMessagesMatch[1]

      if (request.method() === 'POST') {
        const body = request.postDataJSON() as { message_id: string; content: string }
        const timestamp = '2026-04-25T12:00:00.000Z'
        const nextMessage: SessionMessage = {
          id: body.message_id,
          type: 'user',
          content: body.content,
          timestamp,
        }

        messageStore[sessionId] = [...(messageStore[sessionId] ?? []), nextMessage]
        sessionList = sessionList.map((session) =>
          session.id === sessionId
            ? {
                ...session,
                status: 'active',
                lastActivityAt: timestamp,
              }
            : session
        )

        await route.fulfill({ json: nextMessage })
        return
      }

      const responseSnapshot = [...(messageStore[sessionId] ?? [])]
      const delayMs = delayedSessionMessageLoads[sessionId] ?? 0
      if (delayMs > 0) {
        await new Promise((resolve) => setTimeout(resolve, delayMs))
      }
      await route.fulfill({ json: responseSnapshot })
      return
    }

    const sessionDetailMatch = pathname.match(/^\/api\/sessions\/([^/]+)$/)
    if (sessionDetailMatch && request.method() === 'GET') {
      const sessionId = sessionDetailMatch[1]
      const session = sessionList.find((item) => item.id === sessionId) ?? baseSessionDetails[sessionId as keyof typeof baseSessionDetails]
      await route.fulfill({ json: session })
      return
    }

    if (sessionDetailMatch && request.method() === 'DELETE') {
      const sessionId = sessionDetailMatch[1]
      sessionList = sessionList.filter((session) => session.id !== sessionId)
      delete messageStore[sessionId]
      await route.fulfill({ status: 200, body: '' })
      return
    }

    if (pathname === '/api/executions' && request.method() === 'GET') {
      await route.fulfill({ json: [] })
      return
    }

    if (pathname.match(/^\/api\/executions\/([^/]+)\/replay$/)) {
      await route.fulfill({
        json: {
          execution_id: 'exec-history-1',
          status: 'failed',
          node_logs: [],
          event_stream: [],
        },
      })
      return
    }

    await route.fulfill({ status: 404, json: { message: `Unhandled route: ${pathname}` } })
  })
})

test.describe('session history panel', () => {
  test('requires choosing a workflow mode before sending', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByTestId('chat-workflow-select')).toHaveValue('')
    await expect(page.getByTestId('chat-workflow-target')).toContainText('请选择工作流模式')
    await expect(page.getByTestId('chat-input')).toBeDisabled()
    await expect(page.getByTestId('chat-send')).toBeDisabled()
  })

  test('auto-route mode sends without workflow fields', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await expect(page.getByTestId('chat-workflow-target')).toContainText('无固定工作流 / 由路由自动决定')
    await expect(page.getByTestId('chat-input')).toBeEnabled()

    await page.getByTestId('chat-input').fill('Auto route message')
    await page.getByTestId('chat-send').click()

    await expect(page.getByText('Auto route message', { exact: true }).first()).toBeVisible()
    await page.waitForFunction(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }
      return (win.__mockWsPayloads ?? []).some(
        (payload) => payload.action === 'chat.send' || payload.event_type === 'message.text'
      )
    })

    const wsPayloads = await page.evaluate(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>>; __mockWsUrls?: string[] }
      return {
        payloads: win.__mockWsPayloads ?? [],
        urls: win.__mockWsUrls ?? [],
      }
    })
    const chatSend = wsPayloads.payloads.find(isChatSendFrame) as
      | Record<string, unknown>
      | undefined

    expect(chatSend).toBeTruthy()
    expect(chatSend?.payload && typeof chatSend.payload === 'object' ? chatSend.payload : {}).not.toHaveProperty(
      'workflow_code'
    )
    expect(chatSend?.payload && typeof chatSend.payload === 'object' ? chatSend.payload : {}).not.toHaveProperty(
      'workflow_version'
    )
    expect(wsPayloads.urls.some((url) => url.includes('workflow_code='))).toBeFalsy()
  })

  test('recognized intent starts workflow without candidate confirmation popup', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill('Auto route with recognized intent')
    await page.getByTestId('chat-send').click()

    await expect(page.getByText('Auto route with recognized intent', { exact: true }).first()).toBeVisible()
    await page.waitForFunction(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }
      return (win.__mockWsPayloads ?? []).some(
        (payload) => payload.action === 'chat.send' || payload.event_type === 'message.text'
      )
    })

    await expect(page.getByText('候选意图确认')).toHaveCount(0)
    await expect(page.getByText('继续办理')).toHaveCount(0)
  })

  test('fixed workflow mode sends workflow fields and rebuilds websocket URL', async ({ page }) => {
    publishedWorkflows = [
      {
        id: 11,
        workflowCode: 'workflow-hotel',
        name: '酒店预订',
        status: 'published',
        currentVersion: '1.0.0',
      },
      {
        id: 12,
        workflowCode: 'workflow-travel',
        name: '旅行助手',
        status: 'published',
        currentVersion: '2.0.0',
      },
    ]

    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('workflow-hotel')
    await expect(page.getByTestId('chat-workflow-target')).toContainText('workflow-hotel / 1.0.0')

    await page.getByTestId('chat-workflow-select').selectOption('workflow-travel')
    await expect(page.getByTestId('chat-workflow-target')).toContainText('workflow-travel / 2.0.0')

    await page.getByTestId('chat-input').fill('Fixed workflow message')
    await page.getByTestId('chat-send').click()

    await expect(page.getByText('Fixed workflow message', { exact: true }).first()).toBeVisible()
    await page.waitForFunction(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }
      return (win.__mockWsPayloads ?? []).some(
        (payload) => payload.action === 'chat.send' || payload.event_type === 'message.text'
      )
    })

    const wsPayloads = await page.evaluate(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>>; __mockWsUrls?: string[] }
      return {
        payloads: win.__mockWsPayloads ?? [],
        urls: win.__mockWsUrls ?? [],
      }
    })
    const chatSend = wsPayloads.payloads.find(isChatSendFrame) as
      | Record<string, unknown>
      | undefined
    const chatSendPayload = (chatSend?.payload as Record<string, unknown>) ?? {}

    expect(chatSendPayload.workflow_code).toBe('workflow-travel')
    expect(chatSendPayload.workflow_version).toBe('2.0.0')
    expect(wsPayloads.urls.some((url) => url.includes('workflow_code=workflow-travel'))).toBeTruthy()
    expect(wsPayloads.urls.some((url) => url.includes('workflow_version=2.0.0'))).toBeTruthy()
  })

  test('ignores stale socket close while a replacement socket is connecting before send', async ({ page }) => {
    publishedWorkflows = [
      {
        id: 11,
        workflowCode: 'workflow-hotel',
        name: '酒店预订',
        status: 'published',
        currentVersion: '1.0.0',
      },
    ]

    await page.addInitScript(() => {
      const sockets: Array<{ onclose: ((event: CloseEvent) => void) | null; readyState: number }> = []
      const OriginalWebSocket = window.WebSocket
      class TrackingWebSocket extends OriginalWebSocket {
        constructor(url: string | URL, protocols?: string | string[]) {
          super(url, protocols as string | string[] | undefined)
          sockets.push(this as unknown as { onclose: ((event: CloseEvent) => void) | null; readyState: number })
          const value = String(url)
          const forceStaleClose = () => {
            const stale = sockets[0]
            if (!stale) return
            stale.readyState = 3
            stale.onclose?.(new CloseEvent('close'))
          }
          ;(window as Window & { __forceStaleClose?: () => void }).__forceStaleClose = forceStaleClose
        }
      }
      Object.defineProperty(window, 'WebSocket', {
        writable: true,
        value: TrackingWebSocket,
      })
    })

    await page.goto('/')
    await page.getByTestId('chat-workflow-select').selectOption('workflow-hotel')
    await page.waitForFunction(() => {
      const win = window as Window & { __mockWsUrls?: string[] }
      return (win.__mockWsUrls ?? []).length >= 2
    })
    await page.evaluate(() => {
      const win = window as Window & { __forceStaleClose?: () => void }
      win.__forceStaleClose?.()
    })
    await page.getByTestId('chat-input').fill('Send while replacing socket should still reach gateway')
    await page.getByTestId('chat-send').click()

    await page.waitForFunction(() => {
      const win = window as Window & { __mockWsPayloads?: Array<Record<string, unknown>> }
      return (win.__mockWsPayloads ?? []).some(
        (payload) => payload.action === 'chat.send' || payload.event_type === 'message.text'
      )
    }, undefined, { timeout: 10_000 })
    await expect(page.getByText('消息发送失败，请稍后再试。')).toHaveCount(0)
  })

  test('switching from fixed workflow to auto-route starts a fresh session', async ({ page }) => {
    publishedWorkflows = [
      {
        id: 11,
        workflowCode: 'workflow-flight',
        name: '预定机票',
        status: 'published',
        currentVersion: '1.0.0',
      },
    ]

    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('workflow-flight')
    await page.getByTestId('chat-input').fill('Fixed workflow message')
    await page.getByTestId('chat-send').click()
    await expect(page.getByText('Fixed workflow message', { exact: true }).first()).toBeVisible()
    const fixedSessionMeta = await page.getByTestId('current-session-meta').textContent()

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')

    await expect(page.getByTestId('current-session-meta')).not.toContainText(fixedSessionMeta || '')
    await expect(page.getByTestId('message-list').getByText('Fixed workflow message', { exact: true })).toHaveCount(0)
    await expect(page.getByTestId('chat-workflow-target')).toContainText('无固定工作流 / 由路由自动决定')
  })

  test('does not show a fresh empty current session in the history list', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-2')
    await expect(panel.getByTestId('session-history-item-session-current-2')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-empty-1')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-empty-2')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-history-1')).toHaveCount(1)
    await expect(panel.getByTestId('session-history-item-session-history-2')).toHaveCount(1)
  })

  test('does not retain a failed-send local message in history after creating a new session', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill(failedSendContent)
    await page.getByTestId('chat-send').click()

    await expect(page.getByTestId('message-error').getByText('消息发送失败：Mock send failure', { exact: true })).toBeVisible()
    await expect(page.getByText('消息发送失败，请稍后再试。', { exact: true })).toHaveCount(0)

    await page.getByTestId('chat-new-session').click()

    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-3')
    await expect(panel.getByTestId('session-history-item-session-current-3')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-current-2')).toHaveCount(0)
    await expect(panel).not.toContainText(failedSendContent)
  })

  test('keeps the previous session in history after a real user message and new session', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill('Keep this session in history')
    await page.getByTestId('chat-send').click()

    await expect(panel).toContainText('Keep this session in history')

    await page.getByTestId('chat-new-session').click()

    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-3')
    await expect(panel.getByTestId('session-history-item-session-current-3')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-current-2')).toHaveCount(1)
    await expect(panel.getByTestId('session-history-item-session-current-2')).toContainText(
      'Keep this session in his...'
    )
  })


  test('renders user and assistant labels without question marks', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill('Label sanity check')
    await page.getByTestId('chat-send').click()

    const messageList = page.getByTestId('message-list')
    await expect(messageList.getByTestId('message-user').first()).toContainText('\u7528\u6237')
    await expect(messageList.getByTestId('message-ai').first()).toContainText('\u673a\u5668\u4eba')
    await expect(messageList.getByText('???')).toHaveCount(0)
  })

  test('shows animated execution process while streaming and removes it after answer', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill('Show execution process and stream reply')
    await page.getByTestId('chat-send').click()

    const messageList = page.getByTestId('message-list')
    const liveProcessPanel = messageList.getByTestId('execution-process-panel')
    await expect(liveProcessPanel).toBeVisible()
    await expect(liveProcessPanel).toContainText('\u6b63\u5728\u8bc6\u522b\u610f\u56fe')
    await expect(liveProcessPanel).toContainText('\u6b63\u5728\u68c0\u67e5\u5f00\u59cb\u8282\u70b9\u53d8\u91cf')
    await expect(liveProcessPanel).toContainText('\u6b63\u5728\u7b49\u5f85\u6a21\u578b\u751f\u6210\u56de\u590d')
    await expect(messageList.getByTestId('execution-process-active')).toContainText('\u6b63\u5728\u7b49\u5f85\u6a21\u578b\u751f\u6210\u56de\u590d')

    const assistantMessage = messageList.getByTestId('message-ai').filter({ hasText: 'First streamed answer.' })
    await expect(assistantMessage).toHaveCount(1)
    await expect(assistantMessage).toContainText('First streamed answer.')
    await expect(assistantMessage.getByText('\u6b63\u5728\u8bc6\u522b\u610f\u56fe')).toHaveCount(0)
    await expect(assistantMessage.getByTestId('execution-process-panel')).toHaveCount(0)
    await expect(assistantMessage.getByTestId('execution-process-toggle')).toHaveCount(0)
    await expect(assistantMessage.getByTestId('execution-process-active')).toHaveCount(0)
    await expect(messageList.getByTestId('message-ai')).toHaveCount(1)
    await expect(messageList.getByTestId('message-ai').filter({ hasText: '1 \u6b65' })).toHaveCount(0)
  })

  test('keeps user messages visible when the initial empty history request resolves after send', async ({ page }) => {
    delayedSessionMessageLoads['session-current-1'] = 800
    delayedSessionMessageLoads['session-current-2'] = 800
    const content = 'Race condition should not hide this user message'

    await page.goto('/')

    await expect(page.getByTestId('chat-input')).toBeVisible()
    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill(content)
    await page.getByTestId('chat-send').click()

    await expect(page.getByText(content, { exact: true })).toHaveCount(2)
    await page.waitForTimeout(1000)
    await expect(page.getByText(content, { exact: true })).toHaveCount(2)
  })

  test('keeps the user message visible if a reply arrives before the websocket send promise fails', async ({ page }) => {
    await page.goto('/')

    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill(disconnectAfterReplyContent)
    await page.getByTestId('chat-send').click()

    await expect(page.getByText('Mock assistant reply', { exact: true })).toHaveCount(2)
    await page.waitForTimeout(200)
    await expect(page.getByText(disconnectAfterReplyContent, { exact: true })).toHaveCount(2)
  })

  test('rebuilds history from backend sessions on refresh-style initialization', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill(failedSendContent)
    await page.getByTestId('chat-send').click()
    await expect(panel).toContainText('消息发送失败')

    await page.reload()

    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-3')
    await expect(panel.getByTestId('session-history-item-session-current-3')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-current-2')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-history-1')).toHaveCount(1)
    await expect(panel.getByTestId('session-history-item-session-history-2')).toHaveCount(1)
    await expect(panel).not.toContainText(failedSendContent)
  })

  test('deletes history items from the list', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await expect(panel.getByTestId('session-history-item-session-history-1')).toHaveCount(1)

    await panel.getByTestId('session-history-delete-session-history-1').click()

    await expect(panel.getByTestId('session-history-item-session-history-1')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-history-2')).toHaveCount(1)
  })

  test('deleting the current session falls back to a fresh empty session', async ({ page }) => {
    await page.goto('/')

    const panel = page.getByTestId('session-replay-panel')
    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-2')
    await page.getByTestId('chat-workflow-select').selectOption('__AUTO_ROUTE__')
    await page.getByTestId('chat-input').fill('Current session should be deleted')
    await page.getByTestId('chat-send').click()

    await panel.getByTestId('session-history-delete-session-current-2').click()

    await expect(panel.getByTestId('session-history-item-session-current-3')).toHaveCount(0)
    await expect(panel.getByTestId('session-history-item-session-current-2')).toHaveCount(0)
    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-3')
    await expect(panel).not.toContainText('Current session should be deleted')
  })
})
