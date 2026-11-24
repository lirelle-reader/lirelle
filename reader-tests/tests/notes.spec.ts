import { expect, test, type Frame, type Page } from '@playwright/test'
import {
    drag, mainFrame, mark, messages, noteFrame, noteFrameCount, openBook, pixelAt, relocatedAfter, send, SETTINGS,
    settle, since, startReader, waitFor,
} from './harness'

const WIDTH = 390
const HEIGHT = 844
// The panel is never taller than this share of the screen.
const MAX_SHARE = 0.45

// ---------------------------------------------------------------- helpers

/** Taps the middle of the first line of an element: a link that wraps has no text in the middle of its box. */
const tapIn = async (page: Page, frame: Frame, selector: string) => {
    const rect = await frame.locator(selector).first().evaluate(el => {
        const r = [...el.getClientRects()].find(r => r.width > 0 && r.height > 0)
        return r ? { x: r.left + r.width / 2, y: r.top + r.height / 2 } : null
    })
    expect(rect, `${selector} is laid out`).not.toBeNull()
    const box = (await (await frame.frameElement()).boundingBox())!
    await page.touchscreen.tap(box.x + rect!.x, box.y + rect!.y)
}

/** Where the main view stands, as the engine itself knows it. */
const position = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    return { cfi: view.lastLocation?.cfi ?? null, start: view.renderer.start as number }
})

/** What the panel looks like from outside. */
const panel = (page: Page) => page.evaluate(() => {
    const layer = document.getElementById('note-layer')!
    const box = document.getElementById('note-panel')!.getBoundingClientRect()
    return {
        open: !layer.hidden && layer.dataset.state === 'open',
        top: box.top, bottom: box.bottom, height: box.height,
        title: document.getElementById('note-title')!.textContent,
        goTo: document.getElementById('note-go')!.textContent,
        mainHidden: document.getElementById('view')!.getAttribute('aria-hidden'),
    }
})

const isClosed = (page: Page) => page.evaluate(() => document.getElementById('note-layer')!.hidden)

/** The note view's own engine: how tall its content is, how much is shown, how far it is scrolled. */
const noteScroll = (page: Page) => page.evaluate(() => {
    const view = document.querySelector('#note-panel foliate-view') as any
    const { renderer } = view
    return { viewSize: renderer.viewSize as number, size: renderer.size as number, start: renderer.start as number }
})

/** Opens the note of a reference of the first page of `footnote` and waits until it is shown. */
const openNote = async (page: Page, ref: string) => {
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, ref)
    const opened = await waitFor(page, 'noteOpened', from)
    await expect.poll(async () => (await panel(page)).open).toBe(true)
    return { from, opened, note: await noteFrame(page) }
}

const openFootnoteBook = async (page: Page, settings = {}, delays: Record<string, number> = {}) => {
    await startReader(page, { delays })
    await openBook(page, 'footnote', { settings })
    await settle(page)
}

/** How many views hold a section of the open book loaded, by the page's own count. */
const sectionUsers = (page: Page, index: number) => page.evaluate(i =>
    (document.getElementById('view') as any).book.sections[i].lirelleUsers as number, index)

/** Uncaught errors and rejections of the page. */
const pageErrors = (page: Page) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(String(e.stack ?? e)))
    return errors
}

// The notes file of `footnote` arrives this late, so that a note can be caught while it loads.
const SLOW_NOTES = { 'footnote/OEBPS/notes.xhtml': 1500 }

// ---------------------------------------------------------------- opening

test('a marked reference opens its note in a panel, and the page does not move', async ({ page }) => {
    await openFootnoteBook(page)
    const before = await position(page)
    const { from, opened, note } = await openNote(page, '#ref1')
    expect(opened).toEqual({ type: 'noteOpened', href: 'OEBPS/notes.xhtml#n1', label: '1' })
    await expect(note.locator('body')).toContainText('It had been dropped twice, once into the harbour.')
    // Only the note: the rest of its file is not in the panel.
    await expect(note.locator('h1')).toHaveCount(0)
    await settle(page)
    const posted = (await messages(page)).slice(from).map(m => m.type)
    expect(posted).toEqual(['noteOpened'])
    expect(await position(page)).toEqual(before)
})

test('the panel is a dialog that takes the focus and hides the page from accessibility', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const seen = await page.evaluate(() => {
        const dialog = document.getElementById('note-panel')!
        const label = document.getElementById(dialog.getAttribute('aria-labelledby')!)
        return {
            role: dialog.getAttribute('role'),
            modal: dialog.getAttribute('aria-modal'),
            label: label?.textContent ?? null,
            focus: dialog.contains(document.activeElement),
            hidden: document.getElementById('view')!.getAttribute('aria-hidden'),
        }
    })
    expect(seen).toEqual({ role: 'dialog', modal: 'true', label: 'Note 1', focus: true, hidden: 'true' })
    expect((await panel(page)).goTo).toBe('Go to the note')
})

