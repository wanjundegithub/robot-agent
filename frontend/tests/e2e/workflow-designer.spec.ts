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

  test('creates workflow spaces from a modal with optional description', async ({ page }) => {
    let nextSessionIndex = 1
    let createdSpacePayload: Record<string, unknown> | null = null

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname } = url

      if (pathname === '/api/workflows/published' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflow-spaces' && request.method() === 'GET') {
        await route.fulfill({
          json: [
            {
              id: 1,
              workspace_id: 1,
              space_code: 'default_workflow_space',
              name: '默认工作流空间',
              description: '默认空间',
              status: 'PUBLISHED',
            },
          ],
        })
        return
      }
      if (pathname === '/api/workflow-spaces' && request.method() === 'POST') {
        createdSpacePayload = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            id: 2,
            workspace_id: 1,
            space_code: createdSpacePayload.space_code,
            name: createdSpacePayload.name,
            description: createdSpacePayload.description,
            status: 'PUBLISHED',
          },
        })
        return
      }
      if (pathname === '/api/robots' && request.method() === 'GET') {
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

    await expect(page.getByTestId('workflow-list-page')).toBeVisible()
    await expect(page.getByTestId('workflow-space-name-input')).toHaveCount(0)
    await page.getByRole('button', { name: '创建空间' }).click()
    await expect(page.getByRole('dialog', { name: '创建工作流空间' })).toBeVisible()
    await page.getByTestId('workflow-space-dialog-name').fill('售后工作流空间')
    await page.getByTestId('workflow-space-dialog-description').fill('售后流程专用空间')
    await page.getByRole('button', { name: '保存' }).click()

    expect(createdSpacePayload).toMatchObject({
      name: '售后工作流空间',
      description: '售后流程专用空间',
    })
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
      'Number',
      'Boolean',
      'Object',
      'Array',
    ])

    for (let index = 1; index <= 8; index += 1) {
      await panel.getByTestId('workflow-variable-name-input').fill(`globalVar${index}`)
      await panel.getByTestId('workflow-variable-type-select').selectOption(index === 1 ? 'Number' : 'String')
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
    await expect(globalList).not.toContainText('Number')
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

  test('edits function fragment node and publishes python snippet config', async ({ page }) => {
    let nextSessionIndex = 1
    let validateRequest: Record<string, unknown> | null = null
    let testRunRequest: Record<string, unknown> | null = null
    let draftPayload: Record<string, unknown> | null = null

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
      if (pathname === '/api/api-center/groups' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/workflows/function-fragments/validate' && request.method() === 'POST') {
        validateRequest = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            valid: true,
            error_message: null,
            line: null,
            column: null,
          },
        })
        return
      }
      if (pathname === '/api/workflows/function-fragments/test-run' && request.method() === 'POST') {
        testRunRequest = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            success: true,
            variables: {
              global: { user_name: '张三' },
              local: { order_id: 'A001', result: 'ok' },
            },
            stdout: '开始处理\n',
            error_message: null,
            line: null,
            column: null,
            duration_ms: 12,
          },
        })
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
            id: 901,
            workflowId: 902,
            workflowCode: String(draftPayload.workflow_code || `workflow_${Date.now()}`),
            workflowName: String(draftPayload.workflow_name || 'Function Fragment Workflow'),
            version: String(draftPayload.version || 'draft'),
            status: 'DRAFT',
          },
        })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/publish') && request.method() === 'POST') {
        await route.fulfill({
          json: {
            id: 903,
            workflowCode: pathname.split('/')[3] || 'function_fragment_workflow',
            name: 'Function Fragment Workflow',
            status: 'PUBLISHED',
            currentVersion: 'v202606090001',
            createdBy: 'demo-user',
          },
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
        await route.fulfill({
          json: {
            id: pathname.split('/').pop() || 'session-e2e-1',
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

    await page.getByTestId('workflow-name-input').fill('Function Fragment Workflow')
    await page.getByTestId('workflow-description-input').fill('Function fragment workflow description')
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByPlaceholder('流程名称').fill('函数子流程')
    await page.getByTestId('workflow-node-description-input').fill('函数子流程描述')
    await page.getByTestId('workflow-open-subgraph').click()
    await page.getByTestId('workflow-current-graph-name-input').fill('函数子流程')
    await page.getByTestId('workflow-current-graph-description-input').fill('函数子流程描述')
    await page.getByTestId('workflow-add-node-function').click()
    await page.locator('.react-flow__node').filter({ hasText: '函数节点' }).click()

    await expect(page.getByTestId('workflow-function-name-input')).toBeVisible()
    await expect(page.getByTestId('workflow-function-code-input')).toBeVisible()
    await expect(page.getByText('函数(Python)')).toBeVisible()
    await expect(page.getByTestId('workflow-function-test-variables-input')).toHaveCount(0)
    await expect(page.getByText('等待校验：请输入 Python 函数片段')).toHaveCount(0)
    await page.getByTestId('workflow-function-name-input').fill('处理订单变量')
    await page.getByTestId('workflow-function-code-input').fill(
      "print('开始处理')\nctx['local']['result'] = ctx['global']['user_name'] + '-' + ctx['local'].get('order_id', '')"
    )
    await expect.poll(() => validateRequest).not.toBeNull()
    await expect(page.getByTestId('workflow-function-validation-status')).toContainText('校验通过')
    expect(validateRequest?.code).toBe(
      "print('开始处理')\nctx['local']['result'] = ctx['global']['user_name'] + '-' + ctx['local'].get('order_id', '')"
    )

    await page.getByTestId('workflow-function-test-run').click()
    const testDialog = page.getByTestId('workflow-function-test-dialog')
    await expect(testDialog).toBeVisible()
    await expect(testDialog).toContainText('global.user_name')
    await expect(testDialog).toContainText('local.order_id')
    await testDialog.getByTestId('workflow-function-test-variables-input').fill(
      JSON.stringify({ global: { user_name: '张三' }, local: { order_id: 'A001' } }, null, 2)
    )
    await testDialog.getByTestId('workflow-function-test-submit').click()
    await expect(testDialog.getByTestId('workflow-function-test-result')).toContainText('成功')
    await expect(testDialog.getByTestId('workflow-function-test-result')).toContainText('开始处理')
    expect(testRunRequest?.variables).toEqual({
      global: { user_name: '张三' },
      local: { order_id: 'A001' },
    })
    await testDialog.getByRole('button', { name: '关闭' }).click()
    await expect(testDialog).toHaveCount(0)

    await page.getByTestId('workflow-add-node-start').click()
    await page.getByTestId('workflow-add-node-end').click()
    await page.getByTestId('workflow-publish').click()
    await expect.poll(() => draftPayload).not.toBeNull()
    const definition = JSON.parse(String((draftPayload || {}).definition || '{}')) as Record<string, unknown>
    const graphs = definition.graphs as Record<string, Record<string, unknown>>
    const subgraphId = Object.keys(graphs).find((graphId) => graphId !== 'main') as string
    const subgraphNodes = graphs[subgraphId].nodes as Record<string, Record<string, unknown>>
    const functionNode = Object.values(subgraphNodes).find((node) => node.type === 'function')
    const functionConfig = functionNode?.config as Record<string, unknown>

    expect(functionConfig.language).toBe('python')
    expect(functionConfig.function_name).toBe('处理订单变量')
    expect(functionConfig.code).toBe(
      "print('开始处理')\nctx['local']['result'] = ctx['global']['user_name'] + '-' + ctx['local'].get('order_id', '')"
    )
    expect(functionConfig.timeout_ms).toBe(3000)
    expect(functionConfig.operation_type).toBeUndefined()
    expect(functionConfig.assignments).toBeUndefined()
  })

  test('deletes selected nodes and loads API capability groups/items from real APIs', async ({ page }) => {
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
    await page.getByTestId('workflow-add-node-api').click()

    await page.getByTestId('workflow-api-group-select').selectOption('7')
    await expect.poll(() => itemsRequestCount).toBe(1)
    await expect(page.getByTestId('workflow-api-capability-select')).toContainText('Search Flights')
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
    expect(definition).not.toHaveProperty('model_bindings')

    const rawConfig = String((draftPayload || {}).config || '{}')
    const config = JSON.parse(rawConfig) as Record<string, unknown>
    expect(config.main_graph_id).toBe('main')
    expect(config).not.toHaveProperty('graphs')
    expect(config).not.toHaveProperty('model_bindings')
  })

  test('configures execution as API node from published API capability schemas', async ({ page }) => {
    let nextSessionIndex = 1
    let draftPayload: Record<string, unknown> | null = null

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
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/validate-draft') && request.method() === 'POST') {
        await route.fulfill({ json: { valid: true, issues: [] } })
        return
      }
      if (pathname.startsWith('/api/workflows/') && pathname.endsWith('/drafts') && request.method() === 'POST') {
        draftPayload = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            id: 501,
            workflowId: 502,
            workflowCode: String(draftPayload.workflow_code || `workflow_${Date.now()}`),
            workflowName: String(draftPayload.workflow_name || 'API Node Workflow'),
            version: String(draftPayload.version || 'draft'),
            status: 'DRAFT',
          },
        })
        return
      }
      if (pathname === '/api/api-center/groups' && request.method() === 'GET') {
        await route.fulfill({
          json: [
            {
              id: 91,
              groupName: '已上线能力组',
              status: 'PUBLISHED',
              latestSnapshotVersion: 'snap_202605300001',
              capabilityCount: 2,
            },
            { id: 92, groupName: '草稿能力组', status: 'DISABLED', capabilityCount: 1 },
          ],
        })
        return
      }
      if (pathname === '/api/api-center/groups/91/items' && request.method() === 'GET') {
        await route.fulfill({
          json: [
            {
              id: 901,
              groupId: 91,
              apiName: '航班价格查询',
              requestUrl: 'https://tools.example.com/flights/{flightId}/price',
              requestMethod: 'POST',
              status: 'PUBLISHED',
              publishedVersion: 'v202605300001',
              inputSchema: JSON.stringify({
                type: 'object',
                required: ['departureCity'],
                properties: {
                  departureCity: { type: 'string', description: '出发城市' },
                  arrivalCity: { type: 'string', description: '到达城市' },
                },
              }),
              outputSchema: JSON.stringify({
                type: 'object',
                required: ['price'],
                properties: {
                  price: { type: 'number', description: '最低价格' },
                  currency: { type: 'string', description: '币种' },
                },
              }),
            },
            {
              id: 902,
              groupId: 91,
              apiName: '草稿接口',
              requestUrl: 'https://tools.example.com/draft',
              requestMethod: 'POST',
              status: 'DISABLED',
            },
            {
              id: 903,
              groupId: 91,
              apiName: '技能助手',
              requestUrl: 'https://tools.example.com/skill',
              requestMethod: 'POST',
              status: 'DISABLED',
            },
          ],
        })
        return
      }
      if (pathname === '/api/api-center/groups/91/items/flight_price_api/versions' && request.method() === 'GET') {
        await route.fulfill({
          json: [
            {
              id: 901,
              groupId: 91,
              apiName: '航班价格查询',
              requestUrl: 'https://tools.example.com/flights/{flightId}/price',
              requestMethod: 'POST',
              version: 'v202605300001',
              status: 'PUBLISHED',
              inputSchema: JSON.stringify({
                type: 'object',
                properties: {
                  departureCity: { type: 'string', description: '出发城市' },
                  arrivalCity: { type: 'string', description: '到达城市' },
                },
              }),
              outputSchema: JSON.stringify({
                type: 'object',
                properties: {
                  price: { type: 'number', description: '最低价格' },
                  currency: { type: 'string', description: '币种' },
                },
              }),
            },
          ],
        })
        return
      }
      if (pathname.startsWith('/api/api-center/groups/') && pathname.endsWith('/items') && request.method() === 'GET') {
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
    const variablePanel = page.getByTestId('workflow-variable-panel')
    await variablePanel.getByTestId('workflow-variable-name-input').fill('flightIdVar')
    await variablePanel.getByTestId('workflow-variable-add').click()
    await variablePanel.getByTestId('workflow-variable-name-input').fill('departureCityVar')
    await variablePanel.getByTestId('workflow-variable-add').click()
    await variablePanel.getByTestId('workflow-variable-name-input').fill('bestPrice')
    await variablePanel.getByTestId('workflow-variable-scope-select').selectOption('temp')
    await variablePanel.getByTestId('workflow-variable-add').click()

    await page.getByTestId('workflow-current-graph-name-input').fill('API Node Workflow')
    await page.getByTestId('workflow-current-graph-description-input').fill('API 节点流程描述')
    await page.getByTestId('workflow-add-node-sub_agent').click()
    await page.getByPlaceholder('流程名称').fill('API 子流程')
    await page.getByTestId('workflow-node-description-input').fill('API 子流程描述')
    await page.getByTestId('workflow-open-subgraph').click()
    await page.getByTestId('workflow-add-node-api').click()
    await page.locator('.react-flow__node').filter({ hasText: 'API节点' }).click()

    await expect(page.getByText('节点类型：API节点')).toBeVisible()
    await expect(page.getByTestId('workflow-api-group-select').locator('option')).toHaveText([
      '选择API组',
      '已上线能力组',
    ])
    await page.getByTestId('workflow-api-group-select').selectOption('91')
    await expect(page.getByTestId('workflow-api-item-select').locator('option')).toHaveText([
      '选择 API',
      '航班价格查询',
    ])
    await page.getByTestId('workflow-api-item-select').selectOption('901')

    await expect(page.getByTestId('workflow-api-input-parameters')).toContainText('departureCity')
    await expect(page.getByTestId('workflow-api-input-parameters')).toContainText('flightId')
    await expect(page.getByTestId('workflow-api-input-parameters')).not.toContainText('出发城市')
    await expect(page.getByTestId('workflow-api-output-parameters')).toContainText('price')
    await expect(page.getByTestId('workflow-api-output-parameters')).not.toContainText('最低价格')
    await expect(page.getByTestId('workflow-api-input-parameter-flightId-variable-select')).toHaveValue('')
    await expect(page.getByTestId('workflow-api-input-parameter-departureCity-variable-select')).toHaveValue('')
    await expect(page.getByTestId('workflow-api-output-parameter-price-variable-select')).toHaveValue('')
    await expect(page.getByTestId('workflow-api-input-parameters')).toContainText('必填，请选择变量')
    await page.getByTestId('workflow-publish').click()
    await expect(page.getByTestId('workflow-validation-issues')).toContainText('API节点输入参数 departureCity 为必填，请选择变量')
    expect(draftPayload).toBeNull()

    await page.getByTestId('workflow-api-input-parameter-flightId-variable-select').selectOption({ index: 1 })
    await page.getByTestId('workflow-api-input-parameter-departureCity-variable-select').selectOption({ index: 2 })
    await page.getByTestId('workflow-api-output-parameter-price-variable-select').selectOption({ index: 3 })

    await page.getByTestId('workflow-publish').click()
    await expect.poll(() => draftPayload).not.toBeNull()
    const definition = JSON.parse(String((draftPayload || {}).definition || '{}')) as Record<string, unknown>
    const graphs = definition.graphs as Record<string, Record<string, unknown>>
    const subgraphId = Object.keys(graphs).find((graphId) => graphId !== 'main') as string
    const subgraphNodes = graphs[subgraphId].nodes as Record<string, Record<string, unknown>>
    const apiNode = Object.values(subgraphNodes).find((node) => node.type === 'api')
    const apiConfig = apiNode?.config as Record<string, unknown>
    expect(apiNode?.name).toBe('API节点')
    expect(apiConfig.group_id).toBe(91)
    expect(apiConfig.api_id).toBe(901)
    expect(apiConfig.tool_code).toBe('901')
    expect(apiConfig.request_url).toBe('https://tools.example.com/flights/{flightId}/price')
    expect(apiConfig.input_schema).toContain('departureCity')
    expect(apiConfig.output_schema).toContain('price')
    expect(apiConfig.payload_mapping).toEqual({
      flightId: '$session.flightIdVar',
      departureCity: '$session.departureCityVar',
    })
    expect(apiConfig.output_mapping).toEqual({ price: '$execution.bestPrice' })
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
