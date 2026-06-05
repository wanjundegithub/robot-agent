import { expect, test } from '@playwright/test'

async function mockApiCenterApis(page: import('@playwright/test').Page) {
  let savedApiName = '查询用户'
  let savedHeaders: unknown = []
  let savedAuthMode: unknown = 'INHERIT'
  let savedAuthConfig: unknown = null
  let savedGroupAuthConfig: unknown = null
  let lastTestStatus = 'SUCCESS'
  let lastTestTime = '2026-05-31T10:00:00'
  let lastTestErrorMessage: string | null = null

  await page.route('**/api/api-center/groups', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = await route.request().postDataJSON()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          groupName: payload.groupName,
          description: payload.description,
          status: 'ENABLED',
          enabled: true,
          apiCount: 1,
          authType: 'BEARER',
          authPreview: 'Bearer ••••demo',
        }),
      })
      return
    }
    const groups = Array.from({ length: 10 }, (_, index) => ({
      id: index + 1,
      groupName: index === 0 ? '用户API组' : `接口组 ${index + 1}`,
      description: index === 0 ? '用户服务接口集合' : '分页接口组',
      status: 'ENABLED',
      enabled: true,
      apiCount: index === 0 ? 1 : 0,
      authType: index === 0 ? 'BEARER' : 'NO_AUTH',
      authPreview: index === 0 ? 'Bearer ••••demo' : 'No Auth',
    }))
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(groups),
    })
  })

  await page.route('**/api/api-center/groups/1/items', async (route) => {
    if (route.request().method() === 'POST') {
      const payload = await route.request().postDataJSON()
      savedApiName = payload.apiName
      savedHeaders = payload.headers
      savedAuthMode = payload.authMode
      savedAuthConfig = payload.authConfig
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 12,
          groupId: 1,
          apiName: savedApiName,
          description: payload.description,
          status: 'ENABLED',
          enabled: true,
          requestUrl: payload.requestUrl,
          requestMethod: payload.requestMethod,
          authMode: payload.authMode ?? 'INHERIT',
          authType: payload.authConfig?.authType ?? 'BEARER',
          authPreview: payload.authMode === 'CUSTOM' ? 'API Key header:X-API-Key' : 'Bearer ••••demo',
          inputSchema: payload.inputSchema,
          outputSchema: payload.outputSchema,
          lastTestStatus: null,
          lastTestTime: null,
        }),
      })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 11,
          groupId: 1,
          apiName: '查询用户',
          description: '按用户ID查询',
          status: 'ENABLED',
          enabled: true,
          requestUrl: 'https://example.com/users?userId={userId}',
          requestMethod: 'GET',
          authMode: 'INHERIT',
          authType: 'BEARER',
          authPreview: 'Bearer ••••demo',
          inputSchema: '{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false}',
          outputSchema: '{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":true}',
          lastTestStatus,
          lastTestTime,
          lastTestErrorMessage,
          lastTestToken: 'token-1',
        },
      ]),
    })
  })

  await page.route('**/api/api-center/groups/1/items/11', async (route) => {
    if (route.request().method() === 'DELETE') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '' })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 11,
        groupId: 1,
        apiName: '查询用户',
        description: '按用户ID查询',
        status: 'ENABLED',
        enabled: true,
        requestUrl: 'https://example.com/users?userId={userId}',
        requestMethod: 'GET',
        authMode: 'INHERIT',
        authType: 'BEARER',
        authPreview: 'Bearer ••••demo',
        authConfig: { authType: 'NO_AUTH', preview: 'No Auth' },
        headers: [{ key: 'Authorization', value: 'Bearer demo', enabled: true }, { key: 'Content-Type', value: 'application/json', enabled: true }],
        inputSchema: '{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false}',
        outputSchema: '{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":true}',
        lastTestStatus: 'SUCCESS',
        lastTestTime: '2026-05-31T10:00:00',
        lastTestToken: 'token-1',
        urlVariables: ['userId'],
      }),
    })
  })

  await page.route('**/api/api-center/groups/1/validate', async (route) => {
    const payload = await route.request().postDataJSON()
    const schemaIssue = (field: 'inputSchema' | 'outputSchema', label: string) => {
      const value = String(payload[field] ?? '').trim()
      if (!value) return []
      try {
        JSON.parse(value)
        return []
      } catch {
        return [{ field, message: `${label}格式不正确` }]
      }
    }
    const issues = [
      ...(String(payload.apiName ?? '').trim() ? [] : [{ field: 'apiName', message: 'API名称不能为空' }]),
      ...(String(payload.requestUrl ?? '').trim() ? [] : [{ field: 'requestUrl', message: '请求URL不能为空' }]),
      ...schemaIssue('inputSchema', '输入Schema'),
      ...schemaIssue('outputSchema', '输出Schema'),
    ]
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(issues.length === 0
        ? { valid: true, message: '校验通过', issues: [] }
        : { valid: false, message: '校验失败', issues }),
    })
  })

  await page.route('**/api/api-center/groups/1/auth-config', async (route) => {
    if (route.request().method() === 'PUT') {
      savedGroupAuthConfig = await route.request().postDataJSON()
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ authType: 'BEARER', authPreview: 'Bearer ••••demo', preview: 'Bearer ••••demo', configured: true }) })
      return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ authType: 'BEARER', authPreview: 'Bearer ••••demo', preview: 'Bearer ••••demo', configured: true }) })
  })

  await page.route('**/api/api-center/groups/1/test', async (route) => {
    const payload = await route.request().postDataJSON()
    const blocked = payload.urlVariables?.userId === 'blocked'
    lastTestStatus = blocked ? 'FAILED' : 'SUCCESS'
    lastTestTime = '2026-06-02T10:00:00'
    lastTestErrorMessage = blocked ? '禁止访问内网或本机地址' : null
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(blocked ? {
        success: false,
        testType: 'request',
        responsePayload: null,
        errorMessage: '禁止访问内网或本机地址',
        durationMs: null,
        testedAt: '2026-06-02T10:00:00',
        lastTestToken: null,
      } : {
        success: true,
        testType: 'request',
        responsePayload: JSON.stringify({ ok: true, userId: payload.urlVariables?.userId ?? null }),
        errorMessage: null,
        durationMs: 12,
        testedAt: '2026-06-02T10:00:00',
        lastTestToken: 'server-test-token',
      }),
    })
  })

  await page.route('**/api/api-center/groups/*/tests', async (route) => {
    throw new Error(`请求测试记录接口不应再被调用: ${route.request().url()}`)
  })

  await page.route('**/api/api-center/groups/*/audit-records', async (route) => {
    throw new Error(`API调用审计接口不应再被调用: ${route.request().url()}`)
  })

  return {
    savedApiName: () => savedApiName,
    savedHeaders: () => savedHeaders,
    savedAuthMode: () => savedAuthMode,
    savedAuthConfig: () => savedAuthConfig,
    savedGroupAuthConfig: () => savedGroupAuthConfig,
  }
}