test('the note is shown in the reader\'s font, size and colours', async ({ page }) => {
    await openFootnoteBook(page, {
        background: '#202020', foreground: '#e0e0e0', link: '#88aaff', dark: true, font: 'serif', fontSizePx: 22,
    })
    const { note } = await openNote(page, '#ref1')
    const seen = await note.evaluate(() => {
        const style = getComputedStyle(document.querySelector('p')!)
        return { color: style.color, size: style.fontSize, family: style.fontFamily, select: style.userSelect }
    })
    expect(seen.color).toBe('rgb(224, 224, 224)')
    expect(seen.size).toBe('22px')
    expect(seen.family).toContain('LirelleSerif')
    // Nothing to select in the panel.
    expect(seen.select).toBe('none')
    // The panel's surface: the background a tenth of the way to the text on a dark theme.
    const background = await page.evaluate(() => getComputedStyle(document.getElementById('note-panel')!).backgroundColor)
    expect(background).toBe('rgb(51, 51, 51)')
})

test('the panel is as tall as a short note, at the bottom of the screen', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const seen = await panel(page)
    expect(seen.bottom).toBeCloseTo(HEIGHT, 0)
    expect(seen.height).toBeLessThan(HEIGHT * 0.25)
    const scroll = await noteScroll(page)
    // All of the note is shown, nothing to scroll.
    expect(scroll.size).toBeGreaterThanOrEqual(scroll.viewSize - 1)
})

const panelBox = (page: Page) => page.evaluate(() => {
    const b = document.getElementById('note-panel')!.getBoundingClientRect()
    return { left: b.left, right: b.right, width: b.width, window: window.innerWidth }
})

test('on a wide screen the panel is no wider than a column, and centred', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const box = await panelBox(page)
    expect(box.width).toBeLessThanOrEqual(720)
    expect(box.left).toBeCloseTo(box.window - box.right, 0)
    expect(box.left).toBeGreaterThan(100)
})

test('on a phone the panel takes the full width', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const box = await panelBox(page)
    expect(box.left).toBeCloseTo(0, 0)
    expect(box.width).toBeCloseTo(box.window, 0)
})

const channels = (css: string) => css.match(/[\d.]+/g)!.map(Number)

for (const [theme, settings, lighter] of [
    ['black', { background: '#000000', foreground: '#d0d0d0', link: '#88aaff', dark: true }, true],
    ['dark grey', { background: '#202020', foreground: '#e0e0e0', link: '#88aaff', dark: true }, true],
    ['white', { background: '#ffffff', foreground: '#111111', dark: false }, false],
    ['sepia', { background: '#f4ecd8', foreground: '#5b4636', dark: false }, false],
] as const) {
    test(`the panel is a surface of its own over a clear scrim (${theme} theme)`, async ({ page }) => {
        await openFootnoteBook(page, settings)
        const { note } = await openNote(page, '#ref1')
        const seen = await page.evaluate(() => {
            const style = (id: string) => getComputedStyle(document.getElementById(id)!)
            return {
                panel: style('note-panel').backgroundColor,
                topLeft: parseFloat(style('note-panel').borderTopLeftRadius),
                topRight: parseFloat(style('note-panel').borderTopRightRadius),
                bottomLeft: parseFloat(style('note-panel').borderBottomLeftRadius),
                scrim: style('note-scrim').backgroundColor,
                page: getComputedStyle(document.body).backgroundColor,
            }
        })
        const [r, g, b] = channels(seen.panel)
        const paper = channels(seen.page)
        // A shade off the page: lighter on a dark theme, darker on a light one, never far from it.
        for (const [i, c] of [r, g, b].entries()) {
            const shift = lighter ? c - paper[i] : paper[i] - c
            expect(shift).toBeGreaterThanOrEqual(10)
            expect(shift).toBeLessThanOrEqual(40)
        }
        // The note itself sits on the same surface.
        expect(await note.evaluate(() => getComputedStyle(document.documentElement).backgroundColor)).toBe(seen.panel)
        expect(seen.topLeft).toBeGreaterThanOrEqual(12)
        expect(seen.topRight).toBeGreaterThanOrEqual(12)
        expect(seen.bottomLeft).toBe(0)
        // The page behind is dimmed clearly.
        const scrim = channels(seen.scrim)
        expect(scrim.slice(0, 3)).toEqual([0, 0, 0])
        expect(scrim[3]).toBeGreaterThanOrEqual(0.45)
    })
}

test('the note has room around it and under the header, and the panel takes a fifth of the screen at least',
    async ({ page }) => {
        // No margin of the reader's own: the room is the panel's.
        await openFootnoteBook(page, { marginPercent: 0 })
        const { note } = await openNote(page, '#ref1')
        const frame = (await (await note.frameElement()).boundingBox())!
        const text = await note.evaluate(() => {
            const range = document.createRange()
            range.selectNodeContents(document.body)
            const rects = [...range.getClientRects()].filter(r => r.width > 0 && r.height > 0)
            return {
                left: Math.min(...rects.map(r => r.left)), right: Math.max(...rects.map(r => r.right)),
                top: Math.min(...rects.map(r => r.top)), bottom: Math.max(...rects.map(r => r.bottom)),
            }
        })
        const seen = await page.evaluate(() => {
            const box = (id: string) => document.getElementById(id)!.getBoundingClientRect().toJSON()
            return { panel: box('note-panel'), head: box('note-head'), title: box('note-title') }
        })
        expect(seen.panel.height).toBeGreaterThanOrEqual(HEIGHT * 0.2 - 0.5)
        expect(seen.panel.height).toBeLessThan(HEIGHT * 0.25)
        expect(frame.x + text.left - seen.panel.left).toBeGreaterThanOrEqual(16)
        expect(seen.panel.right - (frame.x + text.right)).toBeGreaterThanOrEqual(16)
        expect(frame.y + text.top - seen.head.bottom).toBeGreaterThanOrEqual(16)
        expect(seen.panel.bottom - (frame.y + text.bottom)).toBeGreaterThanOrEqual(16)
        // The note lines up with the title.
        expect(Math.abs(frame.x + text.left - seen.title.left)).toBeLessThanOrEqual(2)
    })

