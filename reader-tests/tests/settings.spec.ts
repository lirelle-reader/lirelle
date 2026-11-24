import { expect, test } from '@playwright/test'
import {
    contentFrame, mark, openBook, pixelAt, relocatedAfter, send, sendAndRelocate, SETTINGS, settle, startReader,
} from './harness'

const rgb = (hex: string) => {
    const n = parseInt(hex.slice(1), 16)
    return `rgb(${n >> 16}, ${(n >> 8) & 255}, ${n & 255})`
}

test('the layout switches to scrolled and back without losing the position', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    await sendAndRelocate(page, { type: 'next' })
    const before = await sendAndRelocate(page, { type: 'next' })
    expect(before.page).toBe(3)

    let from = await mark(page)
    await send(page, { type: 'settings', settings: { ...SETTINGS, flow: 'scrolled' } })
    const scrolled = await relocatedAfter(page, from)
    expect(scrolled.page).toBeNull()
    expect(scrolled.pages).toBeNull()
    expect(scrolled.sectionIndex).toBe(1)
    expect(scrolled.tocId).toBe(before.tocId)
    expect(Math.abs(scrolled.fraction - before.fraction)).toBeLessThan(0.1)

    from = await mark(page)
    await send(page, { type: 'settings', settings: SETTINGS })
    const paged = await relocatedAfter(page, from)
    expect(paged.sectionIndex).toBe(1)
    expect(paged.page).toBe(before.page)
    expect(paged.pages).toBe(before.pages)
    expect(paged.cfi).toBe(before.cfi)
})

test('in scrolled layout the end is reported at the bottom of the last chapter', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    expect(relocated.page).toBeNull()
    expect(relocated.atEnd).toBe(false)
    const top = await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    expect(top.atEnd).toBe(false)
    let latest = top
    for (let i = 0; i < 30 && !latest.atEnd; i++) latest = await sendAndRelocate(page, { type: 'next' })
    expect(latest.atEnd).toBe(true)
    expect(latest.sectionIndex).toBe(2)
    expect(latest.moved).toBe(true)
    // The engine counts a scrolled place from its top: the bottom of the book is the whole of it.
    expect(latest.fraction).toBe(1)
})

test('the font size and the colours reach the book', async ({ page }) => {
    await startReader(page)
    const dark = { background: '#101418', foreground: '#d8dee4', link: '#7ab7ff', dark: true, fontSizePx: 23 }
    await openBook(page, 'basic', { settings: dark })
    const frame = await contentFrame(page)
    const read = () => frame.evaluate(() => {
        const style = (el: Element) => getComputedStyle(el)
        return {
            rootSize: style(document.documentElement).fontSize,
            paragraphSize: style(document.querySelector('p')!).fontSize,
            text: style(document.querySelector('p')!).color,
            heading: style(document.querySelector('h1')!).color,
            background: style(document.body).backgroundColor,
            link: style(document.querySelector('a')!).color,
            lineHeight: parseFloat(style(document.querySelector('p')!).lineHeight),
            selection: style(document.body).userSelect,
            sizeAdjust: style(document.documentElement).getPropertyValue('text-size-adjust')
                || style(document.documentElement).getPropertyValue('-webkit-text-size-adjust'),
        }
    })
    const seen = await read()
    expect(seen.rootSize).toBe('23px')
    expect(seen.paragraphSize).toBe('23px')
    expect(seen.text).toBe(rgb(dark.foreground))
    // The book colours its headings; a dark theme overrides that.
    expect(seen.heading).toBe(rgb(dark.foreground))
    expect(seen.background).toBe(rgb(dark.background))
    expect(seen.link).toBe(rgb(dark.link))
    expect(seen.lineHeight).toBeCloseTo(23 * 1.5, 0)
    // Text can be selected in a reflowable book (the reader's own page around it cannot).
    expect(seen.selection).toBe('text')
    expect(await page.evaluate(() => getComputedStyle(document.body).userSelect)).toBe('none')
    expect(seen.sizeAdjust).toBe('100%')
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(rgb(dark.background))

    // Applied at once to the section on screen, without reloading it.
    await send(page, { type: 'settings', settings: { ...SETTINGS, fontSizePx: 14, lineHeight: 2 } })
    await expect.poll(async () => (await read()).rootSize).toBe('14px')
    const light = await read()
    expect(light.background).toBe(rgb(SETTINGS.background))
    expect(light.text).toBe(rgb(SETTINGS.foreground))
    expect(light.lineHeight).toBeCloseTo(28, 0)
    // Plain black on white leaves the book's own colours alone.
    expect(light.heading).toBe('rgb(34, 68, 136)')
    expect((await contentFrame(page)).url()).toBe(frame.url())
})

