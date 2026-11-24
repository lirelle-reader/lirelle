import { expect, test, type Page } from '@playwright/test'
import {
    contentFrame, mark, type Message, openBook, pixelsIn, relocatedAfter, send, sendAndRelocate, SETTINGS, settle,
    since, startReader, waitFor,
} from './harness'

const WIDTH = 390
const HEIGHT = 844
const YELLOW = '#facc15'
const BLUE = '#38bdf8'
const GREEN = '#4ade80'

type Rect = { left: number, top: number, right: number, bottom: number }
type Highlight = { id: string, cfi: string, color: string, style: string, note: boolean }

// ---------------------------------------------------------------- helpers

/** A range CFI over characters [start, end) of the text of an element of the section on screen. */
const cfiOf = (page: Page, selector: string, start: number, end: number): Promise<string> =>
    page.evaluate(([selector, start, end]) => {
        const view = document.getElementById('view') as any
        const { doc, index } = view.renderer.getContents()[0]
        const el = doc.querySelector(selector)
        const walker = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT)
        const range = doc.createRange()
        let seen = 0
        let started = false
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            const length = node.data.length
            if (!started && start < seen + length) {
                range.setStart(node, start - seen)
                started = true
            }
            if (started && end <= seen + length) {
                range.setEnd(node, end - seen)
                break
            }
            seen += length
        }
        return view.getCFI(index, range) as string
    }, [selector, start, end] as const)

/** The rects of a CFI in the section on screen, in the page's coordinates. */
const rectsOf = (page: Page, cfi: string): Promise<Rect[]> =>
    page.evaluate(cfi => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const range = view.resolveCFI(cfi).anchor(doc)
        const frame = doc.defaultView.frameElement.getBoundingClientRect()
        return [...range.getClientRects()].filter((r: DOMRect) => r.width > 0 && r.height > 0)
            .map((r: DOMRect) => ({
                left: frame.left + r.left, top: frame.top + r.top,
                right: frame.left + r.right, bottom: frame.top + r.bottom,
            }))
    }, cfi)

const firstRect = async (page: Page, cfi: string): Promise<Rect> => {
    const rects = await rectsOf(page, cfi)
    expect(rects.length, 'the range is laid out').toBeGreaterThan(0)
    const [rect] = rects
    expect(rect.left >= 0 && rect.right <= WIDTH && rect.top >= 0 && rect.bottom <= HEIGHT,
        'the range is on the page').toBe(true)
    return rect
}

/** The inside of a rect, as a clip of whole pixels. */
const insideOf = (rect: Rect) => ({
    x: Math.ceil(rect.left), y: Math.ceil(rect.top),
    width: Math.max(1, Math.floor(rect.right - rect.left) - 1),
    height: Math.max(1, Math.floor(rect.bottom - rect.top) - 1),
})

const rgbOf = (hex: string) => {
    const n = parseInt(hex.slice(1), 16)
    return [n >> 16, (n >> 8) & 255, n & 255]
}

/** A colour laid over white at an opacity. */
const overWhite = (hex: string, opacity: number) => rgbOf(hex).map(c => Math.round(255 * (1 - opacity) + c * opacity))

/** The share of the pixels of a part of the page that have a colour, give or take a few units. */
const shareOf = async (page: Page, rect: Rect, rgb: number[]): Promise<number> => {
    const pixels = await pixelsIn(page, insideOf(rect))
    const near = pixels.filter(p => p.every((c, i) => Math.abs(c - rgb[i]) <= 3))
    return near.length / pixels.length
}

const darkShare = async (page: Page, rect: Rect): Promise<number> => {
    const pixels = await pixelsIn(page, insideOf(rect))
    return pixels.filter(p => p.every(c => c < 90)).length / pixels.length
}

const highlight = (id: string, cfi: string, more: Partial<Highlight> = {}): Highlight =>
    ({ id, cfi, color: YELLOW, style: 'highlight', note: false, ...more })

/** The drawings of the overlayer of the section on screen. */
const drawings = (page: Page): Promise<number> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const content = view.renderer.getContents()[0]
    return content && content.overlayer ? content.overlayer.element.childElementCount : 0
})

