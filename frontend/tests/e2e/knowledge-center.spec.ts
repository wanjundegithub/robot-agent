import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
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
      await route.fulfill({
        json: [
          {
            id: 1,
            workspaceId: 1,
            kbCode: 'kb_product',
            name: '产品知识',
            description: '产品说明与售后政策',
            embeddingModel: 'embedding-qwen3-8b',
            status: 'ACTIVE',
            documentCount: 3,
            createdAt: '2026-06-14T00:00:00',
          },
        ],
      })
      return
    }
    await route.fulfill({
      json: {
        id: 2,
        workspaceId: 1,
        kbCode: 'kb_new',
        name: '新知识空间',
        description: '',
        embeddingModel: 'embedding-qwen3-8b',
        status: 'ACTIVE',
      },
    })
  })
  await page.route('**/api/knowledge-bases/kb_product/documents', async (route) =>
    route.fulfill({
      json: [
        {
          id: 1,
          kbCode: 'kb_product',
          docId: 'doc_1',
          title: '产品手册',
          sourceType: 'TEXT',
          status: 'READY',
          chunkCount: 6,
          createdAt: '2026-06-14T00:00:00',
        },
      ],
    })
  )
  await page.route('**/api/knowledge-bases/search', async (route) =>
    route.fulfill({
      json: {
        query: '保修期',
        documents: [
          {
            chunkId: 'chunk_1',
            docId: 'doc_1',
            kbCode: 'kb_product',
            title: '产品手册',
            content: '保修期为一年',
            score: 0.92,
          },
        ],
        answer: '根据产品手册，保修期为一年。',
        citations: [{ chunkId: 'chunk_1', docId: 'doc_1', score: 0.92 }],
        bestScore: 0.92,
      },
    })
  )
})

test('knowledge center uses full screen layout and required entries', async ({ page }) => {
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
})
