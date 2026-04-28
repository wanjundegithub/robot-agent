import { expect, test } from '@playwright/test'

type ModelConfig = {
  id: number
  custom_model_name: string
  provider: string
  model_name: string
  api_key: string
  base_url: string
  updated_at: string
}

type ProviderDraft = {
  provider_type: string
  base_url: string
}

test.describe('简化后的模型配置页', () => {
  test('使用全屏 3:7 布局并支持按模型名搜索、编辑、测试和新建', async ({ page }) => {
    let nextSessionIndex = 1
    let nextModelId = 3
    let lastModelListQuery = ''
    let lastTestPayload: Record<string, unknown> | null = null
    let models: ModelConfig[] = [
      {
        id: 1,
        custom_model_name: '通用对话模型',
        provider: 'openai',
        model_name: 'gpt-4o-mini',
        api_key: 'sk-openai-demo',
        base_url: 'https://api.openai.example/v1',
        updated_at: '2026-04-27T09:00:00',
      },
      {
        id: 2,
        custom_model_name: '意图路由模型',
        provider: 'doubao',
        model_name: 'doubao-seed-2-0-pro-260215',
        api_key: 'sk-doubao-demo',
        base_url: 'https://ark.example.com/api/v3',
        updated_at: '2026-04-27T08:00:00',
      },
    ]

    await page.route('**/api/**', async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const { pathname, searchParams } = url

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

      if (pathname === '/api/model-config/providers' && request.method() === 'GET') {
        await route.fulfill({ json: [] })
        return
      }
      if (pathname === '/api/model-config/providers/validate-draft' && request.method() === 'POST') {
        const payload = request.postDataJSON() as ProviderDraft
        await route.fulfill({
          json: {
            valid: true,
            provider_type: payload.provider_type,
            status_code: 200,
            message: 'ok',
          },
        })
        return
      }

      if (pathname === '/api/model-config/models' && request.method() === 'GET') {
        lastModelListQuery = url.search
        const keyword = (searchParams.get('keyword') || '').toLowerCase()
        const pageIndex = Number(searchParams.get('page') || '0')
        const pageSize = Number(searchParams.get('pageSize') || '20')
        const filtered = models.filter((item) => {
          if (!keyword) {
            return true
          }
          return (
            item.custom_model_name.toLowerCase().includes(keyword) ||
            item.model_name.toLowerCase().includes(keyword)
          )
        })
        const start = pageIndex * pageSize
        const end = start + pageSize
        await route.fulfill({
          json: {
            items: filtered.slice(start, end),
            page: pageIndex,
            page_size: pageSize,
            total: filtered.length,
          },
        })
        return
      }

      if (pathname === '/api/model-config/models' && request.method() === 'POST') {
        const payload = request.postDataJSON() as Omit<ModelConfig, 'id' | 'updated_at'>
        const created: ModelConfig = {
          id: nextModelId,
          updated_at: '2026-04-27T10:00:00',
          ...payload,
        }
        nextModelId += 1
        models = [created, ...models]
        await route.fulfill({ json: created })
        return
      }

      if (pathname === '/api/model-config/models/test' && request.method() === 'POST') {
        lastTestPayload = request.postDataJSON() as Record<string, unknown>
        await route.fulfill({
          json: {
            ok: true,
            provider: String(lastTestPayload.provider || ''),
            model_name: String(lastTestPayload.model_name || ''),
            status_code: 200,
            answer: 'connectivity ok',
          },
        })
        return
      }

      if (/^\/api\/model-config\/models\/\d+$/.test(pathname) && request.method() === 'PUT') {
        const id = Number(pathname.split('/').pop())
        const payload = request.postDataJSON() as Omit<ModelConfig, 'id' | 'updated_at'>
        models = models.map((item) =>
          item.id === id
            ? {
                ...item,
                ...payload,
                updated_at: '2026-04-27T10:30:00',
              }
            : item
        )
        await route.fulfill({ json: models.find((item) => item.id === id) })
        return
      }

      if (/^\/api\/model-config\/models\/\d+$/.test(pathname) && request.method() === 'DELETE') {
        const id = Number(pathname.split('/').pop())
        models = models.filter((item) => item.id !== id)
        await route.fulfill({ status: 204, body: '' })
        return
      }

      await route.fulfill({
        status: 404,
        json: { message: `Unhandled ${request.method()} ${pathname}` },
      })
    })

    await page.goto('/#models')

    await expect(page.getByTestId('models-page-layout')).toBeVisible()
    await expect(page.getByTestId('model-config-layout')).toBeVisible()
    await expect(page.getByTestId('model-config-sidebar')).toBeVisible()
    await expect(page.getByTestId('model-config-list')).toBeVisible()
    await expect(page.getByTestId('model-config-search-input')).toBeVisible()
    await expect(page.getByText('服务商配置')).toHaveCount(0)
    await expect(page.getByText('新建模型记录')).toHaveCount(0)

    await expect(page.getByLabel('自定义模型名')).toHaveAttribute('required', '')
    await expect(page.getByLabel('供应商')).toHaveAttribute('required', '')
    await expect(page.getByLabel('Model 名称（实际调用模型）')).toHaveAttribute('required', '')
    await expect(page.getByLabel('API Key（接口密钥）')).toHaveAttribute('required', '')
    await expect(page.getByLabel('Base URL（接口地址）')).toHaveAttribute('required', '')

    const sidebarBox = await page.getByTestId('model-config-sidebar').boundingBox()
    const listBox = await page.getByTestId('model-config-list').boundingBox()
    expect(sidebarBox).not.toBeNull()
    expect(listBox).not.toBeNull()
    const ratio = (sidebarBox?.width || 0) / ((sidebarBox?.width || 0) + (listBox?.width || 0))
    expect(ratio).toBeGreaterThan(0.25)
    expect(ratio).toBeLessThan(0.35)
    expect(sidebarBox?.height || 0).toBeGreaterThan(500)
    expect(listBox?.height || 0).toBeGreaterThan(500)

    await page.getByTestId('model-config-search-input').fill('gpt-4o')
    await page.getByTestId('model-config-search-apply').click()
    await expect(page.getByTestId('model-config-row-1')).toBeVisible()
    await expect(page.getByText('通用对话模型')).toBeVisible()
    expect(lastModelListQuery).toContain('keyword=gpt-4o')

    await page.getByTestId('model-config-row-1').click()
    await expect(page.getByLabel('自定义模型名')).toHaveValue('通用对话模型')
    await expect(page.getByLabel('供应商')).toHaveValue('openai')
    await expect(page.getByLabel('Model 名称（实际调用模型）')).toHaveValue('gpt-4o-mini')
    await expect(page.getByLabel('API Key（接口密钥）')).toHaveValue('sk-openai-demo')
    await expect(page.getByLabel('Base URL（接口地址）')).toHaveValue('https://api.openai.example/v1')
    await expect(page.getByText(/^已加载模型/)).toHaveCount(0)
    await expect(page.getByText(/^ID\s+\d+$/)).toHaveCount(0)

    const baseUrlBox = await page.getByLabel('Base URL（接口地址）').boundingBox()
    const saveButtonBox = await page.getByTestId('model-config-save').boundingBox()
    expect(baseUrlBox).not.toBeNull()
    expect(saveButtonBox).not.toBeNull()
    expect(saveButtonBox?.y || 0).toBeGreaterThan((baseUrlBox?.y || 0) + (baseUrlBox?.height || 0))
    expect(saveButtonBox?.y || 0).toBeLessThan((baseUrlBox?.y || 0) + (baseUrlBox?.height || 0) + 72)

    await page.getByLabel('自定义模型名').fill('通用对话模型-已编辑')
    await page.getByTestId('model-config-save').click()
    await expect(page.getByText('通用对话模型-已编辑')).toBeVisible()

    await page.getByTestId('model-config-test-call').click()
    await expect(page.getByText('测试返回：connectivity ok')).toBeVisible()
    expect(lastTestPayload?.model_name).toBe('gpt-4o-mini')

    await page.getByTestId('model-config-create').click()
    lastTestPayload = null
    await page.getByLabel('供应商').selectOption('deepseek')
    await page.getByLabel('Model 名称（实际调用模型）').fill('deepseek-chat')
    await page.getByLabel('API Key（接口密钥）').fill('sk-deepseek-demo')
    await page.getByLabel('Base URL（接口地址）').fill('https://api.deepseek.com/v1')
    await page.getByTestId('model-config-test-call').click()
    await expect(page.getByText('请完整填写自定义模型名、供应商、Model 名称、API Key 和 Base URL')).toBeVisible()
    expect(lastTestPayload).toBeNull()

    await page.getByLabel('自定义模型名').fill('结构化抽取模型')
    await page.getByTestId('model-config-save').click()
    await expect(page.getByText('结构化抽取模型')).toBeVisible()
  })
})
