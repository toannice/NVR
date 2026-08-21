import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['html'], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    // Trỏ cứng một IP LAN thì test chết mỗi lần máy đổi mạng (đã dính 15-08-2026:
    // config còn 192.168.2.106 trong khi máy đã sang 192.168.1.x). Mặc định localhost —
    // test hầu như luôn chạy trên chính máy NVR; máy khác thì đặt BASE_URL.
    baseURL: process.env.BASE_URL || 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