/**
 * The rects drawn for the only highlight of the section on screen, and the rects of its range now, both in the
 * coordinates of the section's document.
 */
const drawnAndLaidOut = (page: Page, cfi: string) => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc, overlayer } = view.renderer.getContents()[0]
    const round = (n: number) => Math.round(n * 10) / 10
    const drawn = [...overlayer.element.querySelectorAll('rect')].map((r: SVGRectElement) =>
        [r.x.baseVal.value, r.y.baseVal.value, r.width.baseVal.value, r.height.baseVal.value].map(round))
    const laidOut = [...view.resolveCFI(cfi).anchor(doc).getClientRects()].map((r: DOMRect) =>
        [r.left, r.top, r.width, r.height].map(round))
    return { drawn, laidOut }
}, cfi)

const expectDrawnWhereLaidOut = async (page: Page, cfi: string) => {
    await expect.poll(async () => {
        const { drawn, laidOut } = await drawnAndLaidOut(page, cfi)
        return laidOut.length > 0 && JSON.stringify(drawn) === JSON.stringify(laidOut)
    }).toBe(true)
}

/** The colour of the drawing on top of the overlayer of the section on screen. */
const topFill = (page: Page): Promise<string | null> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const top = view.renderer.getContents()[0].overlayer.element.lastElementChild
    return top ? (top.firstElementChild as SVGElement).style.fill : null
})

const center = (r: Rect): [number, number] => [(r.left + r.right) / 2, (r.top + r.bottom) / 2]

/** Turns the pages of the section on screen from its first page, and returns the bookmarks each one reports. */
const bookmarksByPage = async (page: Page, href: string): Promise<Message[]> => {
    const first = await sendAndRelocate(page, { type: 'goTo', href })
    const seen = [first]
    for (let i = 0; i < 40; i++) {
        const next = await sendAndRelocate(page, { type: 'next' })
        if (next.sectionIndex !== first.sectionIndex) return seen
        seen.push(next)
    }
    throw new Error('The chapter never ended')
}

const pageErrors = (page: Page) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(String(e.stack ?? e)))
    return errors
}

// ---------------------------------------------------------------- highlights

test('highlights are drawn in their colour, over the text', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#plain', 4, 40)
    const rect = await firstRect(page, cfi)
    expect(await shareOf(page, rect, overWhite(YELLOW, 0.3))).toBe(0)
    expect(await drawings(page)).toBe(0)

    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    await expect.poll(() => shareOf(page, rect, overWhite(YELLOW, 0.3))).toBeGreaterThan(0.3)
    expect(await drawings(page)).toBe(1)
    // The text is still drawn under it.
    expect(await darkShare(page, rect)).toBeGreaterThan(0.02)
})

test('every style is drawn, and invert-like unknown styles fall back to a highlight', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const styles = ['highlight', 'underline', 'strikethrough', 'squiggly', 'invert']
    const items = []
    for (const [i, style] of styles.entries())
        items.push(highlight(`s${i}`, await cfiOf(page, '#third', i * 20, i * 20 + 12), { style }))
    await send(page, { type: 'highlights', items })
    await expect.poll(() => drawings(page)).toBe(5)
    const shapes = await page.evaluate(() => {
        const view = document.getElementById('view') as any
        const svg = view.renderer.getContents()[0].overlayer.element as SVGElement
        return [...svg.children].map(g => ({
            children: g.querySelectorAll('rect, path').length,
            fill: (g.firstElementChild as SVGElement).style.fill || (g.firstElementChild as SVGElement).style.stroke,
        }))
    })
    for (const shape of shapes) {
        expect(shape.children).toBeGreaterThan(0)
        expect(shape.fill).toBe('rgb(250, 204, 21)')
    }
})

