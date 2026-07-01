import { expect, test } from '@playwright/test'

type Space = {
  id: number
  workspaceId: number
  kbCode: string
  name: string
  description: string
  embeddingModel?: string | null
  status: string
  documentCount?: number
  createdAt?: string
}

type DocumentItem = {
  docId: string
  kbCode: string
  title?: string | null
  description?: string | null
  content?: string | null
  filename?: string | null
  sourceType?: string | null
  status: string
  chunkCount?: number
  fileSize?: number
  generatedSummary?: string | null
}

type TaskItem = {
  taskId: string
  docId: string
  kbCode: string
  stage: string
  status: string
  progress: number
  errorMessage?: string | null
  retryCount?: number | null
}

test.beforeEach(async ({ page }) => {
  let spaces: Space[] = [
    {
      id: 1,
      workspaceId: 1,
      kbCode: 'kb_product',
      name: '产品知识',
      description: '产品说明与售后政策',
      embeddingModel: 'model-431c4581ab84',
      status: 'ACTIVE',
      documentCount: 1,
      createdAt: '2026-06-14T00:00:00',
    },
  ]
  let documents: DocumentItem[] = [
    {
      docId: 'doc_1',
      kbCode: 'kb_product',
      title: '产品手册',
      description: '售后规则',
      content: '完整正文：产品保修期为一年，电池保修期为六个月。',
      sourceType: 'TEXT',
      status: 'READY',
      chunkCount: 6,
      fileSize: 128,
      generatedSummary: '摘要：保修期为一年。',
    },
  ]
  let tasks: TaskItem[] = [
    {
      taskId: 'task_1',
      docId: 'doc_1',
      kbCode: 'kb_product',
      stage: 'INDEXED',
      status: 'SUCCEEDED',
      progress: 100,
      errorMessage: null,
      retryCount: 1,
    },
  ]

  await page.route('**/api/workflows/published', async (route) => route.fulfill({ json: [] }))
  await page.route('**/api/sessions**', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        json: {
          id: 'session-knowledge',
          workspaceId: 1,
          userId: 'demo-user',
          status: 'ACTIVE',
          currentExecutionId: null,
          createdAt: '2026-06-14T00:00:00',
          lastActivityAt: '2026-06-14T00:00:00',
        },
      })
      return
    }
    await route.fulfill({ json: [] })
  })
  await page.route('**/api/knowledge-bases', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ json: spaces })
      return
    }
    const body = JSON.parse(route.request().postData() || '{}')
    expect(body).not.toHaveProperty('embeddingModel')
    expect(body).not.toHaveProperty('kbCode')
    const created: Space = {
      id: 2,
      workspaceId: 1,
      kbCode: 'kb_generated',
      name: body.name,
      description: body.description ?? '',
      status: 'ACTIVE',
      documentCount: 0,
    }
    spaces = [created, ...spaces]
    await route.fulfill({ json: created })
  })
  await page.route('**/api/knowledge-bases/kb_product', async (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      expect(body).not.toHaveProperty('embeddingModel')
      spaces = spaces.map((space) =>
        space.kbCode === 'kb_product' ? { ...space, name: body.name, description: body.description ?? '' } : space
      )
      await route.fulfill({ json: spaces.find((space) => space.kbCode === 'kb_product') })
      return
    }
    if (route.request().method() === 'DELETE') {
      spaces = spaces.filter((space) => space.kbCode !== 'kb_product')
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.continue()
  })
  await page.route('**/api/knowledge-bases/kb_product/documents', async (route) =>
    route.fulfill({ json: documents })
  )
  await page.route('**/api/knowledge-bases/documents/doc_1', async (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      documents = documents.map((document) =>
        document.docId === 'doc_1'
          ? {
              ...document,
              title: body.title,
              description: body.description ?? '',
              content: body.content,
              generatedSummary: body.content,
              status: 'READY',
            }
          : document
      )
      await route.fulfill({ json: documents.find((document) => document.docId === 'doc_1') })
      return
    }
    if (route.request().method() === 'DELETE') {
      documents = documents.filter((document) => document.docId !== 'doc_1')
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.continue()
  })
  await page.route('**/api/knowledge-bases/documents/doc_1/tasks', async (route) => route.fulfill({ json: tasks }))
  await page.route('**/api/knowledge-bases/tasks/task_1', async (route) => {
    if (route.request().method() === 'DELETE') {
      expect(route.request().headers()['x-user-id']).toBe('demo-user')
      tasks = tasks.filter((task) => task.taskId !== 'task_1')
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.continue()
  })
  await page.route('**/api/knowledge-bases/search/stream', async (route) => {
    expect(route.request().headers()['x-user-id']).toBe('demo-user')
    const result = {
      query: '保修期',
      documents: [
        {
          chunkId: 'chunk_1',
          docId: 'doc_1',
          kbCode: 'kb_product',
          title: '产品手册',
          content: '保修期为一年。',
          score: 0.92,
        },
      ],
      answer: '根据产品手册，保修期为一年。',
      citations: [{ chunkId: 'chunk_1', docId: 'doc_1', score: 0.92 }],
      bestScore: 0.92,
    }
    await route.fulfill({
      contentType: 'text/event-stream; charset=utf-8',
      body: [
        'event: delta',
        `data: ${JSON.stringify({ type: 'delta', content: '根据产品', deltaIndex: 1, elapsedMs: 8 })}`,
        '',
        'event: delta',
        `data: ${JSON.stringify({ type: 'delta', content: '手册，保', deltaIndex: 2, elapsedMs: 9 })}`,
        '',
        'event: delta',
        `data: ${JSON.stringify({ type: 'delta', content: '修期为一', deltaIndex: 3, elapsedMs: 10 })}`,
        '',
        'event: delta',
        `data: ${JSON.stringify({ type: 'delta', content: '年。', deltaIndex: 4, elapsedMs: 10 })}`,
        '',
        'event: completed',
        `data: ${JSON.stringify({ type: 'completed', query: '保修期', elapsedMs: 10, result })}`,
        '',
      ].join('\n'),
    })
  })
})