test('api center removes capability type, api code and snapshot UI', async ({ page }) => {
  await mockApiCenterApis(page)

  await page.goto('/#api-center')

  await expect(page.getByRole('button', { name: 'API中心', exact: true })).toBeVisible()
  await expect(page.locator('aside').getByRole('button', { name: /用户API组/ })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增API', exact: true })).toBeVisible()
  await expect(page.getByText('能力类型')).toHaveCount(0)
  await expect(page.getByText('API 编码')).toHaveCount(0)
  await expect(page.getByText('发布快照')).toHaveCount(0)
  await expect(page.getByText('真实请求测试')).toHaveCount(0)
  await expect(page.getByText('请求测试记录')).toHaveCount(0)
  await expect(page.getByText('API调用审计')).toHaveCount(0)
  await expect(page.getByText('第 1 / 2 页')).toBeVisible()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.locator('aside').getByRole('button', { name: /接口组 9/ })).toBeVisible()
})

test('api group auth center saves bearer config', async ({ page }) => {
  const apiCenter = await mockApiCenterApis(page)

  await page.goto('/#api-center')
  await page.getByRole('button', { name: '新增API组' }).click()

  const dialog = page.locator('.form-overlay').last()
  await expect(dialog.getByText('鉴权中心', { exact: true })).toBeVisible()
  await dialog.locator('#api-group-name').fill('支付API组')
  await dialog.locator('#group-auth-type').selectOption('BEARER')
  await dialog.locator('#group-auth-token').fill('group-token')
  await dialog.getByRole('button', { name: '保存' }).click()

  await expect(dialog).toHaveCount(0)
  expect(apiCenter.savedGroupAuthConfig()).toMatchObject({ authType: 'BEARER', token: 'group-token' })
})

