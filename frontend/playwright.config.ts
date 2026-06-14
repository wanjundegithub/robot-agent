import { defineConfig, devices } from '@playwright/test'

const executablePath = process.env.PLAYWRIGHT_BROWSER_EXECUTABLE_PATH
const channel = process.env.PLAYWRIGHT_BROWSER_CHANNEL
const host = process.env.PLAYWRIGHT_HOST || '127.0.0.1'
const port = process.env.PLAYWRIGHT_PORT || '5173'
const baseURL = process.env.PLAYWRIGHT_BASE_URL || `http://${host}:${port}`

export default defineConfig({
  testDir: './tests/e2e',
  use: {
    baseURL,
    trace: 'on-first-retry',
  },
  webServer: {
    command: `npm run dev -- --host ${host} --port ${port}`,
    url: `${baseURL}/index.html`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(channel ? { channel } : {}),
        ...(executablePath ? { launchOptions: { executablePath } } : {}),
      },
    },
  ],
})