test('a long note is cut at 45 % of the screen and scrolls inside the panel', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref9')
    const seen = await panel(page)
    expect(seen.height).toBeLessThanOrEqual(HEIGHT * MAX_SHARE + 0.5)
    expect(seen.height).toBeGreaterThan(HEIGHT * MAX_SHARE - 2)
    expect(seen.bottom).toBeCloseTo(HEIGHT, 0)
    const scroll = await noteScroll(page)
    expect(scroll.viewSize).toBeGreaterThan(scroll.size + 50)
    expect(scroll.start).toBe(0)
    await page.mouse.move(WIDTH / 2, HEIGHT - 80)
    await page.mouse.wheel(0, 200)
    await expect.poll(async () => (await noteScroll(page)).start).toBeGreaterThan(0)
    expect((await panel(page)).open).toBe(true)
})

test('a reference only set in superscript opens its note too', async ({ page }) => {
    await openFootnoteBook(page)
    const { opened, note } = await openNote(page, '#ref2')
    expect(opened).toEqual({ type: 'noteOpened', href: 'OEBPS/notes.xhtml#n2', label: '2' })
    await expect(note.locator('body')).toContainText('The ledger was bound in sailcloth and smelled of tar.')
    await expect(note.locator('aside')).toHaveCount(0)
})

test('a note in the same file as its reference opens, and the page\'s own document is left alone', async ({ page }) => {
    await openFootnoteBook(page)
    const main = await mainFrame(page)
    const snapshot = () => main.evaluate(() => ({
        elements: document.body.getElementsByTagName('*').length,
        html: document.body.innerHTML,
    }))
    const before = await snapshot()
    const { opened, note } = await openNote(page, '#ref3')
    expect(opened).toEqual({ type: 'noteOpened', href: 'OEBPS/ch1.xhtml#n3', label: '3' })
    await expect(note.locator('body')).toContainText('The lamp was trimmed at dusk by whoever was nearest.')
    await expect(note.locator('#claim')).toHaveCount(0)
    expect(await snapshot()).toEqual(before)
    await send(page, { type: 'closeNote' })
    await expect.poll(() => isClosed(page)).toBe(true)
    expect(await snapshot()).toEqual(before)
})

test('the picture in a note shows', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref4')
    const chart = note.locator('#chart')
    await expect(chart).toBeVisible()
    await expect.poll(() => chart.evaluate(img => (img as HTMLImageElement).naturalWidth)).toBe(120)
    const box = (await chart.boundingBox())!
    expect(box.y + box.height).toBeLessThanOrEqual(HEIGHT)
    expect(await pixelAt(page, box.x + box.width / 2, box.y + box.height / 2)).toEqual([20, 160, 60])
})

// ---------------------------------------------------------------- links back to the reference

for (const [ref, back, kind] of [
    ['#ref14', '#back14', 'marked by its type'],
    ['#ref15', '#back15', 'only a number pointing back'],
    ['#ref16', '#back16', 'marked by its role'],
    ['#ref20', '#back20', 'a number pointing to a wrapper of the reference alone'],
] as const) {
    test(`a link back to the reference is hidden in the panel (${kind}), and only there`, async ({ page }) => {
        await openFootnoteBook(page)
        const { note } = await openNote(page, ref)
        await expect(note.locator('body')).not.toBeEmpty()
        await expect(note.locator(back)).toBeHidden()
        expect(await note.locator(back).evaluate(el => getComputedStyle(el).display)).toBe('none')
        // The rest of the note is shown.
        await expect(note.locator('p').first()).toBeVisible()
        // The page's own copy of the notes keeps it.
        await send(page, { type: 'closeNote' })
        await expect.poll(() => isClosed(page)).toBe(true)
        const from = await mark(page)
        await send(page, { type: 'goTo', href: `OEBPS/notes.xhtml${back.replace('back', '#n').slice(1)}` })
        await relocatedAfter(page, from)
        const main = await mainFrame(page)
        expect(await main.locator(back).evaluate(el => getComputedStyle(el).display)).not.toBe('none')
    })
}

for (const [ref, back, kind] of [
    ['#ref18', '#back18', 'marked, around the whole note'],
    ['#ref19', '#back19', 'to a wrapper that holds more than the reference'],
] as const) {
    test(`a link back that is more than a mark stays in the panel (${kind})`, async ({ page }) => {
        await openFootnoteBook(page)
        const { note } = await openNote(page, ref)
        await expect(note.locator(back)).toBeVisible()
    })
}

test('the other links of a note stay in the panel', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref5')
    await expect(note.locator('#inner')).toBeVisible()
    await expect(note.locator('#web')).toBeVisible()
})

// ---------------------------------------------------------------- links that are not notes after all

