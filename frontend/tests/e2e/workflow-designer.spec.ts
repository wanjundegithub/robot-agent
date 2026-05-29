import { expect, test, type Page } from '@playwright/test'

type PublishedWorkflow = {
  id: number
  workflowCode: string
  name: string
  description?: string
  status: string
  currentVersion: string
  createdBy: string
}

type WorkflowVersionFixture = {
  id: number
  workflowId: number
  workflowCode: string
  workflowName: string
  workflowDescription?: string
  version: string
  status: string
  definition: string
  config: string
  editorMeta: string
}

const openNewWorkflowEditor = async (page: Page) => {
  await page.goto('/#workflow')
  await expect(page.getByTestId('workflow-list-page')).toBeVisible()
  await page.getByTestId('workflow-new-version').click()
  await expect(page.getByTestId('workflow-graph-nav')).toBeVisible()
}

const createPublishedWorkflow = (index: number): PublishedWorkflow => ({
  id: index,
  workflowCode: `workflow_${index}`,
  name: `已发布工作流 ${index}`,
  description: `已发布工作流 ${index} 描述`,
  status: 'PUBLISHED',
  currentVersion: `v20260430000${index}`,
  createdBy: 'demo-user',
})

const createWorkflowVersionFixture = (workflow: PublishedWorkflow): WorkflowVersionFixture => ({
  id: workflow.id * 10,
  workflowId: workflow.id,
  workflowCode: workflow.workflowCode,
  workflowName: workflow.name,
  workflowDescription: workflow.description,
  version: workflow.currentVersion,
  status: 'PUBLISHED',
  definition: JSON.stringify({
    schema_version: 'workflow-designer/v2',
    workflow_code: workflow.workflowCode,
    workflow_name: workflow.name,
    workflow_description: workflow.description,
    workflow_version: workflow.currentVersion,
    main_graph_id: 'main',
    graphs: {
      main: {
        graph_id: 'main',
        graph_type: 'MAIN',
        graph_name: '主流程',
        graph_description: '主流程描述',
        entry_node_id: 'coordinator_main',
        nodes: {
          coordinator_main: {
            id: 'coordinator_main',
            type: 'coordinator',
            name: '协调节点',
            description: '协调节点描述',
            config: {
              prompt: '协调节点描述',
              description: '协调节点描述',
            },
          },
          sub_agent_main: {
            id: 'sub_agent_main',
            type: 'sub_agent',
            name: '子流程名称',
            description: '子代理节点描述',
            config: {
              prompt: '子代理节点描述',
              description: '子代理节点描述',
              subgraph_id: 'subgraph_saved',
            },
          },
        },
        edges: [{ edge_id: 'e1', source_node_id: 'coordinator_main', target_node_id: 'sub_agent_main' }],
      },
      subgraph_saved: {
        graph_id: 'subgraph_saved',
        graph_type: 'SUBGRAPH',
        graph_name: '子流程名称',
        graph_description: '子流程描述',
        entry_node_id: 'start_sub',
        nodes: {
          start_sub: {
            id: 'start_sub',
            type: 'start',
            name: '开始节点',
            config: { prompt: '开始' },
          },
          end_sub: {
            id: 'end_sub',
            type: 'end',
            name: '结束节点',
            config: { prompt: '结束', output_format: {} },
          },
        },
        edges: [{ edge_id: 's1', source_node_id: 'start_sub', target_node_id: 'end_sub' }],
      },
    },
    variables: {
      global: [],
      temporary: [],
    },
    model_bindings: {
      routing_model_code: 'intent-router',
      llm_defaults: {
        model_code: 'general-chat',
      },
    },
    editor_meta: {
      current_graph_id: 'main',
      graph_order: ['main'],
    },
  }),
  config: JSON.stringify({
    schema_version: 'workflow-designer/v2',
    main_graph_id: 'main',
    variable_registry: {
      global: [],
      temporary: [],
    },
    model_bindings: {
      routing_model_code: 'intent-router',
      llm_defaults: {
        model_code: 'general-chat',
      },
    },
  }),
  editorMeta: JSON.stringify({
    current_graph_id: 'main',
    graph_order: ['main', 'subgraph_saved'],
  }),
})