test('api center and dialogs fill available space with readable inputs', async ({ page }) => {
  await mockApiCenterApis(page)

  await page.goto('/#api-center')

  const panel = page.getByTestId('api-center-panel')
  const panelBox = await panel.boundingBox()
  const viewport = page.viewportSize()
  expect(panelBox?.width).toBeGreaterThan((viewport?.width ?? 0) * 0.9)
  expect(panelBox?.height).toBeGreaterThan((viewport?.height ?? 0) * 0.75)

  await page.getByRole('button', { name: '新增API组' }).click()
  const groupDialog = page.locator('.form-overlay').last()
  const groupDialogBox = await groupDialog.locator('[data-testid="api-center-modal-card"]').boundingBox()
  const groupNameBox = await groupDialog.locator('#api-group-name').boundingBox()
  const groupNameBackground = await groupDialog.locator('#api-group-name').evaluate((element) => getComputedStyle(element).backgroundColor)
  expect(groupNameBox?.width).toBeGreaterThan((groupDialogBox?.width ?? 0) * 0.8)
  expect(groupNameBackground).toBe('rgb(248, 250, 252)')
  await groupDialog.getByRole('button', { name: '取消' }).click()

  await page.getByRole('button', { name: '新增API', exact: true }).click()
  const apiDialog = page.locator('.form-overlay').last()
  const apiDialogBox = await apiDialog.locator('[data-testid="api-center-modal-card"]').boundingBox()
  const apiNameBox = await apiDialog.locator('#api-name').boundingBox()
  const apiNameBackground = await apiDialog.locator('#api-name').evaluate((element) => getComputedStyle(element).backgroundColor)
  expect(apiNameBox?.width).toBeGreaterThan((apiDialogBox?.width ?? 0) * 0.35)
  expect(apiNameBackground).toBe('rgb(248, 250, 252)')
  await apiDialog.locator('#api-url').fill('https://example.com/users/{userId}/orders/{orderId}')
  await expect(apiDialog.getByText('URL变量映射')).toBeVisible()
  await expect(apiDialog.locator('#url-var-userId')).toBeVisible()
  await expect(apiDialog.locator('#url-var-orderId')).toBeVisible()
  await expect(apiDialog.locator('#url-var-userId')).toHaveValue('userId')
  await expect(apiDialog.locator('#url-var-orderId')).toHaveValue('orderId')
  const urlBox = await apiDialog.locator('#api-url').boundingBox()
  const variableBox = await apiDialog.getByText('URL变量映射').boundingBox()
  const userIdBox = await apiDialog.locator('#url-var-userId').boundingBox()
  const orderIdBox = await apiDialog.locator('#url-var-orderId').boundingBox()
  const headersBox = await apiDialog.getByText('Headers', { exact: true }).boundingBox()
  expect(variableBox?.y).toBeGreaterThan(urlBox?.y ?? 0)
  expect(orderIdBox?.y).toBeGreaterThan(userIdBox?.y ?? 0)
  expect(headersBox?.y).toBeGreaterThan(variableBox?.y ?? 0)
})