test('a highlight is drawn again after a font size change, a chapter change and a flow change', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#plain', 4, 40)
    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    const tint = overWhite(YELLOW, 0.3)
    await expect.poll(async () => shareOf(page, await firstRect(page, cfi), tint)).toBeGreaterThan(0.3)

    await expectDrawnWhereLaidOut(page, cfi)
    const before = await drawnAndLaidOut(page, cfi)
    let from = await mark(page)
    await send(page, { type: 'settings', settings: { ...SETTINGS, fontSizePx: 24 } })
    await relocatedAfter(page, from)
    await settle(page)
    // The text moved, and the drawing followed it.
    const after = await drawnAndLaidOut(page, cfi)
    expect(JSON.stringify(after.laidOut)).not.toBe(JSON.stringify(before.laidOut))
    await expectDrawnWhereLaidOut(page, cfi)
    await expect.poll(async () => shareOf(page, await firstRect(page, cfi), tint)).toBeGreaterThan(0.3)

    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    await settle(page)
    expect(await drawings(page)).toBe(1)
    await expectDrawnWhereLaidOut(page, cfi)
    await expect.poll(async () => shareOf(page, await firstRect(page, cfi), tint)).toBeGreaterThan(0.3)

    from = await mark(page)
    await send(page, { type: 'settings', settings: { ...SETTINGS, fontSizePx: 24, flow: 'scrolled' } })
    await relocatedAfter(page, from)
    await settle(page)
    await expectDrawnWhereLaidOut(page, cfi)
    await expect.poll(async () => shareOf(page, await firstRect(page, cfi), tint)).toBeGreaterThan(0.3)
})

test('removeHighlight removes it and putHighlight adds or replaces by id', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const first = await cfiOf(page, '#plain', 4, 40)
    const second = await cfiOf(page, '#third', 0, 30)
    await send(page, { type: 'highlights', items: [highlight('h1', first)] })
    const yellow = overWhite(YELLOW, 0.3)
    const blue = overWhite(BLUE, 0.3)
    const firstRectOnPage = await firstRect(page, first)
    const secondRectOnPage = await firstRect(page, second)
    await expect.poll(() => shareOf(page, firstRectOnPage, yellow)).toBeGreaterThan(0.3)

    // Replaced: moved and recoloured.
    await send(page, { type: 'putHighlight', item: highlight('h1', second, { color: BLUE }) })
    await expect.poll(() => shareOf(page, secondRectOnPage, blue)).toBeGreaterThan(0.3)
    expect(await shareOf(page, firstRectOnPage, yellow)).toBe(0)
    expect(await drawings(page)).toBe(1)

    // Added.
    await send(page, { type: 'putHighlight', item: highlight('h2', first) })
    await expect.poll(() => shareOf(page, firstRectOnPage, yellow)).toBeGreaterThan(0.3)
    expect(await drawings(page)).toBe(2)

    await send(page, { type: 'removeHighlight', id: 'h1' })
    await expect.poll(() => shareOf(page, secondRectOnPage, blue)).toBe(0)
    expect(await shareOf(page, firstRectOnPage, yellow)).toBeGreaterThan(0.3)
    await send(page, { type: 'removeHighlight', id: 'h2' })
    await expect.poll(() => drawings(page)).toBe(0)
    expect(await shareOf(page, firstRectOnPage, yellow)).toBe(0)

    // A new set replaces the old one entirely.
    await send(page, { type: 'highlights', items: [highlight('a', first), highlight('b', second)] })
    await expect.poll(() => drawings(page)).toBe(2)
    await send(page, { type: 'highlights', items: [highlight('c', second, { color: GREEN })] })
    await expect.poll(() => drawings(page)).toBe(1)
    await expect.poll(() => shareOf(page, secondRectOnPage, overWhite(GREEN, 0.3))).toBeGreaterThan(0.3)
    expect(await shareOf(page, firstRectOnPage, yellow)).toBe(0)
})