test('knowledge center uses Chinese UI without refresh or embedding model controls', async ({ page }) => {
  await page.goto('/#knowledge')
  const panel = page.getByTestId('knowledge-center-panel')
  await expect(panel).toBeVisible()
  const box = await panel.boundingBox()
  const viewport = page.viewportSize()
  expect(box?.width).toBeGreaterThan((viewport?.width ?? 0) * 0.9)
  expect(box?.height).toBeGreaterThan((viewport?.height ?? 0) * 0.75)

  await expect(page.getByTestId('knowledge-space-create')).toBeVisible()
  await expect(page.getByTestId('knowledge-space-list')).toContainText('产品知识')
  await expect(page.getByTestId('knowledge-subnav-spaces')).toHaveText('知识空间')
  await expect(page.getByTestId('knowledge-subnav-tasks')).toHaveText('采集任务')
  await expect(page.getByTestId('knowledge-subnav-search')).toHaveText('知识检索')
  await expect(panel).not.toContainText('刷新')
  await expect(panel).not.toContainText('向量模型')
  await expect(panel).not.toContainText('embedding')
  await expect(panel).not.toContainText('kb_product')
  await expect(panel).not.toContainText('No knowledge')

  await page.getByTestId('knowledge-space-create').click()
  await expect(page.getByRole('dialog')).toContainText('新增知识空间')
  await expect(page.getByRole('dialog')).not.toContainText('空间编码')
  await expect(page.getByRole('dialog')).not.toContainText('向量模型')
})

test('knowledge spaces can be edited and soft deleted from the page', async ({ page }) => {
  await page.goto('/#knowledge')
  await page.getByTestId('knowledge-space-edit-kb_product').click()
  await page.getByLabel('空间名称').fill('产品知识库')
  await page.getByLabel('描述').fill('产品说明、售后政策与常见问题')
  await page.getByRole('button', { name: '保存' }).click()
  await expect(page.getByTestId('knowledge-space-list')).toContainText('产品知识库')

  await page.getByTestId('knowledge-space-delete-kb_product').click()
  await expect(page.getByRole('dialog')).toContainText('删除知识空间')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByTestId('knowledge-space-list')).not.toContainText('产品知识库')
})

test('text knowledge can be edited and soft deleted from the page', async ({ page }) => {
  await page.goto('/#knowledge')
  await page.getByTestId('knowledge-document-edit-doc_1').click()
  await expect(page.getByLabel('正文')).toHaveValue('完整正文：产品保修期为一年，电池保修期为六个月。')
  await page.getByLabel('标题').fill('产品保修政策')
  await page.getByLabel('描述').fill('售后保修规则')
  await page.getByLabel('正文').fill('产品保修期为一年。')
  await page.getByRole('button', { name: '提交采集' }).click()
  await expect(page.getByText('产品保修政策')).toBeVisible()

  await page.getByTestId('knowledge-document-delete-doc_1').click()
  await expect(page.getByRole('dialog')).toContainText('删除知识')
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page.locator('.knowledge-document-list')).not.toContainText('产品保修政策')
})

test('tasks show successful status and can be deleted from the page', async ({ page }) => {
  await page.goto('/#knowledge')
  await page.getByTestId('knowledge-subnav-tasks').click()

  await expect(page.locator('.knowledge-task-list')).toContainText('已入库')
  await expect(page.locator('.knowledge-task-list')).toContainText('成功')
  await expect(page.locator('.knowledge-task-list')).not.toContainText('未知状态')

  await page.getByTestId('knowledge-task-delete-task_1').click()
  await expect(page.locator('.knowledge-task-list')).not.toContainText('task_1')
  await expect(page.locator('.knowledge-task-list')).toContainText('暂无采集任务')
})

test('knowledge search sends current user id for permission checks', async ({ page }) => {
  await page.goto('/#knowledge')
  await page.getByTestId('knowledge-subnav-search').click()
  await page.getByPlaceholder('输入要查询的知识问题').fill('保修期')
  await page.getByRole('button', { name: '开始检索' }).click()

  await expect(page.locator('.knowledge-search-results')).toContainText('保修期为一年')
})

test('knowledge page keeps backend errors in Chinese', async ({ page }) => {
  await page.unroute('**/api/knowledge-bases')
  await page.route('**/api/knowledge-bases', async (route) => {
    await route.fulfill({
      status: 500,
      json: { message: 'Server Error' },
    })
  })

  await page.goto('/#knowledge')
  const panel = page.getByTestId('knowledge-center-panel')

  await expect(panel).toContainText('加载知识空间失败。')
  await expect(panel).not.toContainText('Server Error')
})
