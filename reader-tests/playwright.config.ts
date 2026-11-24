import { defineConfig } from '@playwright/test'

// A phone-sized touch screen, as the page will see in the app.
export default defineConfig({
    testDir: './tests',
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: 0,
    timeout: 30_000,
    expect: { timeout: 10_000 },
    reporter: process.env.CI ? [['list'], ['junit', { outputFile: 'test-results/junit.xml' }]] : 'list',
    use: {
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 1,
        hasTouch: true,
        isMobile: true,
        trace: 'retain-on-failure',
    },
})
