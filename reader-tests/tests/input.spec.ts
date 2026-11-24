import { expect, test, type Page } from '@playwright/test'
import {
    contentFrame, drag, mark, messages, openBook, relocatedAfter, sendAndRelocate, settle, since, startReader,
    tapElement, waitFor,
} from './harness'

const WIDTH = 390
const HEIGHT = 844

test('a tap in the middle is reported near the middle, and turns nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    const tap = await waitFor(page, 'tap', from)
    expect(tap.x).toBeCloseTo(0.5, 1)
    expect(tap.y).toBeCloseTo(0.5, 1)
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await since(page, 'tap', from)).toHaveLength(1)
})

test('taps are placed on the page, not on the chapter, wherever the chapter is scrolled', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'next' })
    await sendAndRelocate(page, { type: 'next' })
    for (const [x, y] of [[40, 700], [350, 120], [195, 422]]) {
        const from = await mark(page)
        await page.touchscreen.tap(x, y)
        const tap = await waitFor(page, 'tap', from)
        expect(Math.abs(tap.x - x / WIDTH)).toBeLessThan(0.01)
        expect(Math.abs(tap.y - y / HEIGHT)).toBeLessThan(0.01)
    }
})

test('a tap in the margin outside the text is reported too', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, 6)
    const tap = await waitFor(page, 'tap', from)
    expect(tap.y).toBeLessThan(0.02)
})

test('a tap on a link inside the book follows it and is reported as a link, not a tap', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const from = await mark(page)
    await tapElement(page, '#internal')
    const link = await waitFor(page, 'link', from)
    expect(link).toEqual({ type: 'link', href: 'OEBPS/ch3.xhtml#reply', external: false })
    const relocated = await relocatedAfter(page, from)
    expect(relocated.sectionIndex).toBe(2)
    expect(relocated.tocLabel).toBe('The reply')
    expect(relocated.moved).toBe(true)
    expect(await since(page, 'tap', from)).toEqual([])
})

test('a link to the web is reported and not opened', async ({ page, context }) => {
    const { outside } = await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    const from = await mark(page)
    await tapElement(page, '#external')
    const link = await waitFor(page, 'link', from)
    expect(link).toEqual({ type: 'link', href: 'https://example.org/tides', external: true })
    await settle(page)
    expect(await since(page, 'tap', from)).toEqual([])
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(context.pages()).toHaveLength(1)
    expect(page.url()).toBe('https://appassets.androidplatform.net/reader/index.html')
    expect(outside).toEqual([])
    expect(relocated.sectionIndex).toBe(0)
})

test('a note reference leads to its note, outside the reading order', async ({ page }) => {
    await startReader(page)
    // Without the note panel, a reference is a link like any other.
    const { opened } = await openBook(page, 'footnote', { settings: { footnotePanel: false } })
    expect(opened.toc).toHaveLength(1)
    const from = await mark(page)
    await tapElement(page, '#ref1')
    const link = await waitFor(page, 'link', from)
    expect(link).toEqual({ type: 'link', href: 'OEBPS/notes.xhtml#n1', external: false })
    const relocated = await relocatedAfter(page, from)
    expect(relocated.sectionIndex).toBe(1)
    // A note file is not the end of the book.
    expect(relocated.atEnd).toBe(false)
    const frame = await contentFrame(page)
    await expect(frame.locator('#n1')).toBeVisible()
})

test('a swipe turns the page, forward and back', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    let from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    const second = await relocatedAfter(page, from)
    expect(second.page).toBe(2)
    expect(second.moved).toBe(true)
    expect(await since(page, 'tap', from)).toEqual([])
    from = await mark(page)
    await drag(page, [60, 500], [320, 490])
    const first = await relocatedAfter(page, from)
    expect(first.cfi).toBe(relocated.cfi)
})

/** Records, from now on, when the page posts a turn and when the engine reports where it went. */
const stampTurns = (page: Page) => page.evaluate(() => {
    const w = window as any
    const post = w.lirelleHost.postMessage
    w.lirelleTurns = []
    w.lirelleHost.postMessage = (json: string) => {
        if (JSON.parse(json).type === 'turning') w.lirelleTurns.push('turning')
        post(json)
    }
    const view = document.getElementById('view') as any
    view.renderer.addEventListener('relocate', (e: any) => w.lirelleTurns.push(e.detail.reason))
})

