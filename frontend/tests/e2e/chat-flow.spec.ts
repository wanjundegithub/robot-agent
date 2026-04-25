import { expect, test } from '@playwright/test'

const currentSessionIds = ['session-current-1', 'session-current-2', 'session-current-3'] as const
let createSessionCount = 0

const sessionDetails = {
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
} as const

const sessionMessages = {
  'session-current-1': [],
  'session-current-2': [],
  'session-history-1': [
    {
      id: 'msg-history-1-user',
      type: 'user',
      content: '历史用户提问',
      timestamp: '2026-04-20T09:00:00',
      executionId: 'exec-history-1',
    },
    {
      id: 'msg-history-1-ai',
      type: 'error',
      content: '历史失败响应',
      timestamp: '2026-04-20T09:01:00',
      executionId: 'exec-history-1',
    },
  ],
  'session-history-2': [
    {
      id: 'msg-history-2-user',
      type: 'user',
      content: '另一条历史请求',
      timestamp: '2026-04-19T08:00:00',
      executionId: 'exec-history-2',
    },
  ],
} as const

const sessionExecutions = {
  'session-current-1': [],
  'session-current-2': [],
  'session-history-1': [
    {
      execution_id: 'exec-history-1',
      session_id: 'session-history-1',
      workflow_code: 'order_query',
      workflow_version: 'v1',
      status: 'failed',
      current_node_id: 'tool_1',
      variables: null,
      error: '历史失败响应',
    },
  ],
  'session-history-2': [
    {
      execution_id: 'exec-history-2',
      session_id: 'session-history-2',
      workflow_code: 'faq',
      workflow_version: 'v3',
      status: 'completed',
      current_node_id: 'end',
      variables: null,
      error: null,
    },
  ],
} as const

const replayDetails = {
  'exec-history-1': {
    execution_id: 'exec-history-1',
    workflow_code: 'order_query',
    workflow_version: 'v1',
    session_id: 'session-history-1',
    status: 'failed',
    input_variables: { user_message: '历史用户提问' },
    output_variables: {},
    variables: {},
    metrics: {},
    node_logs: [
      {
        node_id: 'tool_1',
        node_type: 'tool',
        status: 'failed',
        started_at: '2026-04-20T09:00:05',
        completed_at: '2026-04-20T09:00:10',
        input: {},
        output: {},
        metrics: {},
        error: '工具超时',
      },
    ],
    event_stream: [
      {
        event_type: 'execution.started',
        execution_id: 'exec-history-1',
        workflow_code: 'order_query',
        workflow_version: 'v1',
      },
      {
        event_type: 'execution.failed',
        execution_id: 'exec-history-1',
      },
    ],
  },
  'exec-history-2': {
    execution_id: 'exec-history-2',
    workflow_code: 'faq',
    workflow_version: 'v3',
    session_id: 'session-history-2',
    status: 'completed',
    input_variables: { user_message: '另一条历史请求' },
    output_variables: { answer: '已完成' },
    variables: {},
    metrics: {},
    node_logs: [],
    event_stream: [
      {
        event_type: 'execution.completed',
        execution_id: 'exec-history-2',
      },
    ],
  },
} as const

test.beforeEach(async ({ page }) => {
  createSessionCount = 0

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
        window.setTimeout(() => {
          this.readyState = MockWebSocket.OPEN
          this.onopen?.(new Event('open'))
        }, 0)
      }

      send(_data: string) {}

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
    const { pathname, searchParams } = url

    if (pathname === '/api/workflows/published') {
      await route.fulfill({ json: [] })
      return
    }

    if (pathname === '/api/sessions' && request.method() === 'POST') {
      const sessionId = currentSessionIds[Math.min(createSessionCount, currentSessionIds.length - 1)]
      createSessionCount += 1
      await route.fulfill({ json: sessionDetails[sessionId] })
      return
    }

    if (pathname === '/api/sessions' && request.method() === 'GET') {
      await route.fulfill({
        json: [sessionDetails['session-history-1'], sessionDetails['session-history-2']],
      })
      return
    }

    const sessionDetailMatch = pathname.match(/^\/api\/sessions\/([^/]+)$/)
    if (sessionDetailMatch) {
      const sessionId = sessionDetailMatch[1] as keyof typeof sessionDetails
      await route.fulfill({ json: sessionDetails[sessionId] })
      return
    }

    const sessionMessagesMatch = pathname.match(/^\/api\/sessions\/([^/]+)\/messages$/)
    if (sessionMessagesMatch) {
      const sessionId = sessionMessagesMatch[1] as keyof typeof sessionMessages
      await route.fulfill({ json: sessionMessages[sessionId] ?? [] })
      return
    }

    if (pathname === '/api/executions' && request.method() === 'GET') {
      const sessionId = searchParams.get('sessionId') as keyof typeof sessionExecutions
      await route.fulfill({ json: sessionExecutions[sessionId] ?? [] })
      return
    }

    const replayMatch = pathname.match(/^\/api\/executions\/([^/]+)\/replay$/)
    if (replayMatch) {
      const executionId = replayMatch[1] as keyof typeof replayDetails
      await route.fulfill({ json: replayDetails[executionId] })
      return
    }

    await route.fulfill({ status: 404, json: { message: `Unhandled route: ${pathname}` } })
  })
})

test.describe('chat and session replay', () => {
  test('creates a fresh session on load and keeps history replay available after creating another session', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('heading', { name: '机器人代理控制台' })).toBeVisible()
    await expect(page.getByRole('button', { name: '聊天' })).toBeVisible()
    await expect(page.getByRole('button', { name: '工作流' })).toBeVisible()
    await expect(page.getByRole('button', { name: '执行' })).toBeVisible()
    await expect(page.getByRole('button', { name: '模型' })).toBeVisible()
    await expect(page.getByText('聊天控制台')).toBeVisible()
    await expect(page.getByText('选择已发布工作流')).toBeVisible()
    await expect(page.getByText('会话回放')).toBeVisible()
    await expect(page.getByTestId('chat-new-session')).toBeVisible()
    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-2')
    await expect(page.getByLabel('会话')).toContainText('session-current-2')
    await expect(page.getByLabel('会话')).toContainText('session-history-1')
    await expect(page.getByLabel('会话')).toContainText('session-history-2')

    await expect(page.getByTestId('session-replay-panel')).toBeVisible()
    await expect(page.getByTestId('session-history-item-session-history-1')).toBeVisible()

    await expect(page.getByText('历史用户提问')).toBeVisible()
    await expect(page.getByText('历史失败响应')).toBeVisible()
    await expect(page.getByTestId('execution-history-item-exec-history-1')).toBeVisible()
    await expect(page.getByText('执行失败')).toBeVisible()
    await expect(page.getByText('工具超时')).toBeVisible()

    await page.getByTestId('chat-new-session').click()

    await expect(page.getByTestId('current-session-meta')).toContainText('session-current-3')
    await expect(page.getByLabel('会话')).toContainText('session-current-3')
    await expect(page.getByLabel('会话')).toContainText('session-history-1')
    await expect(page.getByTestId('session-history-item-session-history-1')).toBeVisible()
    await expect(page.getByText('历史用户提问')).toBeVisible()
  })
})