for (const [ref, href, path] of [
    ['#ref6', 'OEBPS/notes.xhtml#gone', 'marked'],
    ['#ref7', 'OEBPS/notes.xhtml#nowhere', 'heuristic'],
    ['#ref8', 'OEBPS/notes.xhtml#s8', 'heuristic, a whole chapter'],
    ['#ref12', 'OEBPS/notes.xhtml#story', 'marked, a whole chapter'],
] as const) {
    test(`a reference that leads to no note is followed as a link (${path})`, async ({ page }) => {
        await openFootnoteBook(page)
        const main = await mainFrame(page)
        const from = await mark(page)
        await tapIn(page, main, ref)
        const link = await waitFor(page, 'link', from)
        expect(link).toEqual({ type: 'link', href, external: false })
        const relocated = await relocatedAfter(page, from)
        expect(relocated.sectionIndex).toBe(1)
        expect(relocated.moved).toBe(true)
        expect(await since(page, 'link', from)).toHaveLength(1)
        expect(await since(page, 'noteOpened', from)).toEqual([])
        expect(await since(page, 'noteClosed', from)).toEqual([])
        expect(await since(page, 'tap', from)).toEqual([])
        expect(await isClosed(page)).toBe(true)
        await expect.poll(() => noteFrameCount(page)).toBe(0)
        expect(await page.evaluate(() => document.getElementById('view')!.getAttribute('aria-hidden'))).toBeNull()
    })
}

// ---------------------------------------------------------------- leaving the panel

const closedThenLink = async (page: Page, from: number, href: string) => {
    const link = await waitFor(page, 'link', from)
    expect(link).toEqual({ type: 'link', href, external: false })
    const relocated = await relocatedAfter(page, from)
    expect(relocated.sectionIndex).toBe(1)
    const posted = (await messages(page)).slice(from).map(m => m.type).filter(t => t !== 'relocated')
    expect(posted).toEqual(['noteClosed', 'link'])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(await page.evaluate(() => document.getElementById('view')!.getAttribute('aria-hidden'))).toBeNull()
}

test('a link inside the note closes the panel and is followed in the book', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref5')
    const from = await mark(page)
    await tapIn(page, note, '#inner')
    await closedThenLink(page, from, 'OEBPS/notes.xhtml#n2')
    await expect((await mainFrame(page)).locator('#n2')).toBeVisible()
})

test('"Go to the note" closes the panel and follows the reference', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    await page.locator('#note-go').tap()
    await closedThenLink(page, from, 'OEBPS/notes.xhtml#n1')
    await expect((await mainFrame(page)).locator('#n1')).toBeVisible()
})

test('a tap outside the panel closes it, is no tap, and gives the focus back to the reference', async ({ page }) => {
    await openFootnoteBook(page)
    const before = await position(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, 120)
    await waitFor(page, 'noteClosed', from)
    await settle(page)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['noteClosed'])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(await position(page)).toEqual(before)
    const main = await mainFrame(page)
    expect(await main.evaluate(() => document.activeElement?.id ?? null)).toBe('ref1')
    // The page answers taps again.
    const next = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    await waitFor(page, 'tap', next)
})

test('a swipe down on the top of the panel closes it', async ({ page }) => {
    await openFootnoteBook(page)
    const before = await position(page)
    await openNote(page, '#ref1')
    const { top } = await panel(page)
    const from = await mark(page)
    await drag(page, [WIDTH / 2, top + 12], [WIDTH / 2, top + 212])
    await waitFor(page, 'noteClosed', from)
    await settle(page)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['noteClosed'])
    expect(await position(page)).toEqual(before)
})

test('a swipe down on the note itself closes the panel when the note is at its top', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref9')
    const { top } = await panel(page)
    const from = await mark(page)
    await drag(page, [WIDTH / 2, top + 120], [WIDTH / 2, top + 320])
    await waitFor(page, 'noteClosed', from)
    await settle(page)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['noteClosed'])
})

test('a short drag or a tap on the panel leaves it open', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref1')
    const { top } = await panel(page)
    const from = await mark(page)
    await drag(page, [WIDTH / 2, top + 12], [WIDTH / 2, top + 30])
    await tapIn(page, note, 'p')
    await page.touchscreen.tap(30, top + 20)
    await settle(page)
    expect((await messages(page)).slice(from)).toEqual([])
    expect((await panel(page)).open).toBe(true)
})

test('closeNote closes the panel', async ({ page }) => {
    await openFootnoteBook(page)
    const before = await position(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    await send(page, { type: 'closeNote' })
    await waitFor(page, 'noteClosed', from)
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect((await panel(page)).mainHidden).toBeNull()
    await settle(page)
    expect(await position(page)).toEqual(before)
    // Nothing to close any more: nothing is said.
    const again = await mark(page)
    await send(page, { type: 'closeNote' })
    await settle(page, 300)
    expect((await messages(page)).slice(again)).toEqual([])
})

test('opening a book closes the panel', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    await openBook(page, 'basic')
    expect(await since(page, 'noteClosed', from)).toHaveLength(1)
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
})

// ---------------------------------------------------------------- where a note leads

/** The drawing that marks where a note led, in the overlayer of the main view's section. */
const noteTarget = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const out: { index: number, rects: number, box: any, rises: number }[] = []
    for (const content of view.renderer.getContents()) {
        const svg = content.overlayer?.element as SVGElement | undefined
        for (const g of svg ? svg.querySelectorAll('[data-lirelle="note-target"]') : []) {
            const rects = [...g.querySelectorAll('rect')].map(r => r.getBBox())
            const frames = (g as SVGElement).getAnimations()[0]?.effect?.getKeyframes() ?? []
            const values = frames.map((k: any) => Number(k.opacity))
            // How many times the tint grows again after the first frame: the pulses.
            const rises = values.filter((v: number, i: number) => i > 0 && v > values[i - 1]).length
            out.push({
                index: content.index, rects: rects.length, rises,
                box: rects.length ? {
                    left: Math.min(...rects.map(r => r.x)), top: Math.min(...rects.map(r => r.y)),
                    right: Math.max(...rects.map(r => r.x + r.width)), bottom: Math.max(...rects.map(r => r.y + r.height)),
                } : null,
            })
        }
    }
    return out
})

