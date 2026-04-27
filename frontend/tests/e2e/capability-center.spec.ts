import { expect, test } from '@playwright/test'

type MockCapabilityType = 'API' | 'SKILL' | 'MCP'

async function mockCapabilityCenterApis(
  page: import('@playwright/test').Page,
  options: {
    capabilityType?: MockCapabilityType
  } = {}
) {
  const capabilityType = options.capabilityType ?? 'API'
  await page.route('**/api/capabilities/groups', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 1,
          groupName: '工作流能力',
          description: '调用工作流 API 的能力集合',
          status: 'PUBLISHED',
          capabilityCount: 1,
          latestSnapshotVersion: 'v20260426120000',
        },
      ]),
    })
  })

  await page.route('**/api/capabilities/groups/1/items', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 11,
          groupId: 1,
          capabilityCode: 'workflow_release_fetch',
          capabilityName: '获取已发布版本',
          capabilityType,
          status: 'PUBLISHED',
          draftVersion: 'draft',
          publishedVersion: 'v20260426115900',
          lastTestStatus: 'SUCCESS',
          lastTestTime: '2026-04-26T11:59:00',
          description: '读取工作流发布版本列表',
          authConfigId: 21,
        },
      ]),
    })
  })

  await page.route('**/api/capabilities/groups/1/snapshots', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 31,
          groupId: 1,
          snapshotVersion: 'published',
          status: 'PUBLISHED',
          description: 'current published snapshot',
          publishedAt: '2026-04-26T12:00:00',
        },
      ]),
    })
  })

  await page.route('**/api/capabilities/groups/1/snapshots/publish', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 32,
        groupId: 1,
        snapshotVersion: 'published',
        status: 'PUBLISHED',
        description: 'published from test',
        publishedAt: '2026-04-26T12:30:00',
      }),
    })
  })

  await page.route('**/api/capabilities/groups/1/auth-configs', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 21,
          authName: '工作流网关 Token',
          authType: 'JWT',
          scope: 'GROUP',
          status: 'ACTIVE',
          maskedPreview: '已配置 / 已脱敏',
        },
      ]),
    })
  })

  await page.route('**/api/capabilities/groups/1/items/workflow_release_fetch/versions', async (route) => {
    const definitionJson =
      capabilityType === 'API'
        ? JSON.stringify({
            url: 'http://127.0.0.1:8080/api/workflows/published',
            method: 'GET',
            headers: {
              Accept: 'application/json',
            },
          })
        : capabilityType === 'SKILL'
          ? JSON.stringify({
              skill_name: 'workflow.release.fetch',
              skill_source: 'builtin',
              executor_type: 'sync',
              endpoint: 'skills/workflow.release.fetch',
              allowed_capabilities: ['workflow_release_fetch'],
            })
          : JSON.stringify({
              server_url: 'http://127.0.0.1:3001/sse',
              protocol: 'sse',
              tool_name: 'workflow_release_fetch',
            })
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 11,
          groupId: 1,
          capabilityCode: 'workflow_release_fetch',
          capabilityName: '获取已发布版本',
          capabilityType,
          version: 'draft',
          status: 'DRAFT',
          description: '读取工作流发布版本列表',
          definitionJson,
          inputSchema: capabilityType === 'API' ? JSON.stringify({ type: 'object', properties: {} }, null, 2) : null,
          outputSchema: capabilityType === 'API' ? JSON.stringify({ type: 'array' }, null, 2) : null,
          authConfigId: 21,
        },
      ]),
    })
  })
}

test('capability center hides group codes and business codes', async ({ page }) => {
  await mockCapabilityCenterApis(page)

  await page.goto('/#capability-center')

  await expect(page.getByRole('button', { name: /工作流能力/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /调用工作流 API 的能力集合/ })).toBeVisible()
  await expect(page.getByText('cap_group')).toHaveCount(0)
  await expect(page.getByText('业务编码')).toHaveCount(0)

  await page.getByRole('button', { name: '编辑能力组' }).click()
  const groupDialog = page.locator('.form-overlay').last()
  await expect(groupDialog).toBeVisible()
  await expect(groupDialog.getByText('能力组名称')).toBeVisible()
  await expect(groupDialog.getByText('业务编码')).toHaveCount(0)
})