test('the blending and the opacity follow the settings without new highlights', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#plain', 4, 40)
    const rect = await firstRect(page, cfi)
    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    await expect.poll(() => shareOf(page, rect, overWhite(YELLOW, 0.3))).toBeGreaterThan(0.3)

    // Under the ink at full strength: the paper takes the colour, the letters stay dark.
    await send(page, { type: 'settings', settings: { ...SETTINGS, highlightBlend: 'multiply', highlightOpacity: 1 } })
    await expect.poll(() => shareOf(page, rect, rgbOf(YELLOW))).toBeGreaterThan(0.3)
    expect(await darkShare(page, rect)).toBeGreaterThan(0.02)

    // Over the ink at full strength: nothing of the letters shows through.
    await send(page, { type: 'settings', settings: { ...SETTINGS, highlightBlend: 'normal', highlightOpacity: 1 } })
    await expect.poll(() => darkShare(page, rect)).toBe(0)
    expect(await shareOf(page, rect, rgbOf(YELLOW))).toBeGreaterThan(0.9)

    // Out-of-range values fall back within bounds.
    await send(page, { type: 'settings', settings: { ...SETTINGS, highlightBlend: 'screen', highlightOpacity: 0 } })
    await expect.poll(() => shareOf(page, rect, overWhite(YELLOW, 0.1))).toBeGreaterThan(0.3)
})

test('a highlight with a note has a mark at the end of its last rect', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const plain = await cfiOf(page, '#plain', 4, 40)
    // Long enough to run over several lines.
    const noted = await cfiOf(page, '#third', 10, 150)
    await send(page, {
        type: 'highlights',
        items: [highlight('plain', plain), highlight('noted', noted, { note: true })],
    })
    await expect.poll(() => drawings(page)).toBe(2)
    const seen = await page.evaluate(cfi => {
        const view = document.getElementById('view') as any
        const { doc, overlayer } = view.renderer.getContents()[0]
        const rects = [...view.resolveCFI(cfi).anchor(doc).getClientRects()]
            .filter((r: DOMRect) => r.width > 0 && r.height > 0)
        const last = rects[rects.length - 1] as DOMRect
        const marks = [...overlayer.element.querySelectorAll('circle')] as SVGCircleElement[]
        return {
            lines: rects.length,
            marks: marks.map(c => ({ x: c.cx.baseVal.value, y: c.cy.baseVal.value })),
            end: { x: last.right, top: last.top, bottom: last.bottom },
        }
    }, noted)
    expect(seen.lines).toBeGreaterThan(1)
    expect(seen.marks).toHaveLength(1)
    const [note] = seen.marks
    expect(Math.abs(note.x - seen.end.x)).toBeLessThanOrEqual(6)
    expect(note.y).toBeGreaterThanOrEqual(seen.end.top - 6)
    expect(note.y).toBeLessThanOrEqual(seen.end.bottom)
})

// ---------------------------------------------------------------- taps

test('a tap on a highlight sends highlightTap with its id and place, and no tap', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#third', 0, 120)
    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    await expect.poll(() => drawings(page)).toBe(1)
    const rects = await rectsOf(page, cfi)
    const from = await mark(page)
    await page.touchscreen.tap(...center(rects[0]))
    const tap = await waitFor(page, 'highlightTap', from)
    expect(tap.id).toBe('h1')
    const box = {
        left: Math.min(...rects.map(r => r.left)) / WIDTH,
        top: Math.min(...rects.map(r => r.top)) / HEIGHT,
        right: Math.max(...rects.map(r => r.right)) / WIDTH,
        bottom: Math.max(...rects.map(r => r.bottom)) / HEIGHT,
    }
    for (const side of ['left', 'top', 'right', 'bottom'] as const)
        expect(Math.abs(tap.bounds[side] - box[side])).toBeLessThan(0.01)
    await settle(page)
    expect(await since(page, 'tap', from)).toEqual([])
    expect(await since(page, 'highlightTap', from)).toHaveLength(1)
})