/** The box of an element of the main view's document, in that document's coordinates. */
const boxIn = (frame: Frame, selector: string) => frame.locator(selector).evaluate(el => {
    const r = el.getBoundingClientRect()
    return { left: r.left, top: r.top, right: r.right, bottom: r.bottom }
})

const bodyOf = (frame: Frame) => frame.evaluate(() => ({
    elements: document.body.getElementsByTagName('*').length,
    html: document.body.innerHTML,
    ours: document.querySelectorAll('[data-lirelle]').length,
}))

/**
 * Checks that the note's target flashes around it once the page is there, then goes, the book's document untouched.
 * [started] is when the action that leads there was made: the drawing, made after it, lasts 1.5 s from then at least,
 * however late the test sees it.
 */
const expectFlash = async (page: Page, selector: string, pulses: number, started: number) => {
    await expect.poll(async () => (await noteTarget(page)).length, { timeout: 1000 }).toBe(1)
    const appeared = Date.now()
    const [drawn] = await noteTarget(page)
    expect(drawn.index).toBe(1)
    expect(drawn.rises).toBe(pulses)
    const main = await mainFrame(page)
    const target = await boxIn(main, selector)
    expect(drawn.box.left).toBeLessThanOrEqual(target.left)
    expect(drawn.box.top).toBeLessThanOrEqual(target.top)
    expect(drawn.box.right).toBeGreaterThanOrEqual(target.right)
    expect(drawn.box.bottom).toBeGreaterThanOrEqual(target.bottom)
    const during = await bodyOf(main)
    expect(during.ours).toBe(0)
    await expect.poll(async () => (await noteTarget(page)).length, { timeout: 3000 }).toBe(0)
    expect(Date.now() - started).toBeGreaterThan(1400)
    expect(Date.now() - appeared).toBeLessThan(2500)
    expect(await bodyOf(main)).toEqual(during)
}

test('"Go to the note" makes the note pulse where it lands, then the drawing goes', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    const started = Date.now()
    await page.locator('#note-go').tap()
    await relocatedAfter(page, from)
    await expectFlash(page, '#n1', 2, started)
})

test('with the panel off, the note a reference leads to pulses too', async ({ page }) => {
    await openFootnoteBook(page, { footnotePanel: false })
    const from = await mark(page)
    const started = Date.now()
    await tapIn(page, await mainFrame(page), '#ref2')
    expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'OEBPS/notes.xhtml#n2', external: false })
    await expectFlash(page, '#n2', 2, started)
})

test('a note whose target is an empty anchor pulses its paragraph', async ({ page }) => {
    await openFootnoteBook(page, { footnotePanel: false })
    const from = await mark(page)
    const started = Date.now()
    await tapIn(page, await mainFrame(page), '#ref17')
    expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'OEBPS/notes.xhtml#a17', external: false })
    await expectFlash(page, '#p17', 2, started)
})

test('a marked reference to an empty anchor is no note: its link is followed, and the paragraph pulses',
    async ({ page }) => {
        await openFootnoteBook(page)
        const from = await mark(page)
        const started = Date.now()
        await tapIn(page, await mainFrame(page), '#ref21')
        expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'OEBPS/notes.xhtml#a21', external: false })
        await expectFlash(page, '#p21', 2, started)
        expect((await relocatedAfter(page, from)).sectionIndex).toBe(1)
        expect(await since(page, 'link', from)).toHaveLength(1)
        expect(await since(page, 'noteOpened', from)).toEqual([])
        expect(await since(page, 'noteClosed', from)).toEqual([])
        expect(await isClosed(page)).toBe(true)
        await expect.poll(() => noteFrameCount(page)).toBe(0)
    })

test('with reduced motion the note is tinted steadily, then fades', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const started = Date.now()
    await page.locator('#note-go').tap()
    await expectFlash(page, '#n1', 0, started)
})

test('an ordinary link does not flash, nor a move of the host', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref5')
    const from = await mark(page)
    await tapIn(page, note, '#inner')
    await relocatedAfter(page, from)
    await send(page, { type: 'goTo', href: 'OEBPS/notes.xhtml#n4' })
    await settle(page, 400)
    expect(await noteTarget(page)).toEqual([])
})