test('editing a capability writes back its saved auth binding and removes inherit option', async ({ page }) => {
  await mockCapabilityCenterApis(page)

  await page.goto('/#capability-center')
  await page.getByRole('button', { name: '编辑', exact: true }).click()

  const capabilityDialog = page.locator('.form-overlay').last()
  await expect(capabilityDialog).toBeVisible()

  const authBinding = capabilityDialog.getByLabel('认证绑定')
  await expect(authBinding).toHaveValue('21')
  await expect(authBinding.locator('option')).toHaveCount(1)
  await expect(authBinding.locator('option')).toHaveText(['工作流网关 Token'])
  await expect(capabilityDialog.getByText('继承能力组默认认证')).toHaveCount(0)
})
test('editing auth config without changing json does not overwrite saved config payload', async ({ page }) => {
  await mockCapabilityCenterApis(page)

  let requestBody: Record<string, unknown> | null = null
  await page.route('**/api/capabilities/groups/1/auth-configs/21', async (route) => {
    requestBody = route.request().postDataJSON() as Record<string, unknown>
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 21,
        authName: 'Gateway Token Updated',
        authType: 'JWT',
        scope: 'GROUP',
        status: 'ACTIVE',
        maskedPreview: 'configured',
      }),
    })
  })

  await page.goto('/#capability-center')
  await page.locator('button.rounded-full').nth(1).click()
  await page.locator('.grid.gap-3.lg\\:grid-cols-2 .prompt-secondary').first().click()

  const authDialog = page.locator('.form-overlay').last()
  await expect(authDialog).toBeVisible()
  await authDialog.locator('#auth-name').fill('Gateway Token Updated')
  await authDialog.locator('.prompt-primary').click()

  await expect.poll(() => requestBody).not.toBeNull()
  await expect(requestBody).not.toHaveProperty('config')
  await expect(requestBody).toMatchObject({
    id: 21,
    authName: 'Gateway Token Updated',
    authType: 'JWT',
  })
})

test('capability center shows snapshots tab and published snapshot list', async ({ page }) => {
  await mockCapabilityCenterApis(page)

  await page.goto('/#capability-center')

  await expect(page.locator('button.rounded-full')).toHaveCount(3)
  await page.locator('button.rounded-full').nth(2).click()
  await expect(page.getByText('published', { exact: true })).toBeVisible()
  await expect(page.getByText('current published snapshot')).toBeVisible()
})

test('skill capability editor uses type-specific fields and hides schemas', async ({ page }) => {
  await mockCapabilityCenterApis(page, { capabilityType: 'SKILL' })

  await page.goto('/#capability-center')
  await page.locator('tbody .prompt-secondary').first().click()

  const capabilityDialog = page.locator('.form-overlay').last()
  await expect(capabilityDialog).toBeVisible()
  await expect(capabilityDialog.locator('#capability-type')).toHaveValue('SKILL')
  await expect(capabilityDialog.locator('#skill-name')).toBeVisible()
  await expect(capabilityDialog.locator('#skill-source')).toBeVisible()
  await expect(capabilityDialog.locator('#executor-type')).toBeVisible()
  await expect(capabilityDialog.locator('#skill-endpoint')).toBeVisible()
  await expect(capabilityDialog.locator('#allowed-capabilities')).toBeVisible()
  await expect(capabilityDialog.locator('#api-url')).toHaveCount(0)
  await expect(capabilityDialog.locator('#input-schema')).toHaveCount(0)
  await expect(capabilityDialog.locator('#output-schema')).toHaveCount(0)
})
