import { test, expect } from '@playwright/test'

test.describe('聊天流程', () => {
  test('用户发送消息并接收回复', async ({ page }) => {
    await page.goto('http://localhost:3000')

    await page.fill('[placeholder*="输入"]', '你好')
    await page.press('[placeholder*="输入"]', 'Enter')

    await expect(page.locator('text=您').last()).toBeVisible()
    await expect(page.locator('text=机器人').last()).toBeVisible()
  })

  test('欢迎消息显示', async ({ page }) => {
    await page.goto('http://localhost:3000')

    await expect(page.locator('text=欢迎使用服务机器人')).toBeVisible()
  })

  test('发送按钮禁用状态', async ({ page }) => {
    await page.goto('http://localhost:3000')

    const sendButton = page.locator('button[type="submit"]')
    await expect(sendButton).toBeDisabled()
  })
})