test('the pulse leaves highlights to be tapped and search outlines in place', async ({ page }) => {
    await openFootnoteBook(page)
    const search = await mark(page)
    await send(page, { type: 'search', searchId: 1, query: 'harbour' })
    await waitFor(page, 'searchDone', search)
    await openNote(page, '#ref1')
    let from = await mark(page)
    await page.locator('#note-go').tap()
    await relocatedAfter(page, from)
    const outlines = () => page.evaluate(() => {
        const view = document.getElementById('view') as any
        const svg = view.renderer.getContents()[0].overlayer.element as SVGElement
        return svg.querySelectorAll(':scope > [data-lirelle="search"]').length
    })
    await expect.poll(outlines).toBeGreaterThan(0)
    const before = await outlines()
    // A highlight over the note's first words, drawn while the note pulses.
    const cfi = await page.evaluate(() => {
        const view = document.getElementById('view') as any
        const { doc, index } = view.renderer.getContents()[0]
        const text = doc.querySelector('#n1 p').firstChild
        const range = doc.createRange()
        range.setStart(text, 3)
        range.setEnd(text, 20)
        return view.getCFI(index, range) as string
    })
    await send(page, { type: 'highlights', items: [{ id: 'h1', cfi, color: '#ffd400', style: 'highlight', note: false }] })
    expect((await noteTarget(page)).length).toBe(1)
    const main = await mainFrame(page)
    const word = await main.evaluate(() => {
        const text = document.querySelector('#n1 p')!.firstChild!
        const range = document.createRange()
        range.setStart(text, 6)
        range.setEnd(text, 10)
        const r = range.getBoundingClientRect()
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
    })
    const frame = (await (await main.frameElement()).boundingBox())!
    from = await mark(page)
    await page.touchscreen.tap(frame.x + word.x, frame.y + word.y)
    expect((await waitFor(page, 'highlightTap', from)).id).toBe('h1')
    await expect.poll(async () => (await noteTarget(page)).length, { timeout: 3000 }).toBe(0)
    expect(await outlines()).toBe(before)
})

// ---------------------------------------------------------------- settings

test('with the panel off, every reference is followed as a link', async ({ page }) => {
    await openFootnoteBook(page, { footnotePanel: false })
    for (const [ref, href] of [['#ref1', 'OEBPS/notes.xhtml#n1'], ['#ref2', 'OEBPS/notes.xhtml#n2']]) {
        if (ref === '#ref2') {
            await send(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
            await settle(page)
        }
        const main = await mainFrame(page)
        const from = await mark(page)
        await tapIn(page, main, ref)
        expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href, external: false })
        expect((await relocatedAfter(page, from)).sectionIndex).toBe(1)
        expect(await since(page, 'noteOpened', from)).toEqual([])
        await expect.poll(() => noteFrameCount(page)).toBe(0)
    }
})

test('the panel\'s texts come from the settings, and odd ones are replaced', async ({ page }) => {
    await openFootnoteBook(page, { noteLabels: { goTo: 'Aller à la note', title: 'Remarque' } })
    await openNote(page, '#ref4')
    let seen = await panel(page)
    expect(seen.title).toBe('Remarque 4')
    expect(seen.goTo).toBe('Aller à la note')
    await send(page, { type: 'closeNote' })
    await expect.poll(() => isClosed(page)).toBe(true)
    await send(page, { type: 'settings', settings: { ...SETTINGS, noteLabels: { goTo: 42, title: '' } } })
    await openNote(page, '#ref4')
    seen = await panel(page)
    expect(seen.title).toBe('Note 4')
    expect(seen.goTo).toBe('Go to the note')
})

test('the panel follows a change of theme while it is open', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref1')
    await send(page, {
        type: 'settings',
        settings: { ...SETTINGS, background: '#101010', foreground: '#f0f0f0', dark: true },
    })
    await expect.poll(() => note.evaluate(() => getComputedStyle(document.querySelector('p')!).color))
        .toBe('rgb(240, 240, 240)')
    expect(await page.evaluate(() => getComputedStyle(document.getElementById('note-panel')!).backgroundColor))
        .toBe('rgb(38, 38, 38)')
    expect(await note.evaluate(() => getComputedStyle(document.documentElement).backgroundColor))
        .toBe('rgb(38, 38, 38)')
})

test.describe('on a narrow screen', () => {
    test.use({ viewport: { width: 280, height: 600 } })

    test('long texts of the panel stay on one line inside it, cut with an ellipsis', async ({ page }) => {
        await openFootnoteBook(page, {
            fontSizePx: 28,
            noteLabels: { goTo: 'Aller directement à la note dans le livre', title: 'Remarque du traducteur' },
        })
        await openNote(page, '#ref1')
        const seen = await page.evaluate(() => {
            const box = (id: string) => document.getElementById(id)!.getBoundingClientRect()
            const style = (id: string) => getComputedStyle(document.getElementById(id)!)
            const lines = (id: string) => box(id).height / parseFloat(style(id).lineHeight === 'normal'
                ? String(parseFloat(style(id).fontSize) * 1.2) : style(id).lineHeight)
            return {
                width: document.documentElement.clientWidth,
                panel: box('note-panel'), title: box('note-title'), go: box('note-go'),
                titleLines: lines('note-title'),
                titleCut: style('note-title').textOverflow, goCut: style('note-go').textOverflow,
                titleSize: parseFloat(style('note-title').fontSize), goSize: parseFloat(style('note-go').fontSize),
                pageScroll: document.documentElement.scrollWidth,
            }
        })
        expect(seen.pageScroll).toBeLessThanOrEqual(seen.width)
        expect(seen.panel.right).toBeLessThanOrEqual(seen.width)
        expect(seen.go.right).toBeLessThanOrEqual(seen.panel.right)
        expect(seen.title.left).toBeGreaterThanOrEqual(seen.panel.left)
        expect(seen.title.right).toBeLessThanOrEqual(seen.go.left)
        expect(seen.titleLines).toBeLessThan(1.5)
        expect([seen.titleCut, seen.goCut]).toEqual(['ellipsis', 'ellipsis'])
        // The header follows the reader's text size.
        expect(seen.titleSize).toBeGreaterThanOrEqual(24)
        expect(seen.goSize).toBeGreaterThanOrEqual(24)
        expect(seen.panel.height).toBeLessThanOrEqual(600 * MAX_SHARE + 0.5)
        expect(seen.panel.height).toBeGreaterThanOrEqual(600 * 0.2 - 0.5)
    })
})