test('the settings keep working in the next chapter', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { fontSizePx: 21, background: '#f4ecd8', foreground: '#3b2f1e' } })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    const frame = await contentFrame(page)
    await expect(frame.locator('h1')).toHaveText('The Morning Boat')
    const seen = await frame.evaluate(() => ({
        size: getComputedStyle(document.documentElement).fontSize,
        background: getComputedStyle(document.body).backgroundColor,
    }))
    expect(seen).toEqual({ size: '21px', background: rgb('#f4ecd8') })
})

test('an image keeps its natural colours and size in a dark theme', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', {
        settings: { background: '#000000', foreground: '#c8c8c8', link: '#7ab7ff', dark: true },
    })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    await settle(page)
    const frame = await contentFrame(page)
    const image = frame.locator('#plate')
    const style = await image.evaluate((el: HTMLImageElement) => {
        const s = getComputedStyle(el)
        return { filter: s.filter, blend: s.mixBlendMode, opacity: s.opacity, natural: el.naturalWidth }
    })
    expect(style).toEqual({ filter: 'none', blend: 'normal', opacity: '1', natural: 160 })
    const box = (await image.boundingBox())!
    expect(Math.round(box.width)).toBe(160)
    expect(Math.round(box.height)).toBe(100)
    expect(await pixelAt(page, box.x + box.width / 2, box.y + box.height / 2)).toEqual([200, 30, 40])
    // The page around it is the theme's.
    expect(await pixelAt(page, 4, 400)).toEqual([0, 0, 0])
})

test('a bundled font is imposed, and its missing file falls back without harm', async ({ page }) => {
    await startReader(page)
    const { requested } = { requested: [] as string[] }
    page.on('request', r => requested.push(new URL(r.url()).pathname))
    await openBook(page, 'basic', { settings: { font: 'serif' } })
    const frame = await contentFrame(page)
    const family = () => frame.evaluate(() => getComputedStyle(document.querySelector('p')!).fontFamily)
    expect(await family()).toMatch(/^"?LirelleSerif"?, serif$/)
    await expect.poll(() => requested).toContain('/fonts/LirelleSerif-Regular.woff2')
    await expect(frame.locator('h1')).toBeVisible()

    await send(page, { type: 'settings', settings: { ...SETTINGS, font: 'dyslexic' } })
    await expect.poll(family).toMatch(/^"?LirelleDyslexic"?, sans-serif$/)
    await send(page, { type: 'settings', settings: { ...SETTINGS, font: 'sans' } })
    await expect.poll(family).toMatch(/^"?LirelleSans"?, sans-serif$/)
    // "Book's layout" imposes nothing.
    await send(page, { type: 'settings', settings: { ...SETTINGS, font: 'book' } })
    await expect.poll(family).not.toContain('Lirelle')
})

test('alignment is imposed only when asked, and never on what the book centres', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const frame = await contentFrame(page)
    const read = () => frame.evaluate(() => {
        const align = (selector: string) => getComputedStyle(document.querySelector(selector)!).textAlign
        const hyphens = getComputedStyle(document.querySelector('#links')!).hyphens
        return { text: align('#links'), centred: align('#centred'), hyphens }
    })
    expect(await read()).toEqual({ text: 'start', centred: 'center', hyphens: 'auto' })
    await send(page, { type: 'settings', settings: { ...SETTINGS, align: 'justify', hyphenate: false } })
    await expect.poll(read).toEqual({ text: 'justify', centred: 'center', hyphens: 'manual' })
    await send(page, { type: 'settings', settings: { ...SETTINGS, align: 'start' } })
    await expect.poll(read).toEqual({ text: 'start', centred: 'center', hyphens: 'auto' })
})

test('wider margins narrow the text', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { marginPercent: 4 } })
    const frame = await contentFrame(page)
    const width = () => frame.evaluate(() => document.querySelector('#links')!.getBoundingClientRect().width)
    const narrow = await width()
    await send(page, { type: 'settings', settings: { ...SETTINGS, marginPercent: 16 } })
    await expect.poll(width).toBeLessThan(narrow - 40)
})

test('two columns show only in a wide landscape window', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await startReader(page)
    await openBook(page, 'basic', { settings: { maxColumns: 2 } })
    const frame = await contentFrame(page)
    const width = () => frame.evaluate(() => document.querySelector('#links')!.getBoundingClientRect().width)
    expect(await width()).toBeLessThan(640)
    const two = await width()
    await send(page, { type: 'settings', settings: { ...SETTINGS, maxColumns: 1 } })
    await settle(page)
    // One column is still capped to a readable measure.
    expect(await width()).toBeLessThanOrEqual(720)
    expect(await width()).toBeGreaterThan(two)
})

test('the book with an obfuscated font renders with that font', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'fonts')
    expect(opened.title).toBe('A Book Set in Pebbles')
    const frame = await contentFrame(page)
    await expect(frame.locator('#heading')).toBeVisible()
    await expect.poll(() => frame.evaluate(async () => {
        await document.fonts.ready
        return Array.from(document.fonts, f => `${f.family.replace(/"/g, '')}: ${f.status}`)
    })).toContain('Lirelle Pebble: loaded')
})