test('a swipe that turns the page says so before the page moves on, then where it went', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    await stampTurns(page)
    for (const [from, to, expected] of [[[320, 500], [60, 510], 2], [[60, 500], [320, 490], 1]] as const) {
        const at = await mark(page)
        await drag(page, [...from], [...to])
        const relocated = await relocatedAfter(page, at)
        expect(relocated.page).toBe(expected)
        expect((await messages(page)).slice(at)).toEqual([{ type: 'turning' }, relocated])
    }
    // Before the engine moved the page on, each time.
    const seen: string[] = await page.evaluate(() => (window as any).lirelleTurns)
    expect(seen[0]).toBe('turning')
    expect(seen.filter(t => t === 'turning')).toHaveLength(2)
    expect(seen.indexOf('turning', 1)).toBeGreaterThan(seen.indexOf('snap'))
})

test('a drag too short to turn the page says nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    const from = await mark(page)
    await drag(page, [250, 500], [215, 505], 20)
    await settle(page)
    expect(await since(page, 'turning', from)).toEqual([])
    expect(await since(page, 'relocated', from)).toEqual([])
})

test('a short quick flick turns the page as the engine does, and says so', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    const from = await mark(page)
    await drag(page, [300, 500], [210, 500], 3)
    const relocated = await relocatedAfter(page, from)
    expect(relocated.page).toBe(2)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
})

test('a swipe back on the first page turns nothing and says nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    const from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    await settle(page)
    expect((await messages(page)).slice(from)).toEqual([])
})

test('in a right-to-left book a swipe to the right turns the page forward, and says so first', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'rtl')
    await settle(page)
    let from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    let relocated = await relocatedAfter(page, from)
    expect(relocated.page).toBe(2)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
    from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    relocated = await relocatedAfter(page, from)
    expect(relocated.page).toBe(1)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
})

test('a swipe turns the page of a fixed-layout book', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    let from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    expect((await relocatedAfter(page, from)).sectionIndex).toBe(1)
    from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    expect((await relocatedAfter(page, from)).sectionIndex).toBe(0)
})

test('a vertical drag on the left edge changes the brightness and does not turn the page', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    const from = await mark(page)
    await drag(page, [24, 640], [30, 300], 17)
    await settle(page)
    const brightness = await since(page, 'brightness', from)
    expect(brightness[0]).toEqual({ type: 'brightness', phase: 'start', delta: 0 })
    expect(brightness[brightness.length - 1]).toEqual({ type: 'brightness', phase: 'end', delta: 0 })
    const moves = brightness.slice(1, -1)
    expect(moves.length).toBeGreaterThan(5)
    expect(moves.every(m => m.phase === 'move' && m.delta > 0)).toBe(true)
    // Up is positive, as a fraction of the screen height.
    expect(moves.reduce((sum, m) => sum + m.delta, 0)).toBeCloseTo(340 / HEIGHT, 2)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await since(page, 'tap', from)).toEqual([])

    // Downwards is negative.
    const again = await mark(page)
    await drag(page, [24, 300], [24, 500])
    await settle(page, 200)
    const down = (await since(page, 'brightness', again)).filter(m => m.phase === 'move')
    expect(down.reduce((sum, m) => sum + m.delta, 0)).toBeCloseTo(-200 / HEIGHT, 2)
})

test('the brightness edge can be the right one, or none', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { brightnessEdge: 'right' } })
    let from = await mark(page)
    await drag(page, [24, 640], [24, 300])
    await settle(page)
    expect(await since(page, 'brightness', from)).toEqual([])
    await drag(page, [370, 640], [370, 300])
    await settle(page, 200)
    expect((await since(page, 'brightness', from)).map(m => m.phase)).toContain('end')

    await openBook(page, 'basic', { settings: { brightnessEdge: 'off' } })
    from = await mark(page)
    await drag(page, [24, 640], [24, 300])
    await drag(page, [370, 640], [370, 300])
    await settle(page)
    expect(await since(page, 'brightness', from)).toEqual([])
})

test('a vertical drag outside the strip sends no brightness', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const from = await mark(page)
    await drag(page, [200, 640], [200, 300])
    await settle(page)
    expect(await since(page, 'brightness', from)).toEqual([])
})