// ---------------------------------------------------------------- more links that are not notes

for (const [ref, href, what] of [
    ['#ref10', 'OEBPS/ch1.xhtml#loop', 'with an id'],
    ['a[href="#circle"]', 'OEBPS/ch1.xhtml#circle', 'without an id'],
] as const) {
    test(`a "note" that holds its own reference is followed as a link (${what})`, async ({ page }) => {
        await openFootnoteBook(page)
        const main = await mainFrame(page)
        const from = await mark(page)
        await tapIn(page, main, ref)
        expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href, external: false })
        await settle(page)
        expect(await since(page, 'link', from)).toHaveLength(1)
        expect(await since(page, 'noteOpened', from)).toEqual([])
        expect(await isClosed(page)).toBe(true)
        await expect.poll(() => noteFrameCount(page)).toBe(0)
    })
}

test('a reference to something outside the reading order is followed at once', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page)
    const main = await mainFrame(page)
    const from = await mark(page)
    const started = Date.now()
    await tapIn(page, main, '#ref13')
    expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'OEBPS/images/chart.png', external: false })
    expect(Date.now() - started).toBeLessThan(3000)
    await settle(page)
    expect(await since(page, 'noteOpened', from)).toEqual([])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(errors).toEqual([])
})

test('in a fixed-layout book a note reference is a link like any other', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    await settle(page)
    const from = await mark(page)
    // The plates have no link of their own: the engine's event is sent as it would be for one.
    const followed = await page.evaluate(() => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const a = doc.createElementNS('http://www.w3.org/1999/xhtml', 'a')
        a.setAttributeNS('http://www.idpf.org/2007/ops', 'epub:type', 'noteref')
        a.setAttribute('href', 'page2.xhtml#plate')
        // Not cancelled: the engine follows the link itself.
        return view.dispatchEvent(new CustomEvent('link', {
            detail: { a, href: 'OEBPS/page2.xhtml#plate' }, cancelable: true,
        }))
    })
    expect(followed).toBe(true)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'link', href: 'OEBPS/page2.xhtml#plate', external: false }])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
})

// ---------------------------------------------------------------- more ways out

test('a link to the web inside a note is reported, and the panel stays', async ({ page }) => {
    await openFootnoteBook(page)
    const { note } = await openNote(page, '#ref5')
    const from = await mark(page)
    await tapIn(page, note, '#web')
    expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'https://example.org/tides', external: true })
    await settle(page)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['link'])
    expect((await panel(page)).open).toBe(true)
    expect(page.context().pages()).toHaveLength(1)
})

test('Escape closes the panel', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    const from = await mark(page)
    await page.keyboard.press('Escape')
    await waitFor(page, 'noteClosed', from)
    expect(await isClosed(page)).toBe(true)
})

test('the focus is not given back to a reference that is no longer on the page', async ({ page }) => {
    await openFootnoteBook(page)
    await openNote(page, '#ref1')
    let from = await mark(page)
    await send(page, { type: 'next' })
    expect((await relocatedAfter(page, from)).page).toBe(2)
    const turned = await position(page)
    from = await mark(page)
    await send(page, { type: 'closeNote' })
    await waitFor(page, 'noteClosed', from)
    await settle(page)
    const main = await mainFrame(page)
    expect(await main.evaluate(() => document.activeElement?.id ?? null)).not.toBe('ref1')
    expect(await position(page)).toEqual(turned)
    expect(await since(page, 'relocated', from)).toEqual([])
})

// ---------------------------------------------------------------- the engine's bookkeeping

test('opening and closing notes of the file on screen leaves that file loaded for the page', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page)
    const main = await mainFrame(page)
    const url = main.url()
    await page.evaluate(() => {
        const w = window as any
        w.lirelleRevoked = []
        const revoke = URL.revokeObjectURL.bind(URL)
        URL.revokeObjectURL = (u: string) => {
            w.lirelleRevoked.push(u)
            revoke(u)
        }
    })
    for (let i = 0; i < 3; i++) {
        await openNote(page, '#ref3')
        await send(page, { type: 'closeNote' })
        await expect.poll(() => isClosed(page)).toBe(true)
        await expect.poll(() => noteFrameCount(page)).toBe(0)
    }
    await settle(page)
    expect(await page.evaluate(() => (window as any).lirelleRevoked)).not.toContain(url)
    expect(await sectionUsers(page, 0)).toBe(1)
    // The page goes on: the next chapter's own file, and back.
    let from = await mark(page)
    await send(page, { type: 'goTo', href: 'OEBPS/notes.xhtml' })
    expect((await relocatedAfter(page, from)).sectionIndex).toBe(1)
    from = await mark(page)
    await send(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    expect((await relocatedAfter(page, from)).sectionIndex).toBe(0)
    await expect((await mainFrame(page)).locator('#ref3')).toBeVisible()
    expect(errors).toEqual([])
})

