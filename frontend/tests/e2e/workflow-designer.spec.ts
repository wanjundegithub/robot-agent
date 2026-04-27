import { expect, test } from '@playwright/test'

type PublishedWorkflow = {
  id: number
  workflowCode: string
  name: string
  status: string
  currentVersion: string
  createdBy: string
}

test.describe('workflow designer v2 contract', () => {
  test('shows chinese workflow navigation and property panels', async ({ page }) => {
    let nextSessionIndex = 1

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await expect(page.getByTestId('workflow-graph-nav')).toBeVisible()
    await expect(page.getByTestId('workflow-properties-panel')).toBeVisible()
    await expect(page.getByTestId('workflow-breadcrumb-main')).toContainText('主流程')
    await expect(page.getByTestId('workflow-current-graph')).toContainText('主流程')
  })

  test('uses a full-width workspace with a dedicated versions sidebar and no draft action bar', async ({ page }) => {
    let nextSessionIndex = 1

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await expect(page.getByTestId('workflow-page-layout')).toBeVisible()
    await expect(page.getByTestId('workflow-page-main')).toBeVisible()
    await expect(page.getByTestId('workflow-version-panel')).toBeVisible()
    await expect(page.getByTestId('workflow-name-input')).toBeVisible()
    await expect(page.getByTestId('workflow-publish')).toBeVisible()
    await expect(page.getByTestId('workflow-save-draft')).toHaveCount(0)
    await expect(page.getByTestId('workflow-validate')).toHaveCount(0)

    const mainBox = await page.getByTestId('workflow-page-main').boundingBox()
    const sidebarBox = await page.getByTestId('workflow-version-panel').boundingBox()

    expect(mainBox).not.toBeNull()
    expect(sidebarBox).not.toBeNull()
    expect((sidebarBox as { x: number }).x).toBeGreaterThan((mainBox as { x: number }).x)
  })

  test('restricts main graph nodes and exposes start/message/function/end inside subflows', async ({ page }) => {
    let nextSessionIndex = 1

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await expect(page.getByTestId('workflow-add-node-coordinator')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-sub_agent')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-start')).toHaveCount(0)
    await expect(page.getByTestId('workflow-add-node-message')).toHaveCount(0)
    await expect(page.getByTestId('workflow-add-node-end')).toHaveCount(0)

    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByTestId('workflow-subgraph-id-input').fill('subgraph_a')
    await page.getByTestId('workflow-open-subgraph').click()

    await expect(page.getByTestId('workflow-breadcrumb-subgraph_a')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-start')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-message')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-function')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-end')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-coordinator')).toHaveCount(0)
  })

  test('deletes selected nodes and loads capability groups/items from real APIs', async ({ page }) => {
    let nextSessionIndex = 1
    let itemsRequestCount = 0

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({
          json: [
            {
              id: 7,
              groupName: 'Booking APIs',
              status: 'PUBLISHED',
              capabilityCount: 1,
            },
          ],
        })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        itemsRequestCount += 1
        await route.fulfill({
          json: [
            {
              id: 70,
              groupId: 7,
              capabilityCode: 'search_flights',
              capabilityName: 'Search Flights',
              capabilityType: 'API',
              status: 'PUBLISHED',
              publishedVersion: 'v20260426010101',
            },
          ],
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await expect(page.locator('.react-flow__node')).toHaveCount(1)
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await page.getByTestId('workflow-delete-node').click()
    await expect(page.locator('.react-flow__node')).toHaveCount(1)

    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByTestId('workflow-subgraph-id-input').fill('subgraph_capability')
    await page.getByTestId('workflow-open-subgraph').click()
    await page.getByTestId('workflow-add-node-tool').click()

    await page.getByTestId('workflow-capability-group-select').selectOption('7')
    await expect.poll(() => itemsRequestCount).toBe(1)
    await expect(page.getByTestId('workflow-capability-code-select')).toContainText('Search Flights')
    await expect(page.getByTestId('workflow-capability-version-select')).toHaveCount(0)
  })

  test('saves definition with graphs object map in v2 schema', async ({ page }) => {
    let draftPayload: Record<string, unknown> | null = null
    let nextSessionIndex = 1
    const publishedWorkflows: PublishedWorkflow[] = []

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: publishedWorkflows })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/validate-draft') && request.method() === 'POST') {
        await route.fulfill({ json: { valid: true, issues: [] } })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/drafts') && request.method() === 'POST') {
        draftPayload = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            id: 100 + nextSessionIndex,
            workflowId: 101,
            workflowCode: String((draftPayload || {}).workflow_code || `workflow_${Date.now()}`),
            workflowName: String((draftPayload || {}).workflow_name || 'E2E Workflow'),
            version: String((draftPayload || {}).version || 'draft'),
            status: 'DRAFT',
          },
        })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/publish') && request.method() === 'POST') {
        const workflowCode = pathname.split('/')[3] || `workflow_${Date.now()}`
        const body = request.postDataJSON() as { version?: string }
        publishedWorkflows[0] = {
          id: 201,
          workflowCode,
          name: 'Published Workflow',
          status: 'PUBLISHED',
          currentVersion: body.version || 'v20260426000000',
          createdBy: 'demo-user',
        }
        await route.fulfill({ json: publishedWorkflows[0] })
        return
      }

      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await page.getByTestId('workflow-name-input').fill('Nested Graph Contract')
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByTestId('workflow-subgraph-id-input').fill('subgraph_a')
    await page.getByTestId('workflow-open-subgraph').click()
    await expect(page.getByTestId('workflow-breadcrumb-subgraph_a')).toBeVisible()
    await page.getByTestId('workflow-breadcrumb-main').click()
    await page.getByTestId('workflow-publish').click()

    await expect.poll(() => draftPayload).not.toBeNull()
    const rawDefinition = String((draftPayload || {}).definition || '{}')
    const definition = JSON.parse(rawDefinition) as Record<string, unknown>
    const graphs = (definition.graphs || {}) as Record<string, unknown>

    expect(definition.schema_version).toBe('workflow-designer/v2')
    expect(definition.main_graph_id).toBe('main')
    expect(Array.isArray(graphs)).toBe(false)
    expect(graphs.main).toBeTruthy()
    expect(graphs.subgraph_a).toBeTruthy()
    const mainGraph = graphs.main as Record<string, unknown>
    const subGraph = graphs.subgraph_a as Record<string, unknown>
    expect(mainGraph.graph_id).toBe('main')
    expect(mainGraph.graph_type).toBe('MAIN')
    expect(mainGraph.entry_node_id).toBeTruthy()
    expect(Array.isArray(mainGraph.edges)).toBe(true)
    expect(subGraph.graph_id).toBe('subgraph_a')
    expect(subGraph.graph_type).toBe('SUBGRAPH')
    expect(Array.isArray(subGraph.edges)).toBe(true)
    expect(mainGraph).not.toHaveProperty('transitions')
    expect(mainGraph).not.toHaveProperty('entry')

    const rawConfig = String((draftPayload || {}).config || '{}')
    const config = JSON.parse(rawConfig) as Record<string, unknown>
    expect(config.main_graph_id).toBe('main')
    expect(config).not.toHaveProperty('graphs')
  })

  test('links published workflow code/version into chat page', async ({ page }) => {
    let publishRequest: { workflowCode: string; version: string } | null = null
    let nextSessionIndex = 1
    const publishedWorkflows: PublishedWorkflow[] = []

    await page.addInitScript(() => {
      class MockWebSocket {
        static CONNECTING = 0
        static OPEN = 1
        static CLOSING = 2
        static CLOSED = 3
        readyState = MockWebSocket.CONNECTING
        onopen: ((event: Event) => void) | null = null
        onmessage: ((event: MessageEvent) => void) | null = null
        onerror: ((event: Event) => void) | null = null
        onclose: ((event: CloseEvent) => void) | null = null
        constructor() {
          window.setTimeout(() => {
            this.readyState = MockWebSocket.OPEN
            this.onopen?.(new Event('open'))
          }, 0)
        }
        send() {}
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

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: publishedWorkflows })
        return
      }
      if (pathname === '/api/workflows' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/validate-draft') && request.method() === 'POST') {
        await route.fulfill({ json: { valid: true, issues: [] } })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/drafts') && request.method() === 'POST') {
        const draftPayload = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            id: 301,
            workflowId: 302,
            workflowCode: String(draftPayload.workflow_code || `workflow_${Date.now()}`),
            workflowName: String(draftPayload.workflow_name || 'Publish Ready'),
            version: String(draftPayload.version || 'draft'),
            status: 'DRAFT',
          },
        })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/publish') && request.method() === 'POST') {
        const workflowCode = pathname.split('/')[3] || `workflow_${Date.now()}`
        const body = request.postDataJSON() as { version?: string }
        publishRequest = {
          workflowCode,
          version: body.version || 'v20260426000000',
        }
        publishedWorkflows[0] = {
          id: 401,
          workflowCode,
          name: 'Publish Ready',
          status: 'PUBLISHED',
          currentVersion: publishRequest.version,
          createdBy: 'demo-user',
        }
        await route.fulfill({ json: publishedWorkflows[0] })
        return
      }

      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/capabilities/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      if (pathname === '/api/sessions' && request.method() === 'POST') {
        const createdId = `session-e2e-${nextSessionIndex}`
        nextSessionIndex += 1
        await route.fulfill({
          json: {
            id: createdId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/sessions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && pathname.endsWith('/messages') && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname.startsWith('/api/sessions/') && request.method() === 'GET') {
        const sessionId = pathname.split('/').pop() || 'session-e2e-1'
        await route.fulfill({
          json: {
            id: sessionId,
            workspaceId: 1,
            userId: 'demo-user',
            status: 'active',
            currentExecutionId: null,
          },
        })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')
    await page.getByTestId('workflow-name-input').fill('Publish And Link')
    await page.getByTestId('workflow-publish').click()

    await expect.poll(() => publishRequest).not.toBeNull()
    await page.getByTestId('workflow-link-chat').click()
    await expect(page).toHaveURL(/#chat$/)

    const expected = publishRequest as { workflowCode: string; version: string }
    await expect(page.getByTestId('chat-workflow-target')).toContainText(expected.workflowCode)
    await expect(page.getByTestId('chat-workflow-target')).toContainText(expected.version)
  })
})
