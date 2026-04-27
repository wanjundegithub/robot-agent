import { expect, test } from '@playwright/test'

type ModelProvider = {
  provider_code: string
  provider_name: string
  provider_type: string
  base_url: string
  enabled: boolean
}

type ModelRecord = {
  model_code: string
  model_name: string
  provider_code: string
  provider_name: string
  provider_type: string
  upstream_model_code: string
  capabilities: string[]
  default_system_prompt?: string
  default_options?: Record<string, unknown>
  enabled: boolean
  updated_at: string
}

test.describe('model config redesign', () => {
  test('uses provider + model-record workspace and supports CRUD/filter actions', async ({ page }) => {
    let nextSessionIndex = 1
    let lastModelListQuery = ''
    let records: ModelRecord[] = [
      {
        model_code: 'intent-router',
        model_name: '意图路由模型',
        provider_code: 'provider-a',
        provider_name: '豆包生产',
        provider_type: 'doubao',
        upstream_model_code: 'doubao-seed-2-0-pro-260215',
        capabilities: ['text', 'json'],
        default_system_prompt: '',
        default_options: { temperature: 0.1 },
        enabled: true,
        updated_at: '2026-04-26T21:00:00',
      },
      {
        model_code: 'general-chat',
        model_name: '通用对话',
        provider_code: 'provider-b',
        provider_name: 'OpenRouter',
        provider_type: 'openai_compatible',
        upstream_model_code: 'gpt-4o-mini',
        capabilities: ['text', 'stream'],
        default_system_prompt: '',
        default_options: { temperature: 0.3 },
        enabled: true,
        updated_at: '2026-04-26T22:00:00',
      },
    ]
    let providers: ModelProvider[] = [
      {
        provider_code: 'provider-a',
        provider_name: '豆包生产',
        provider_type: 'doubao',
        base_url: 'https://ark.example.com',
        enabled: true,
      },
      {
        provider_code: 'provider-b',
        provider_name: 'OpenRouter',
        provider_type: 'openai_compatible',
        base_url: 'https://api.openrouter.ai/v1',
        enabled: true,
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
        await route.fulfill({ json: providers })
        return
      }
      if (pathname === '/api/model-config/providers' && request.method() === 'POST') {
        const payload = request.postDataJSON() as ModelProvider
        providers = [...providers, payload]
        await route.fulfill({ json: payload })
        return
      }
      if (pathname.startsWith('/api/model-config/providers/') && request.method() === 'PUT') {
        const providerCode = pathname.split('/').pop() || ''
        const payload = request.postDataJSON() as Partial<ModelProvider>
        providers = providers.map((item) => (item.provider_code === providerCode ? { ...item, ...payload } : item))
        await route.fulfill({ json: providers.find((item) => item.provider_code === providerCode) })
        return
      }
      if (pathname.startsWith('/api/model-config/providers/') && request.method() === 'DELETE') {
        const providerCode = pathname.split('/').pop() || ''
        providers = providers.filter((item) => item.provider_code !== providerCode)
        await route.fulfill({ status: 204, body: '' })
        return
      }
      if (pathname === '/api/model-config/providers/validate-draft' && request.method() === 'POST') {
        await route.fulfill({
          json: {
            valid: true,
            provider_code: 'draft',
            message: 'ok',
            status_code: 200,
            tested_model_code: 'test-model',
          },
        })
        return
      }

      if (pathname === '/api/model-config/models' && request.method() === 'GET') {
        lastModelListQuery = url.search
        const keyword = (searchParams.get('keyword') || '').toLowerCase()
        const providerCode = searchParams.get('providerCode') || ''
        const enabledParam = searchParams.get('enabled')
        const enabledFilter =
          enabledParam === 'true' ? true : enabledParam === 'false' ? false : undefined
        const page = Number(searchParams.get('page') || '0')
        const pageSize = Number(searchParams.get('pageSize') || '10')

        const filtered = records.filter((item) => {
          const keywordMatch =
            !keyword ||
            item.model_code.toLowerCase().includes(keyword) ||
            item.model_name.toLowerCase().includes(keyword) ||
            item.upstream_model_code.toLowerCase().includes(keyword)
          const providerMatch = !providerCode || item.provider_code === providerCode
          const enabledMatch = typeof enabledFilter !== 'boolean' || item.enabled === enabledFilter
          return keywordMatch && providerMatch && enabledMatch
        })

        const start = page * pageSize
        const end = start + pageSize
        const items = filtered.slice(start, end)
        await route.fulfill({
          json: {
            items,
            page,
            page_size: pageSize,
            total: filtered.length,
          },
        })
        return
      }
      if (pathname === '/api/model-config/models' && request.method() === 'POST') {
        const payload = request.postDataJSON() as ModelRecord
        records = [{ ...payload, updated_at: '2026-04-27T00:00:00' }, ...records]
        await route.fulfill({ json: records[0] })
        return
      }
      if (pathname.startsWith('/api/model-config/models/') && request.method() === 'PUT') {
        const modelCode = pathname.split('/').pop() || ''
        const payload = request.postDataJSON() as Partial<ModelRecord>
        records = records.map((item) =>
          item.model_code === modelCode ? { ...item, ...payload, updated_at: '2026-04-27T00:01:00' } : item
        )
        await route.fulfill({ json: records.find((item) => item.model_code === modelCode) })
        return
      }
      if (pathname.startsWith('/api/model-config/models/') && request.method() === 'DELETE') {
        const modelCode = pathname.split('/').pop() || ''
        records = records.filter((item) => item.model_code !== modelCode)
        await route.fulfill({ status: 204, body: '' })
        return
      }

      await route.fulfill({
        status: 404,
        json: { message: `Unhandled ${request.method()} ${pathname}` },
      })
    })

    await page.goto('/#models')

    await expect(page.getByText('业务模型配置')).toHaveCount(0)
    await expect(page.getByText('默认 OpenAI 提供方')).toHaveCount(0)
    await expect(page.getByTestId('model-config-layout')).toBeVisible()
    await expect(page.getByTestId('model-provider-panel')).toBeVisible()
    await expect(page.getByTestId('model-record-list')).toBeVisible()
    await expect(page.getByTestId('model-record-pagination')).toBeVisible()
    await expect(page.getByText('通用对话')).toBeVisible()

    await page.getByTestId('model-record-search-input').fill('router')
    await page.getByTestId('model-record-provider-filter').selectOption('provider-a')
    await page.getByTestId('model-record-enabled-filter').selectOption('true')
    await page.getByTestId('model-record-search-apply').click()
    await expect(page.getByText('意图路由模型')).toBeVisible()
    expect(lastModelListQuery).toContain('keyword=router')
    expect(lastModelListQuery).toContain('providerCode=provider-a')
    expect(lastModelListQuery).toContain('enabled=true')

    await page.getByTestId('model-record-search-input').fill('')
    await page.getByTestId('model-record-provider-filter').selectOption('')
    await page.getByTestId('model-record-enabled-filter').selectOption('all')
    await page.getByTestId('model-record-search-apply').click()

    await page.getByTestId('model-record-create').click()
    await page.getByTestId('model-record-form-model-code').fill('structured-extract')
    await page.getByTestId('model-record-form-model-name').fill('结构化抽取')
    await page.getByTestId('model-record-form-provider-code').selectOption('provider-a')
    await page.getByTestId('model-record-form-upstream-code').fill('doubao-seed-2-0-pro-260215')
    await page.getByTestId('model-record-form-capabilities').fill('text,json')
    await page.getByTestId('model-record-form-save').click()
    await expect(page.getByText('结构化抽取')).toBeVisible()

    await page.getByTestId('model-record-row-structured-extract').click()
    await page.getByTestId('model-record-form-model-name').fill('结构化抽取（编辑）')
    await page.getByTestId('model-record-form-save').click()
    await expect(page.getByText('结构化抽取（编辑）')).toBeVisible()

    await page.getByTestId('model-record-toggle-general-chat').click()
    await page.getByTestId('model-record-enabled-filter').selectOption('false')
    await page.getByTestId('model-record-search-apply').click()
    await expect(page.getByTestId('model-record-row-general-chat')).toBeVisible()

    await page.getByTestId('model-record-delete-general-chat').click()
    await expect(page.getByTestId('model-record-row-general-chat')).toHaveCount(0)
  })
})
