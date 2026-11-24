import { expect, test } from '@playwright/test'
import { messages, openBook, send, SETTINGS, startReader, waitFor } from './harness'

test('says ready, then opens a book with its contents, direction and title', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'basic')
    const types = (await messages(page)).map(m => m.type)
    expect(types[0]).toBe('ready')
    expect(types.indexOf('opened')).toBeLessThan(types.indexOf('relocated'))
    expect(opened.toc).toEqual([
        { id: 0, label: 'The Green Lighthouse', href: 'OEBPS/ch1.xhtml', depth: 0 },
        { id: 1, label: 'The lamp room', href: 'OEBPS/ch1.xhtml#lamp', depth: 1 },
        { id: 2, label: 'The Missing Gull', href: 'OEBPS/ch2.xhtml', depth: 0 },
        { id: 3, label: 'The Morning Boat', href: 'OEBPS/ch3.xhtml', depth: 0 },
        { id: 4, label: 'The reply', href: 'OEBPS/ch3.xhtml#reply', depth: 1 },
    ])
    expect(opened.dir).toBe('ltr')
    expect(opened.fixedLayout).toBe(false)
    expect(opened.title).toBe('The Ledger of Port Vesper')
})

test('a book with no title and no contents opens with an empty list and a null title', async ({ page }) => {
    await startReader(page)
    const { opened, relocated } = await openBook(page, 'bare')
    expect(opened.toc).toEqual([])
    expect(opened.title).toBeNull()
    expect(opened.dir).toBe('ltr')
    expect(relocated.tocId).toBeNull()
    expect(relocated.tocLabel).toBeNull()
    expect(relocated.sectionCount).toBe(2)
})

test('a base with no index reports open_failed', async ({ page }) => {
    await startReader(page)
    await send(page, { type: 'open', base: '/book/nowhere/', cfi: null, fraction: null, settings: SETTINGS })
    const error = await waitFor(page, 'error')
    expect(error.code).toBe('open_failed')
    expect(typeof error.message).toBe('string')
    expect(error.message.length).toBeGreaterThan(0)
    expect((await messages(page)).some(m => m.type === 'opened')).toBe(false)
})

test('a book whose package file is missing reports open_failed', async ({ page }) => {
    await startReader(page, { missing: ['basic/OEBPS/content.opf'] })
    await send(page, { type: 'open', base: '/book/basic/', cfi: null, fraction: null, settings: SETTINGS })
    const error = await waitFor(page, 'error')
    expect(error.code).toBe('open_failed')
})

test('a second book can be opened after the first', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const { opened, relocated } = await openBook(page, 'rtl')
    expect(opened.title).toBe('Le registre de Port-Vesprée')
    expect(relocated.moved).toBe(false)
    expect(relocated.sectionCount).toBe(2)
})

test('unknown and malformed messages are dropped without breaking the page', async ({ page }) => {
    await startReader(page)
    await page.evaluate(() => (window as any).lirelle.receive('not json'))
    await send(page, { type: 'somethingElse' })
    const { opened } = await openBook(page, 'basic')
    expect(opened.type).toBe('opened')
})

test('a web view too old for the engine reports unsupported_engine instead of ready', async ({ page }) => {
    await page.addInitScript(() => { delete (Object as any).groupBy })
    await startReader(page, { ready: false })
    const error = await waitFor(page, 'error')
    expect(error.code).toBe('unsupported_engine')
    expect(error.message).toContain('Object.groupBy')
    expect((await messages(page)).some(m => m.type === 'ready')).toBe(false)
    // Asking to open a book repeats the answer.
    await send(page, { type: 'open', base: '/book/basic/', cfi: null, fraction: null, settings: SETTINGS })
    await expect.poll(async () => (await messages(page)).filter(m => m.type === 'error').length).toBe(2)
})