test('a sideways swipe starting in the strip still turns the page', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { brightnessEdge: 'right' } })
    const from = await mark(page)
    await drag(page, [375, 500], [80, 505])
    const relocated = await relocatedAfter(page, from)
    expect(relocated.page).toBe(2)
    expect(await since(page, 'brightness', from)).toEqual([])
})

test('in scrolled layout the strip changes the brightness and does not scroll the text', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    await settle(page)
    let from = await mark(page)
    await drag(page, [24, 640], [24, 300])
    await settle(page)
    expect((await since(page, 'brightness', from)).map(m => m.phase)).toContain('move')
    expect(await since(page, 'relocated', from)).toEqual([])
    // The same drag in the text scrolls it.
    from = await mark(page)
    await drag(page, [200, 640], [200, 300])
    const relocated = await relocatedAfter(page, from)
    expect(relocated.moved).toBe(true)
    expect(relocated.fraction).toBeGreaterThan(0)
    expect(await since(page, 'brightness', from)).toEqual([])
    // And so does the next one.
    from = await mark(page)
    await drag(page, [200, 640], [200, 300])
    expect((await relocatedAfter(page, from)).fraction).toBeGreaterThan(relocated.fraction)
})

test('in scrolled layout, pulling past the end of a chapter opens the next one, and back', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    // Pulling down at the top of a chapter shows the end of the one before.
    let from = await mark(page)
    await drag(page, [200, 300], [200, 640])
    const before = await relocatedAfter(page, from)
    expect(before.sectionIndex).toBe(0)
    expect(before.fraction).toBeGreaterThan(0.2)
    // Pulling up at the bottom goes on to the next.
    from = await mark(page)
    await drag(page, [200, 640], [200, 300])
    const after = await relocatedAfter(page, from)
    expect(after.sectionIndex).toBe(1)
    expect(after.cfi).toContain('[top]')
})

test('a swipe forward on the last page says the reader went past the end; a swipe back does not', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    // Not at the end: a swipe forward is only a page turned.
    let from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    await relocatedAfter(page, from)
    expect(await since(page, 'pastEnd', 0)).toEqual([])
    await sendAndRelocate(page, { type: 'goTo', fraction: 1 })
    await settle(page)
    from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    await settle(page)
    // The engine has no page to turn to: no turn is announced.
    expect((await messages(page)).slice(from)).toEqual([{ type: 'pastEnd' }])
    from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    await relocatedAfter(page, from)
    expect(await since(page, 'pastEnd', from)).toEqual([])
})

test('in a right-to-left book, forward is the other way', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'rtl')
    await sendAndRelocate(page, { type: 'goTo', fraction: 1 })
    await settle(page)
    const from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    await waitFor(page, 'pastEnd', from)
    await settle(page)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'pastEnd' }])
})

test('a swipe forward on the last plate of a fixed-layout book says the reader went past the end', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    const end = await sendAndRelocate(page, { type: 'next' })
    await settle(page)
    const from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    await settle(page)
    // The place on screen said again is nobody's move.
    expect((await messages(page)).slice(from))
        .toEqual([{ type: 'turning' }, { type: 'pastEnd' }, { ...end, userMove: false }])
})

test('in scrolled layout, pulling past the bottom of the last chapter says the reader went past the end', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    const end = await sendAndRelocate(page, { type: 'goTo', fraction: 1 })
    expect(end.atEnd).toBe(true)
    await settle(page)
    const from = await mark(page)
    await drag(page, [200, 640], [200, 300])
    await waitFor(page, 'pastEnd', from)
    await settle(page)
    expect(await since(page, 'pastEnd', from)).toHaveLength(1)
    // Pulling the other way scrolls back up and says nothing of the end.
    const back = await mark(page)
    await drag(page, [200, 300], [200, 640])
    await relocatedAfter(page, back)
    expect(await since(page, 'pastEnd', back)).toEqual([])
})

test('a long press is not a tap', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const from = await mark(page)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 600 }] })
    await page.waitForTimeout(700)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await settle(page, 300)
    expect(await since(page, 'tap', from)).toEqual([])
    const selected = await (await contentFrame(page)).evaluate(() => String(getSelection()))
    expect(selected).toBe('')
})