test('a reference tapped while another note loads replaces it, and the first view is let go', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, SLOW_NOTES)
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await tapIn(page, main, '#ref3')
    const opened = await waitFor(page, 'noteOpened', from)
    expect(opened.href).toBe('OEBPS/ch1.xhtml#n3')
    await page.waitForTimeout(2500)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['noteOpened'])
    await expect.poll(() => noteFrameCount(page)).toBe(1)
    await expect((await noteFrame(page)).locator('body')).toContainText('The lamp was trimmed')
    expect(await sectionUsers(page, 1)).toBe(0)
    expect(await sectionUsers(page, 0)).toBe(2)
    expect(errors).toEqual([])
})

test('closeNote while a note loads gives it up without a word', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, SLOW_NOTES)
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await send(page, { type: 'closeNote' })
    await page.waitForTimeout(2500)
    expect((await messages(page)).slice(from)).toEqual([])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(await sectionUsers(page, 1)).toBe(0)
    expect(errors).toEqual([])
})

test('opening a book while a note loads gives the note up', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, SLOW_NOTES)
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await openBook(page, 'basic')
    await page.waitForTimeout(2500)
    expect(await since(page, 'noteOpened', from)).toEqual([])
    expect(await since(page, 'error', from)).toEqual([])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(errors).toEqual([])
})

test('a document without a body, its book closed while it loaded, is not laid out again on a change of size',
    async ({ page }) => {
        const errors = pageErrors(page)
        await openFootnoteBook(page)
        // As a note's document whose book went while it loaded: the document on screen says it has no body, though
        // nothing in it changed (which would set off the view's own observer).
        await page.evaluate(() => {
            const view = document.getElementById('view') as any
            Object.defineProperty(view.renderer.getContents()[0].doc, 'body', { get: () => null })
        })
        await page.setViewportSize({ width: WIDTH - 40, height: HEIGHT - 60 })
        await page.waitForTimeout(300)
        await page.setViewportSize({ width: WIDTH, height: HEIGHT })
        await page.waitForTimeout(300)
        expect(errors).toEqual([])
    })

test('a note view given up while it loads is not laid out again when the window changes size', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, SLOW_NOTES)
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await send(page, { type: 'closeNote' })
    // While the view let go of finishes loading, unseen, the window changes size again and again.
    for (let i = 0; i < 30; i++) {
        await page.setViewportSize({ width: WIDTH - (i % 2) * 40, height: HEIGHT - (i % 3) * 30 })
        await page.waitForTimeout(80)
    }
    await page.setViewportSize({ width: WIDTH, height: HEIGHT })
    await expect.poll(() => noteFrameCount(page), { timeout: 15_000 }).toBe(0)
    expect(await since(page, 'noteOpened', from)).toEqual([])
    expect(errors).toEqual([])
})

test('a touch on the page or a turn while a note loads gives the note up', async ({ page }) => {
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, SLOW_NOTES)
    const main = await mainFrame(page)
    let from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT - 120)
    await waitFor(page, 'tap', from)
    await page.waitForTimeout(2500)
    expect((await messages(page)).slice(from).map(m => m.type)).toEqual(['tap'])
    expect(await isClosed(page)).toBe(true)

    // The notes file was let go with the view that loaded it: it comes slowly again.
    from = await mark(page)
    await tapIn(page, main, '#ref4')
    await send(page, { type: 'next' })
    await relocatedAfter(page, from)
    await page.waitForTimeout(1500)
    expect(await since(page, 'noteOpened', from)).toEqual([])
    expect(await since(page, 'link', from)).toEqual([])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(errors).toEqual([])
})

test('a note that does not come in time is given up, and its link followed', async ({ page }) => {
    test.setTimeout(60_000)
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, { 'footnote/OEBPS/notes.xhtml': 10_000 })
    const main = await mainFrame(page)
    const from = await mark(page)
    const started = Date.now()
    await tapIn(page, main, '#ref1')
    expect(await waitFor(page, 'link', from)).toEqual({ type: 'link', href: 'OEBPS/notes.xhtml#n1', external: false })
    expect(Date.now() - started).toBeGreaterThan(7000)
    expect((await relocatedAfter(page, from)).sectionIndex).toBe(1)
    await settle(page)
    expect(await since(page, 'noteOpened', from)).toEqual([])
    expect(await isClosed(page)).toBe(true)
    await expect.poll(() => noteFrameCount(page)).toBe(0)
    expect(await sectionUsers(page, 1)).toBe(1)
    expect(errors).toEqual([])
})

test('a view let go of whose note never comes is disposed after a while', async ({ page }) => {
    test.setTimeout(60_000)
    const errors = pageErrors(page)
    await openFootnoteBook(page, {}, { 'footnote/OEBPS/notes.xhtml': 40_000 })
    const main = await mainFrame(page)
    const from = await mark(page)
    await tapIn(page, main, '#ref1')
    await page.waitForTimeout(200)
    await send(page, { type: 'closeNote' })
    // Its document never came, so it has no frame yet: the view itself is counted.
    const views = () => page.evaluate(() => document.querySelectorAll('#note-panel foliate-view').length)
    await page.waitForTimeout(3000)
    expect(await views()).toBe(1)
    await expect.poll(views, { timeout: 15_000 }).toBe(0)
    expect((await messages(page)).slice(from)).toEqual([])
    expect(errors).toEqual([])
})