test('api editor validates required fields only when saving', async ({ page }) => {
  const apiCenter = await mockApiCenterApis(page)

  await page.goto('/#api-center')
  await page.getByRole('button', { name: '新增API', exact: true }).click()

  const dialog = page.locator('.form-overlay').last()
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('请求体测试参数 JSON')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '校验Schema' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '请求测试' })).toHaveCount(0)
  await expect(dialog.getByText('Schema 校验通过')).toHaveCount(0)
  await expect(dialog.getByText('保存前需要等待')).toHaveCount(0)

  const apiNameBox = await dialog.locator('#api-name').boundingBox()
  const methodBox = await dialog.locator('#api-method').boundingBox()
  const descriptionBox = await dialog.locator('#api-description').boundingBox()
  expect(descriptionBox?.y).toBeGreaterThan(apiNameBox?.y ?? 0)
  expect(descriptionBox?.y).toBeGreaterThan(methodBox?.y ?? 0)

  await dialog.getByRole('button', { name: '保存' }).click()
  const toast = page.getByTestId('api-validation-toast')
  await expect(toast).toBeVisible()
  await expect(toast).toHaveCSS('display', 'flex')
  await expect(toast).toContainText('apiName: API名称不能为空')
  await expect(toast).toContainText('requestUrl: 请求URL不能为空')
  await expect(dialog.getByText('apiName: API名称不能为空')).toHaveCount(0)
  await expect(dialog.getByText('requestUrl: 请求URL不能为空')).toHaveCount(0)
  await expect(toast).toHaveCount(0, { timeout: 4000 })

  await dialog.locator('#api-name').fill('创建用户')
  await dialog.locator('#api-url').fill('https://example.com/users')
  await dialog.getByRole('button', { name: '新增Header' }).click()
  await dialog.getByLabel('Header Key 1').fill('Authorization')
  await dialog.getByLabel('Header Value 1').fill('Bearer demo')
  await dialog.locator('#input-schema').fill('')
  await dialog.locator('#output-schema').fill('')

  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog).toHaveCount(0)
  expect(apiCenter.savedApiName()).toBe('创建用户')
  expect(apiCenter.savedHeaders()).toEqual([{ key: 'Authorization', value: 'Bearer demo', enabled: true }])
})

test('api editor keeps validation quiet while typing and clears errors after successful save validation', async ({ page }) => {
  await mockApiCenterApis(page)

  await page.goto('/#api-center')
  await page.getByRole('button', { name: '新增API', exact: true }).click()

  const dialog = page.locator('.form-overlay').last()
  await dialog.locator('#api-name').fill('查询就绪状态')
  await dialog.locator('#input-schema').fill('{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false}')
  await dialog.locator('#output-schema').fill('{"$schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":true}')
  await expect(dialog.getByText('requestUrl: 请求URL不能为空')).toHaveCount(0)

  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByTestId('api-validation-toast')).toContainText('requestUrl: 请求URL不能为空')
  await dialog.locator('#api-url').fill('http://localhost:8080/api/operations/readiness')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog).toHaveCount(0)
})