test('where highlights overlap, the tap goes to the one added last', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const a = await cfiOf(page, '#plain', 0, 40)
    const b = await cfiOf(page, '#plain', 20, 60)
    const overlap = await cfiOf(page, '#plain', 25, 35)
    await send(page, { type: 'highlights', items: [highlight('a', a), highlight('b', b, { color: BLUE })] })
    await expect.poll(() => drawings(page)).toBe(2)
    const spot = center((await rectsOf(page, overlap))[0])

    let from = await mark(page)
    await page.touchscreen.tap(...spot)
    expect((await waitFor(page, 'highlightTap', from)).id).toBe('b')

    await send(page, { type: 'putHighlight', item: highlight('a', a, { color: GREEN }) })
    await expect.poll(() => topFill(page)).toBe('rgb(74, 222, 128)')
    from = await mark(page)
    await page.touchscreen.tap(...spot)
    expect((await waitFor(page, 'highlightTap', from)).id).toBe('a')

    // Only the first part of `a` lies outside `b`.
    from = await mark(page)
    await page.touchscreen.tap(...center((await rectsOf(page, await cfiOf(page, '#plain', 2, 10)))[0]))
    expect((await waitFor(page, 'highlightTap', from)).id).toBe('a')
})

test('a tap on plain text next to a highlight is still a tap', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#plain', 4, 40)
    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    await expect.poll(() => drawings(page)).toBe(1)
    const plain = (await rectsOf(page, await cfiOf(page, '#third', 0, 20)))[0]
    const from = await mark(page)
    await page.touchscreen.tap(...center(plain))
    const tap = await waitFor(page, 'tap', from)
    expect(tap.x).toBeCloseTo(center(plain)[0] / WIDTH, 2)
    await settle(page)
    expect(await since(page, 'highlightTap', from)).toEqual([])
})

// ---------------------------------------------------------------- bookmarks

test('each page reports exactly the bookmarks made on it', async ({ page }) => {
    test.setTimeout(60_000)
    await startReader(page)
    const { relocated: one } = await openBook(page, 'marks')
    expect(one.bookmarkIds).toEqual([])
    expect(one.page).toBe(1)
    await sendAndRelocate(page, { type: 'next' })
    const three = await sendAndRelocate(page, { type: 'next' })
    await sendAndRelocate(page, { type: 'next' })
    const five = await sendAndRelocate(page, { type: 'next' })
    expect(five.page).toBe(5)
    expect(five.bookmarkIds).toEqual([])
    // The same place in the document of another chapter.
    const elsewhere = one.cfi.replace('epubcfi(/6/4!', 'epubcfi(/6/8!')
    expect(elsewhere).not.toBe(one.cfi)

    // Answered with a relocation, since the page on screen now holds one.
    const answer = await sendAndRelocate(page, {
        type: 'bookmarks',
        items: [
            { id: 'b1', cfi: one.cfi }, { id: 'b3', cfi: three.cfi }, { id: 'b5', cfi: five.cfi },
            { id: 'other', cfi: elsewhere },
        ],
    })
    expect(answer.bookmarkIds).toEqual(['b5'])
    expect(answer.page).toBe(5)
    expect(answer.cfi).toBe(five.cfi)

    const pages = await bookmarksByPage(page, 'OEBPS/ch1.xhtml')
    expect(pages.length).toBeGreaterThan(5)
    for (const relocated of pages) {
        const expected = { 1: ['b1'], 3: ['b3'], 5: ['b5'] }[relocated.page as number] ?? []
        expect(relocated.bookmarkIds, `page ${relocated.page}`).toEqual(expected)
    }

    // A new set without the page's bookmark is answered too.
    await sendAndRelocate(page, { type: 'goTo', cfi: three.cfi })
    const cleared = await sendAndRelocate(page, { type: 'bookmarks', items: [{ id: 'b1', cfi: one.cfi }] })
    expect(cleared.bookmarkIds).toEqual([])
    expect(cleared.page).toBe(3)
})