const mockEmptyWorkflowEditorApis = async (page: Page) => {
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
}

test.describe('workflow designer v2 contract', () => {
  test('shows published workflow list by default with pagination and new entry', async ({ page }) => {
    let nextSessionIndex = 1
    const publishedWorkflows = [
      ...Array.from({ length: 12 }, (_, index) => createPublishedWorkflow(index + 1)),
      { ...createPublishedWorkflow(101), workflowCode: 'cap_workflow', name: 'Capability Workflow' },
      { ...createPublishedWorkflow(102), workflowCode: 'flight_booking', name: 'Flight Booking', createdBy: 'system' },
      { ...createPublishedWorkflow(103), workflowCode: 'workflow_1776609829026', name: 'test-demo' },
      { ...createPublishedWorkflow(104), workflowCode: 'workflow_20260430120000', name: '真实业务工作流' },
    ]

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: publishedWorkflows })
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
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await expect(page.getByTestId('workflow-list-page')).toBeVisible()
    await expect(page.getByTestId('workflow-graph-nav')).toHaveCount(0)
    await expect(page.getByTestId('workflow-list-row')).toHaveCount(10)
    await expect(page.getByTestId('workflow-list-page-summary')).toContainText('第 1 / 2 页')
    await expect(page.getByTestId('workflow-list-page-summary')).toContainText('共 13 个')
    await expect(page.getByTestId('workflow-list-row-cap_workflow')).toHaveCount(0)
    await expect(page.getByTestId('workflow-list-row-flight_booking')).toHaveCount(0)
    await expect(page.getByTestId('workflow-list-row-workflow_1776609829026')).toHaveCount(0)
    await expect(page.getByTestId('workflow-list-row-workflow_1')).toContainText('已发布工作流 1')
    await expect(page.getByTestId('workflow-list-row-workflow_1')).toContainText('workflow_1')
    await expect(page.getByTestId('workflow-list-row-workflow_1')).toContainText('v202604300001')

    const listBox = await page.getByTestId('workflow-list-page').boundingBox()
    const firstRowBox = await page.getByTestId('workflow-list-row-workflow_1').boundingBox()
    const pagerBox = await page.getByTestId('workflow-list-page-summary').boundingBox()
    expect(listBox?.width).toBeGreaterThan(1100)
    expect(pagerBox?.y).toBeGreaterThan(firstRowBox?.y || 0)

    await page.getByTestId('workflow-list-next').click()
    await expect(page.getByTestId('workflow-list-page-summary')).toContainText('第 2 / 2 页')
    await expect(page.getByTestId('workflow-list-row-workflow_11')).toBeVisible()
    await expect(page.getByTestId('workflow-list-row-workflow_20260430120000')).toBeVisible()
    await expect(page.getByTestId('workflow-list-row-workflow_1')).toHaveCount(0)

    await page.getByTestId('workflow-new-version').click()
    await expect(page.getByTestId('workflow-graph-nav')).toBeVisible()
    await expect(page.getByTestId('workflow-back-list')).toBeVisible()
    await expect(page.getByTestId('workflow-link-chat')).toHaveCount(0)
  })

  test('edits current published version and deletes from published list', async ({ page }) => {
    let nextSessionIndex = 1
    let deletedPath = ''
    const publishedWorkflows = [createPublishedWorkflow(1), createPublishedWorkflow(2)]

    await page.addInitScript(() => {
      window.confirm = () => true
    })

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: publishedWorkflows })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/versions') && request.method() === 'GET') {
        const workflowCode = decodeURIComponent(pathname.split('/')[3] || '')
        const workflow = publishedWorkflows.find((item) => item.workflowCode === workflowCode)
        await route.fulfill({ json: workflow ? [createWorkflowVersionFixture(workflow)] : [] })
        return
      }
      if (pathname.startsWith('/api/workflows/') && request.method() === 'DELETE') {
        deletedPath = pathname
        const workflowCode = decodeURIComponent(pathname.split('/')[3] || '')
        const index = publishedWorkflows.findIndex((item) => item.workflowCode === workflowCode)
        if (index >= 0) {
          publishedWorkflows.splice(index, 1)
        }
        await route.fulfill({ status: 204 })
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
      if (pathname === '/api/capabilities/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/executions' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }

      await route.fulfill({ status: 404, json: { message: `Unhandled ${request.method()} ${pathname}` } })
    })

    await page.goto('/#workflow')

    await page.getByTestId('workflow-list-edit-workflow_1').click()
    await expect(page.getByTestId('workflow-name-input')).toHaveValue('已发布工作流 1')
    await expect(page.getByTestId('workflow-description-input')).toHaveValue('已发布工作流 1 描述')
    await expect(page.getByTestId('workflow-current-graph')).toContainText('主流程')
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await expect(page.locator('.react-flow__edge')).toHaveCount(1)
    await expect(page.getByTestId('workflow-current-graph-name-input')).toHaveValue('主流程')
    await expect(page.getByTestId('workflow-current-graph-description-input')).toHaveValue('主流程描述')
    await page.locator('.react-flow__node').filter({ hasText: '子流程名称' }).click()
    await expect(page.getByPlaceholder('流程名称')).toHaveValue('子流程名称')
    await expect(page.getByTestId('workflow-node-description-input')).toHaveValue('子流程描述')
    await page.getByTestId('workflow-graph-nav-subgraph_saved').click()
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await expect(page.locator('.react-flow__edge')).toHaveCount(1)
    await expect(page.getByTestId('workflow-current-graph-name-input')).toHaveValue('子流程名称')
    await expect(page.getByTestId('workflow-current-graph-description-input')).toHaveValue('子流程描述')
    await page.getByTestId('workflow-breadcrumb-main').click()
    await expect(page.getByTestId('workflow-link-chat')).toHaveCount(0)

    await page.getByTestId('workflow-back-list').click()
    await expect(page.getByTestId('workflow-list-page')).toBeVisible()

    await page.getByTestId('workflow-list-delete-workflow_1').click()
    await expect.poll(() => deletedPath).toBe('/api/workflows/workflow_1')
    await expect(page.getByTestId('workflow-list-row-workflow_1')).toHaveCount(0)
    await expect(page.getByTestId('workflow-list-row-workflow_2')).toBeVisible()
  })

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

    await openNewWorkflowEditor(page)

    await expect(page.getByTestId('workflow-graph-nav')).toBeVisible()
    await expect(page.getByTestId('workflow-properties-panel')).toBeVisible()
    await expect(page.getByTestId('workflow-breadcrumb-main')).toContainText('主流程')
    await expect(page.getByTestId('workflow-current-graph')).toContainText('主流程')
  })

  test('uses a full-width workspace with original four-column editor proportions', async ({ page }) => {
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

    await openNewWorkflowEditor(page)

    await expect(page.getByTestId('workflow-page-layout')).toBeVisible()
    await expect(page.getByTestId('workflow-page-main')).toBeVisible()
    await expect(page.getByTestId('workflow-variable-panel')).toBeVisible()
    await expect(page.getByTestId('workflow-info-panel')).toBeVisible()
    await expect(page.getByTestId('workflow-process-properties-card')).toBeVisible()
    await expect(page.getByTestId('workflow-version-panel')).toHaveCount(0)
    await expect(page.getByTestId('workflow-back-list')).toBeVisible()
    await expect(page.getByTestId('workflow-new-version')).toHaveCount(0)
    await expect(page.getByTestId('workflow-version-toggle')).toHaveCount(0)
    await expect(page.getByTestId('workflow-name-input')).toBeVisible()
    await expect(page.getByTestId('workflow-publish')).toBeVisible()
    await expect(page.getByRole('button', { name: '新增子流程' })).toHaveCount(0)
    await expect(page.getByTestId('workflow-info-panel')).toContainText('维护名称并发布版本')
    await expect(page.getByTestId('workflow-info-panel')).not.toContainText('Intent Entry Rule')
    await expect(page.getByTestId('workflow-info-panel')).not.toContainText('编号')
    await expect(page.getByTestId('workflow-info-panel')).not.toContainText('版本列表入口只保留编辑、发布和删除')
    await expect(page.getByTestId('workflow-save-draft')).toHaveCount(0)
    await expect(page.getByTestId('workflow-validate')).toHaveCount(0)

    const mainBox = await page.getByTestId('workflow-page-main').boundingBox()
    const workspaceBox = await page.getByTestId('workflow-editor-workspace').boundingBox()
    const graphBox = await page.getByTestId('workflow-graph-nav').boundingBox()
    const canvasBox = await page.getByTestId('workflow-canvas-panel').boundingBox()
    const variableBox = await page.getByTestId('workflow-variable-panel').boundingBox()
    const infoBox = await page.getByTestId('workflow-info-panel').boundingBox()
    const propertiesBox = await page.getByTestId('workflow-process-properties-card').boundingBox()

    expect(mainBox).not.toBeNull()
    expect(workspaceBox).not.toBeNull()
    expect(graphBox).not.toBeNull()
    expect(canvasBox).not.toBeNull()
    expect(variableBox).not.toBeNull()
    expect(infoBox).not.toBeNull()
    expect(propertiesBox).not.toBeNull()
    expect((canvasBox as { x: number }).x).toBeGreaterThan((graphBox as { x: number }).x)
    expect(Math.abs((infoBox as { x: number }).x - (propertiesBox as { x: number }).x)).toBeLessThan(2)
    expect((infoBox as { y: number }).y).toBeLessThan((propertiesBox as { y: number }).y)
    expect(Math.abs((infoBox as { width: number }).width - (propertiesBox as { width: number }).width)).toBeLessThan(2)
    expect((variableBox as { x: number }).x).toBeGreaterThan((propertiesBox as { x: number }).x)
    expect(((variableBox as { x: number; width: number }).x + (variableBox as { width: number }).width)).toBeLessThanOrEqual(
      (workspaceBox as { x: number; width: number }).x + (workspaceBox as { width: number }).width + 4
    )
  })

  test('keeps the editor layout scale and allows two-axis workspace and canvas dragging', async ({ page }) => {
    await page.setViewportSize({ width: 1180, height: 720 })
    await mockEmptyWorkflowEditorApis(page)

    await openNewWorkflowEditor(page)

    const workspaceMetrics = await page.getByTestId('workflow-editor-workspace').evaluate((element) => ({
      clientWidth: element.clientWidth,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    }))
    expect(workspaceMetrics.scrollHeight).toBeGreaterThan(workspaceMetrics.clientHeight)

    await page.getByTestId('workflow-editor-workspace').evaluate((element) => {
      element.scrollTop = 120
    })
    const scrolledPosition = await page.getByTestId('workflow-editor-workspace').evaluate((element) => ({
      scrollTop: element.scrollTop,
    }))
    expect(scrolledPosition.scrollTop).toBeGreaterThan(0)

    await page.getByTestId('workflow-editor-workspace').evaluate((element) => {
      element.scrollTop = 0
    })
    const viewport = page.locator('.react-flow__viewport').first()
    const initialTransform = await viewport.getAttribute('style')
    expect(initialTransform || '').toContain('scale(1)')

    const paneBox = await page.locator('.react-flow__pane').first().boundingBox()
    expect(paneBox).not.toBeNull()
    await page.mouse.move((paneBox?.x || 0) + 24, (paneBox?.y || 0) + 24)
    await page.mouse.down()
    await page.mouse.move((paneBox?.x || 0) + 144, (paneBox?.y || 0) + 104, { steps: 6 })
    await page.mouse.up()

    await expect.poll(() => viewport.getAttribute('style')).not.toBe(initialTransform)
  })

  test('resizes all workflow editor regions horizontally with drag handles', async ({ page }) => {
    await mockEmptyWorkflowEditorApis(page)

    await openNewWorkflowEditor(page)

    const readPanelWidths = async () => ({
      graph: (await page.getByTestId('workflow-graph-nav').boundingBox())?.width || 0,
      canvas: (await page.getByTestId('workflow-canvas-panel').boundingBox())?.width || 0,
      info: (await page.getByTestId('workflow-info-panel').boundingBox())?.width || 0,
      properties: (await page.getByTestId('workflow-process-properties-card').boundingBox())?.width || 0,
      variables: (await page.getByTestId('workflow-variable-panel').boundingBox())?.width || 0,
    })

    await expect(page.getByTestId('workflow-resize-handle')).toHaveCount(3)
    const before = await readPanelWidths()

    const dragHandle = async (handleId: string, delta: number) => {
      const handle = page.getByTestId(handleId)
      await handle.scrollIntoViewIfNeeded()
      const handleBox = await handle.boundingBox()
      expect(handleBox).not.toBeNull()
      await page.mouse.move((handleBox?.x || 0) + (handleBox?.width || 0) / 2, (handleBox?.y || 0) + 80)
      await page.mouse.down()
      await page.mouse.move((handleBox?.x || 0) + (handleBox?.width || 0) / 2 + delta, (handleBox?.y || 0) + 80, {
        steps: 5,
      })
      await page.mouse.up()
    }

    await dragHandle('workflow-resize-handle-graph-canvas', 60)
    const afterGraphDrag = await readPanelWidths()
    expect(afterGraphDrag.graph).toBeGreaterThan(before.graph)
    expect(afterGraphDrag.canvas).toBeLessThan(before.canvas)

    await dragHandle('workflow-resize-handle-canvas-properties', -60)
    const afterCanvasDrag = await readPanelWidths()
    expect(afterCanvasDrag.canvas).toBeLessThan(afterGraphDrag.canvas)
    expect(afterCanvasDrag.properties).toBeGreaterThan(afterGraphDrag.properties)
    expect(Math.abs(afterCanvasDrag.info - afterCanvasDrag.properties)).toBeLessThan(2)

    await dragHandle('workflow-resize-handle-properties-variables', -60)
    const afterPropertiesDrag = await readPanelWidths()
    expect(afterPropertiesDrag.properties).toBeLessThan(afterCanvasDrag.properties)
    expect(afterPropertiesDrag.variables).toBeGreaterThan(afterCanvasDrag.variables)
  })

  test('shows paginated variable names and supports click edit plus context menu actions', async ({ page }) => {
    await mockEmptyWorkflowEditorApis(page)

    await openNewWorkflowEditor(page)

    const panel = page.getByTestId('workflow-variable-panel')
    await expect(panel.getByTestId('workflow-variable-type-select').locator('option')).toHaveText([
      'String',
      'Integer',
      'Long',
      'Double',
      'BigDecimal',
      'Boolean',
      'LocalDate',
      'LocalDateTime',
      'LocalTime',
      'List',
      'Map',
      'Object',
    ])

    for (let index = 1; index <= 8; index += 1) {
      await panel.getByTestId('workflow-variable-name-input').fill(`globalVar${index}`)
      await panel.getByTestId('workflow-variable-type-select').selectOption(index === 1 ? 'Long' : 'String')
      await panel.getByTestId('workflow-variable-description-input').fill(`变量 ${index}`)
      await panel.getByTestId('workflow-variable-add').click()
    }

    const globalList = panel.getByTestId('workflow-variable-list-global')
    await expect(globalList.getByTestId('workflow-variable-name-item')).toHaveText([
      'globalVar1',
      'globalVar2',
      'globalVar3',
      'globalVar4',
      'globalVar5',
      'globalVar6',
    ])
    await expect(globalList).not.toContainText('Long')
    await expect(globalList).not.toContainText('变量 1')

    await globalList.getByRole('button', { name: 'globalVar1' }).click()
    await expect(panel.getByTestId('workflow-variable-name-input')).toHaveValue('globalVar1')
    await panel.getByTestId('workflow-variable-name-input').fill('renamedGlobalVar')
    await panel.getByTestId('workflow-variable-save').click()
    await expect(globalList.getByRole('button', { name: 'renamedGlobalVar' })).toBeVisible()

    await globalList.getByRole('button', { name: 'globalVar2' }).click({ button: 'right' })
    await page.getByTestId('workflow-variable-context-edit').click()
    await expect(panel.getByTestId('workflow-variable-name-input')).toHaveValue('globalVar2')
    await panel.getByTestId('workflow-variable-cancel').click()

    await globalList.getByRole('button', { name: 'renamedGlobalVar' }).click({ button: 'right' })
    await page.getByTestId('workflow-variable-context-delete').click()
    await expect(globalList.getByRole('button', { name: 'renamedGlobalVar' })).toHaveCount(0)

    await panel.getByTestId('workflow-variable-page-next-global').click()
    await expect(globalList.getByRole('button', { name: 'globalVar8' })).toBeVisible()
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

    await openNewWorkflowEditor(page)

    await expect(page.getByTestId('workflow-add-node-coordinator')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-sub_agent')).toBeVisible()
    await expect(page.getByTestId('workflow-add-node-start')).toHaveCount(0)
    await expect(page.getByTestId('workflow-add-node-message')).toHaveCount(0)
    await expect(page.getByTestId('workflow-add-node-end')).toHaveCount(0)

    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByPlaceholder('流程名称').fill('子流程 A')
    await page.getByTestId('workflow-open-subgraph').click()

    await expect(page.locator('[data-testid^="workflow-breadcrumb-subgraph_sub_agent_"]')).toBeVisible()
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

    await openNewWorkflowEditor(page)

    await expect(page.locator('.react-flow__node')).toHaveCount(1)
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await expect(page.locator('.react-flow__node')).toHaveCount(2)
    await page.getByTestId('workflow-delete-node').click()
    await expect(page.locator('.react-flow__node')).toHaveCount(1)

    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByPlaceholder('流程名称').fill('能力子流程')
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

    await openNewWorkflowEditor(page)

    await page.getByTestId('workflow-current-graph-name-input').fill('Nested Graph Contract')
    await page.getByTestId('workflow-current-graph-description-input').fill('工作流描述')
    await expect(page.getByTestId('workflow-name-input')).toHaveValue('Nested Graph Contract')
    await expect(page.getByTestId('workflow-description-input')).toHaveValue('工作流描述')
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await expect(page.getByTestId('workflow-subgraph-id-input')).toHaveCount(0)
    await page.getByPlaceholder('流程名称').fill('子流程复核')
    await page.getByTestId('workflow-node-description-input').fill('子代理节点描述')
    await page.getByTestId('workflow-open-subgraph').click()
    await expect(page.getByTestId('workflow-breadcrumb-main')).toBeVisible()
    await expect(page.locator('[data-testid^="workflow-breadcrumb-subgraph_sub_agent_"]')).toBeVisible()

    const graphNameInput = page.getByTestId('workflow-current-graph-name-input')
    const graphDescriptionInput = page.getByTestId('workflow-current-graph-description-input')
    await expect(graphNameInput).toHaveValue('子流程复核')
    await graphNameInput.fill('')
    await expect(graphNameInput).toHaveValue('')
    await graphNameInput.fill('子流程复核')
    await expect(graphNameInput).toHaveValue('子流程复核')
    await graphDescriptionInput.fill('子流程复核描述')
    await page.getByTestId('workflow-breadcrumb-main').click()
    await page.locator('.react-flow__node').filter({ hasText: '子流程复核' }).click()
    await expect(page.getByPlaceholder('流程名称')).toHaveValue('子流程复核')
    await expect(page.getByTestId('workflow-node-description-input')).toHaveValue('子流程复核描述')

    await page.getByTestId('workflow-publish').click()

    await expect.poll(() => draftPayload).not.toBeNull()
    const rawDefinition = String((draftPayload || {}).definition || '{}')
    const definition = JSON.parse(rawDefinition) as Record<string, unknown>
    const graphs = (definition.graphs || {}) as Record<string, unknown>
    const definitionBindings = (definition.model_bindings || {}) as Record<string, unknown>
    const definitionLlmDefaults = (definitionBindings.llm_defaults || {}) as Record<string, unknown>

    expect(definition.schema_version).toBe('workflow-designer/v2')
    expect(draftPayload?.workflow_name).toBe('Nested Graph Contract')
    expect(draftPayload?.workflow_description).toBe('工作流描述')
    expect(definition.workflow_name).toBe('Nested Graph Contract')
    expect(definition.workflow_description).toBe('工作流描述')
    expect(definition.main_graph_id).toBe('main')
    expect(Array.isArray(graphs)).toBe(false)
    expect(graphs.main).toBeTruthy()
    const subgraphId = Object.keys(graphs).find((graphId) => graphId !== 'main')
    expect(subgraphId).toBeTruthy()
    const mainGraph = graphs.main as Record<string, unknown>
    const subGraph = graphs[subgraphId as string] as Record<string, unknown>
    expect(mainGraph.graph_id).toBe('main')
    expect(mainGraph.graph_type).toBe('MAIN')
    expect(mainGraph.graph_name).toBe('Nested Graph Contract')
    expect(mainGraph.graph_description).toBe('工作流描述')
    expect(mainGraph.entry_node_id).toBeTruthy()
    expect(Array.isArray(mainGraph.edges)).toBe(true)
    const mainNodes = mainGraph.nodes as Record<string, Record<string, unknown>>
    const savedSubAgent = Object.values(mainNodes).find((node) => node.type === 'sub_agent')
    expect(savedSubAgent?.name).toBe('子流程复核')
    expect(savedSubAgent?.description).toBe('子流程复核描述')
    expect((savedSubAgent?.config as Record<string, unknown>).description).toBe('子流程复核描述')
    expect(subGraph.graph_id).toBe(subgraphId)
    expect(subGraph.graph_type).toBe('SUBGRAPH')
    expect(subGraph.graph_name).toBe('子流程复核')
    expect(subGraph.graph_description).toBe('子流程复核描述')
    expect(Array.isArray(subGraph.edges)).toBe(true)
    expect(mainGraph).not.toHaveProperty('transitions')
    expect(mainGraph).not.toHaveProperty('entry')
    expect(definitionBindings.routing_model_code).toBeTruthy()
    expect(definitionLlmDefaults.model_code).toBeTruthy()
    expect(definitionBindings).not.toHaveProperty('intent_profile_ref')
    expect(definitionLlmDefaults).not.toHaveProperty('model_profile_ref')

    const rawConfig = String((draftPayload || {}).config || '{}')
    const config = JSON.parse(rawConfig) as Record<string, unknown>
    const configBindings = (config.model_bindings || {}) as Record<string, unknown>
    const configLlmDefaults = (configBindings.llm_defaults || {}) as Record<string, unknown>
    expect(config.main_graph_id).toBe('main')
    expect(config).not.toHaveProperty('graphs')
    expect(configBindings.routing_model_code).toBeTruthy()
    expect(configLlmDefaults.model_code).toBeTruthy()
    expect(configBindings).not.toHaveProperty('intent_profile_ref')
    expect(configLlmDefaults).not.toHaveProperty('model_profile_ref')
  })

  test('publishes from editor without exposing chat debug entry', async ({ page }) => {
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

    await openNewWorkflowEditor(page)
    await page.getByTestId('workflow-name-input').fill('Publish Without Chat Link')
    await page.getByTestId('workflow-current-graph-name-input').fill('发布主流程')
    await page.getByTestId('workflow-current-graph-description-input').fill('发布主流程描述')
    await page.getByTestId('workflow-publish').click()

    await expect.poll(() => publishRequest).not.toBeNull()
    await expect(page.getByTestId('workflow-link-chat')).toHaveCount(0)
    await page.getByTestId('workflow-back-list').click()
    await expect(page.getByTestId('workflow-list-page')).toBeVisible()
    await expect(page.getByTestId(`workflow-list-row-${publishRequest?.workflowCode}`)).toBeVisible()
    await expect(page.getByTestId(`workflow-list-row-${publishRequest?.workflowCode}`)).toContainText(publishRequest?.version || '')
    await expect(page).toHaveURL(/#workflow$/)
  })
})