test('saved api item supports right-click edit delete and item-level request test', async ({ page }) => {
  await mockApiCenterApis(page)

  await page.goto('/#api-center')

  const item = page.getByTestId('api-item-11')
  await expect(item.getByRole('button', { name: '请求测试' })).toBeVisible()
  await expect(item.getByRole('button', { name: '编辑' })).toBeVisible()
  await expect(item.getByRole('button', { name: '删除' })).toBeVisible()

  await item.getByRole('button', { name: '编辑' }).click()
  const editor = page.locator('.form-overlay').last()
  await expect(editor).toBeVisible()
  await expect(editor.getByText('输入Schema（仅Body，Draft-07）')).toBeVisible()
  await expect(editor.getByText('Headers', { exact: true })).toBeVisible()
  await expect(editor.getByLabel('Header Key 1')).toHaveValue('Authorization')
  await expect(editor.getByLabel('Header Value 1')).toHaveValue('Bearer demo')
  await editor.getByRole('button', { name: '取消' }).click()

  await item.getByRole('button', { name: '请求测试' }).click()
  const testDialog = page.locator('.form-overlay').last()
  await expect(testDialog.getByText('请求测试', { exact: true })).toBeVisible()
  await expect(testDialog.locator('#test-url-var-userId')).toBeVisible()
  await expect(testDialog.locator('#test-body-json')).toBeVisible()
  await testDialog.locator('#test-url-var-userId').fill('u-1')
  await testDialog.getByRole('button', { name: '开始测试' }).click()
  await expect(page.getByText('请求测试通过')).toBeVisible()

  await item.getByRole('button', { name: '请求测试' }).click()
  const failedTestDialog = page.locator('.form-overlay').last()
  await expect(failedTestDialog.getByText('请求测试会访问真实URL')).toHaveCount(0)
  await failedTestDialog.locator('#test-url-var-userId').fill('blocked')
  await failedTestDialog.getByRole('button', { name: '开始测试' }).click()
  const failureToast = page.getByTestId('api-validation-toast')
  await expect(failureToast).toContainText('请求测试失败：禁止访问内网或本机地址')
  await expect(page.getByTestId('api-item-11').getByText('测试状态：失败')).toBeVisible()
  await expect(page.getByTestId('api-item-11').getByText('最近错误：禁止访问内网或本机地址')).toHaveCount(0)
  await expect(page.locator('[data-testid="api-center-panel"] > div').getByText('请求测试失败')).toHaveCount(0)
  await failedTestDialog.getByRole('button', { name: '取消' }).click()

  page.once('dialog', async (confirmDialog) => {
    await confirmDialog.accept()
  })
  await item.getByRole('button', { name: '删除' }).click()
})

test('api auth strategy allows manual auth headers and custom api key payload', async ({ page }) => {
  const apiCenter = await mockApiCenterApis(page)

  await page.goto('/#api-center')
  await page.getByRole('button', { name: '新增API', exact: true }).click()

  const dialog = page.locator('.form-overlay').last()
  await expect(dialog.getByText('鉴权策略')).toBeVisible()
  await expect(dialog.locator('#api-auth-mode')).toHaveValue('INHERIT')
  await dialog.locator('#api-auth-mode').selectOption('NONE')
  await expect(dialog.getByText('Headers 仍可填写 Authorization')).toBeVisible()
  await dialog.locator('#api-name').fill('免鉴权但保留Header')
  await dialog.locator('#api-url').fill('https://example.com/no-auth')
  await dialog.getByRole('button', { name: '新增Header' }).click()
  await dialog.getByLabel('Header Key 1').fill('Authorization')
  await dialog.getByLabel('Header Value 1').fill('Bearer manual')
  await dialog.locator('#input-schema').fill('')
  await dialog.locator('#output-schema').fill('')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(dialog).toHaveCount(0)
  expect(apiCenter.savedAuthMode()).toBe('NONE')
  expect(apiCenter.savedHeaders()).toEqual([{ key: 'Authorization', value: 'Bearer manual', enabled: true }])

  await page.getByRole('button', { name: '新增API', exact: true }).click()
  const customDialog = page.locator('.form-overlay').last()
  await customDialog.locator('#api-auth-mode').selectOption('CUSTOM')
  await customDialog.locator('#api-auth-type').selectOption('API_KEY')
  await customDialog.locator('#api-auth-key').fill('X-API-Key')
  await customDialog.locator('#api-auth-value').fill('secret-key')
  await customDialog.locator('#api-name').fill('自定义APIKey')
  await customDialog.locator('#api-url').fill('https://example.com/key')
  await customDialog.locator('#input-schema').fill('')
  await customDialog.locator('#output-schema').fill('')
  await customDialog.getByRole('button', { name: '保存' }).click()
  await expect(customDialog).toHaveCount(0)
  expect(apiCenter.savedAuthMode()).toBe('CUSTOM')
  expect(apiCenter.savedAuthConfig()).toMatchObject({ authType: 'API_KEY', key: 'X-API-Key', value: 'secret-key', addTo: 'HEADER' })
})