test('after a font size change each bookmark is still reported on exactly one page', async ({ page }) => {
    test.setTimeout(60_000)
    await startReader(page)
    const { relocated: one } = await openBook(page, 'marks')
    await sendAndRelocate(page, { type: 'next' })
    const three = await sendAndRelocate(page, { type: 'next' })
    await sendAndRelocate(page, { type: 'next' })
    const five = await sendAndRelocate(page, { type: 'next' })
    await sendAndRelocate(page, {
        type: 'bookmarks',
        items: [{ id: 'b1', cfi: one.cfi }, { id: 'b3', cfi: three.cfi }, { id: 'b5', cfi: five.cfi }],
    })
    for (const fontSizePx of [13, 26]) {
        const from = await mark(page)
        await send(page, { type: 'settings', settings: { ...SETTINGS, fontSizePx } })
        await relocatedAfter(page, from)
        const pages = await bookmarksByPage(page, 'OEBPS/ch1.xhtml')
        const reported = pages.flatMap(p => p.bookmarkIds as string[])
        expect(reported.sort(), `at ${fontSizePx}px`).toEqual(['b1', 'b3', 'b5'])
    }
})

// A line cut by the bottom of the view is in two visible parts in a row: a bookmark there shows on both.
test('in scrolled layout the bookmarks in the visible part are reported', async ({ page }) => {
    test.setTimeout(60_000)
    await startReader(page)
    const { relocated: top } = await openBook(page, 'marks', { settings: { flow: 'scrolled' } })
    expect(top.bookmarkIds).toEqual([])
    // The start of a paragraph far down the chapter.
    const deep = await cfiOf(page, 'body > p:nth-of-type(14)', 0, 0)
    const answer = await sendAndRelocate(page, {
        type: 'bookmarks', items: [{ id: 'top', cfi: top.cfi }, { id: 'deep', cfi: deep }],
    })
    expect(answer.bookmarkIds).toEqual(['top'])

    const there = await sendAndRelocate(page, { type: 'goTo', cfi: deep })
    expect(there.bookmarkIds).toContain('deep')
    expect(there.bookmarkIds).not.toContain('top')

    const pages = await bookmarksByPage(page, 'OEBPS/ch1.xhtml')
    expect(pages[0].bookmarkIds).toContain('top')
    for (const id of ['top', 'deep']) {
        const holding = pages.flatMap((p, i) => (p.bookmarkIds as string[]).includes(id) ? [i] : [])
        expect(holding.length, id).toBeGreaterThanOrEqual(1)
        expect(holding.length, id).toBeLessThanOrEqual(2)
        expect(holding[holding.length - 1] - holding[0], id).toBe(holding.length - 1)
    }
})

test('in a fixed-layout book a bookmark belongs to its page', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    const { relocated: first } = await openBook(page, 'fixed')
    const second = await sendAndRelocate(page, { type: 'next' })
    expect(second.sectionIndex).toBe(1)
    const answer = await sendAndRelocate(page, { type: 'bookmarks', items: [{ id: 'p2', cfi: second.cfi }] })
    expect(answer.bookmarkIds).toEqual(['p2'])
    const back = await sendAndRelocate(page, { type: 'prev' })
    expect(back.sectionIndex).toBe(first.sectionIndex)
    expect(back.bookmarkIds).toEqual([])

    // Nothing is drawn, and nothing breaks.
    await send(page, { type: 'highlights', items: [highlight('h', 'epubcfi(/6/2!/4/2,/1:0,/1:5)')] })
    await settle(page, 300)
    const from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    expect((await waitFor(page, 'tap', from)).type).toBe('tap')
    expect(await since(page, 'error', 0)).toEqual([])
    expect(errors).toEqual([])
})

// ---------------------------------------------------------------- open, robustness

test('opened carries the label of the contents entry of each section', async ({ page }) => {
    await startReader(page)
    const { opened: basic } = await openBook(page, 'basic')
    expect(basic.sectionLabels).toEqual(['The Green Lighthouse', 'The Missing Gull', 'The Morning Boat'])
    const { opened: marks } = await openBook(page, 'marks')
    // A title page before the first entry, then a chapter missing from the contents, which belongs to the
    // last entry before it.
    expect(marks.sectionLabels).toEqual([null, 'The Tide Ledger', 'A Note in the Margin', 'The Last Lantern'])
    const { opened: bare } = await openBook(page, 'bare')
    expect(bare.sectionLabels).toEqual([null, null])
})

