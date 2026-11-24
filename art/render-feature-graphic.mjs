// Renders art/feature-graphic.html to the store listings' featureGraphic.png, with the Chromium of reader-tests'
// Playwright: cd reader-tests && npm ci && node ../art/render-feature-graphic.mjs
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// Playwright is reader-tests' own: taken from where the script is run.
const { chromium } = createRequire(path.join(process.cwd(), 'package.json'))('@playwright/test')

const art = path.dirname(fileURLToPath(import.meta.url))
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 })
await page.goto('file://' + path.join(art, 'feature-graphic.html'))
await page.evaluate(() => document.fonts.ready)
for (const locale of ['en-US', 'fr-FR']) {
    if (locale === 'fr-FR') {
        await page.evaluate(() => {
            document.documentElement.lang = 'fr'
            for (const element of document.querySelectorAll('[data-fr]')) element.textContent = element.dataset.fr
        })
    }
    const out = path.join(art, '..', 'fastlane/metadata/android', locale, 'images/featureGraphic.png')
    await page.locator('.graphic').screenshot({ path: out })
    console.log(out)
}
await browser.close()