test('a mark that cannot be placed is skipped without an error', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    const { relocated } = await openBook(page, 'marks')
    const good = await cfiOf(page, '#plain', 4, 40)
    const bad = [
        'epubcfi(/6/40!/4/2,/1:0,/1:5)',
        'epubcfi(/6/4!/4/999/7,/1:0,/1:5)',
        'epubcfi(nonsense)',
        'not a cfi',
    ]
    await send(page, {
        type: 'highlights',
        items: [
            ...bad.map((cfi, i) => highlight(`bad${i}`, cfi)),
            highlight('', good), { id: 'x'.repeat(65), cfi: good, color: YELLOW, style: 'highlight', note: false },
            highlight('colour', good, { color: 'yellow' }), null, 42,
            highlight('good', good),
        ],
    })
    await expect.poll(() => drawings(page)).toBe(1)
    const answer = await sendAndRelocate(page, {
        type: 'bookmarks',
        items: [...bad.map((cfi, i) => ({ id: `bad${i}`, cfi })), { id: 'here', cfi: relocated.cfi }, 'junk'],
    })
    expect(answer.bookmarkIds).toEqual(['here'])
    await send(page, { type: 'highlights', items: 'not a list' })
    await send(page, { type: 'putHighlight', item: { id: 'p', cfi: bad[0], color: YELLOW, style: 'highlight' } })
    await send(page, { type: 'removeHighlight', id: 'nothing' })
    await sendAndRelocate(page, { type: 'next' })
    await settle(page, 300)
    expect(await since(page, 'error', 0)).toEqual([])
    expect(errors).toEqual([])
})

test('opening a book clears its highlights and bookmarks', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#plain', 4, 40)
    await send(page, { type: 'highlights', items: [highlight('h1', cfi)] })
    const answer = await sendAndRelocate(page, { type: 'bookmarks', items: [{ id: 'b1', cfi: relocated.cfi }] })
    expect(answer.bookmarkIds).toEqual(['b1'])
    expect(await drawings(page)).toBe(1)

    const { relocated: again } = await openBook(page, 'marks', { cfi: relocated.cfi })
    expect(again.cfi).toBe(relocated.cfi)
    expect(again.bookmarkIds).toEqual([])
    await settle(page)
    expect(await drawings(page)).toBe(0)
})

test('goTo with the CFI of a spine item lands at the start of that section', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    const later = await sendAndRelocate(page, { type: 'next' })
    expect(later.page).toBe(2)
    // Spine item i is step 2(i + 1); /4 is the body of its document.
    const ch1 = await sendAndRelocate(page, { type: 'goTo', cfi: 'epubcfi(/6/4!/4)' })
    expect(ch1.sectionIndex).toBe(1)
    expect(ch1.page).toBe(1)
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    const inside = await sendAndRelocate(page, { type: 'next' })
    expect(inside.page).toBe(2)
    const ch2 = await sendAndRelocate(page, { type: 'goTo', cfi: 'epubcfi(/6/6!/4)' })
    expect(ch2.sectionIndex).toBe(2)
    expect(ch2.page).toBe(1)
})

test('the book\'s own document is never changed by marks', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'marks')
    await settle(page)
    const frame = await contentFrame(page)
    const snapshot = () => frame.evaluate(() => ({
        elements: document.body.getElementsByTagName('*').length,
        html: document.body.innerHTML,
    }))
    const before = await snapshot()

    const cfi = await cfiOf(page, '#inline', 10, 120)
    await send(page, {
        type: 'highlights',
        items: [highlight('noted', cfi, { note: true }), highlight('under', await cfiOf(page, '#plain', 0, 30),
            { style: 'squiggly' })],
    })
    await sendAndRelocate(page, { type: 'bookmarks', items: [{ id: 'b1', cfi: relocated.cfi }] })
    await expect.poll(() => drawings(page)).toBe(2)
    const from = await mark(page)
    await page.touchscreen.tap(...center((await rectsOf(page, cfi))[0]))
    expect((await waitFor(page, 'highlightTap', from)).id).toBe('noted')
    await send(page, { type: 'removeHighlight', id: 'under' })
    await expect.poll(() => drawings(page)).toBe(1)

    expect(await snapshot()).toEqual(before)
})
