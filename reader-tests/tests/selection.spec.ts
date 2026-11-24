import { type CDPSession, expect, type Page, test } from '@playwright/test'
import {
    contentFrame, drag, mark, type Message, messages, openBook, relocatedAfter, send, sendAndRelocate, SETTINGS, settle,
    since, startReader, waitFor,
} from './harness'

const WIDTH = 390
const HEIGHT = 844
const YELLOW = '#facc15'

type Bounds = { left: number, top: number, right: number, bottom: number }

/**
 * What to select in the section on screen: characters [start, end) of the text of an element (the end may lie in
 * another element), the contents of an element, an element itself, the text node that follows an element, or a CFI.
 */
type Pick =
    | { selector: string, start: number, end: number, endSelector?: string }
    | { contents: string }
    | { around: string }
    | { textAfter: string }
    | { cfi: string }
    | { from: string, offset: number, endBefore: string }

// ---------------------------------------------------------------- helpers

/** Sets the selection of the section on screen, as a script would, and returns the text of the range. */
const selectIn = (page: Page, pick: Pick): Promise<string> => page.evaluate(pick => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const textPoint = (el: Element, offset: number, isEnd: boolean): [Node, number] => {
        const walker = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT)
        let seen = 0
        for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
            const length = node.data.length
            if (isEnd ? offset <= seen + length : offset < seen + length) return [node, offset - seen]
            seen += length
        }
        throw new Error(`No character ${offset} in ${el.id}`)
    }
    let range = doc.createRange()
    if ('cfi' in pick) range = view.resolveCFI(pick.cfi).anchor(doc)
    else if ('contents' in pick) range.selectNodeContents(doc.querySelector(pick.contents))
    else if ('around' in pick) range.selectNode(doc.querySelector(pick.around))
    else if ('textAfter' in pick) range.selectNodeContents(doc.querySelector(pick.textAfter).nextSibling)
    else if ('from' in pick) {
        range.setStart(...textPoint(doc.querySelector(pick.from), pick.offset, false))
        range.setEndBefore(doc.querySelector(pick.endBefore))
    } else {
        range.setStart(...textPoint(doc.querySelector(pick.selector), pick.start, false))
        range.setEnd(...textPoint(doc.querySelector(pick.endSelector ?? pick.selector), pick.end, true))
    }
    const selection = doc.getSelection()
    selection.removeAllRanges()
    selection.addRange(range)
    return range.toString() as string
}, pick)

/** The middle of the first line of characters [start, end) of an element of the section on screen, on the page. */
const pointOf = (page: Page, selector: string, start: number, end: number): Promise<[number, number]> =>
    page.evaluate(([selector, start, end]) => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const text = doc.querySelector(selector).firstChild
        const range = doc.createRange()
        range.setStart(text, start)
        range.setEnd(text, end)
        const frame = doc.defaultView.frameElement.getBoundingClientRect()
        const r = [...range.getClientRects()].find((r: DOMRect) => r.width > 0) as DOMRect
        return [frame.left + (r.left + r.right) / 2, frame.top + (r.top + r.bottom) / 2] as [number, number]
    }, [selector, start, end] as const)

/** A selector of a paragraph of the section on screen whose first line is on the page. */
const paragraphOnScreen = (page: Page): Promise<string> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    const paragraphs = [...doc.querySelectorAll('body > p')] as HTMLElement[]
    for (const [i, p] of paragraphs.entries()) {
        const range = doc.createRange()
        range.setStart(p.firstChild, 0)
        range.setEnd(p.firstChild, 40)
        const r = range.getBoundingClientRect()
        const left = frame.left + r.left
        const top = frame.top + r.top
        if (left >= 0 && left + r.width <= innerWidth && top >= 0 && top + r.height <= innerHeight - 100)
            return `body > p:nth-of-type(${i + 1})`
    }
    throw new Error('No paragraph starts on the page')
})

/** A range CFI over characters [start, end) of the first text node of an element of the section on screen. */
const cfiOf = (page: Page, selector: string, start: number, end: number): Promise<string> =>
    page.evaluate(([selector, start, end]) => {
        const view = document.getElementById('view') as any
        const { doc, index } = view.renderer.getContents()[0]
        const text = doc.querySelector(selector).firstChild
        const range = doc.createRange()
        range.setStart(text, start)
        range.setEnd(text, end)
        return view.getCFI(index, range) as string
    }, [selector, start, end] as const)

/** The text a CFI resolves to in the section on screen, and its section. */
const resolved = (page: Page, cfi: string) => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const target = view.resolveCFI(cfi)
    return { index: target.index as number, text: target.anchor(doc).toString() as string }
}, cfi)

/** The visible part of the range of a CFI in the section on screen, as fractions of the page. */
const boundsOf = (page: Page, cfi: string): Promise<Bounds | null> => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    const rects = [...view.resolveCFI(cfi).anchor(doc).getClientRects()]
        .filter((r: DOMRect) => r.width > 0 && r.height > 0)
        .map((r: DOMRect) => ({
            left: frame.left + r.left, top: frame.top + r.top, right: frame.left + r.right, bottom: frame.top + r.bottom,
        }))
        .filter(r => r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight)
    if (!rects.length) return null
    const clamp = (n: number) => Math.min(1, Math.max(0, n))
    return {
        left: clamp(Math.min(...rects.map(r => r.left)) / innerWidth),
        top: clamp(Math.min(...rects.map(r => r.top)) / innerHeight),
        right: clamp(Math.max(...rects.map(r => r.right)) / innerWidth),
        bottom: clamp(Math.max(...rects.map(r => r.bottom)) / innerHeight),
    }
}, cfi)

/** The selection of the section on screen as the browser holds it. */
const liveSelection = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const selection = doc.getSelection()
    return { type: selection.type as string, text: selection.rangeCount ? selection.getRangeAt(0).toString() : '' }
})

/** Where the engine has scrolled to, and where the section's frame is on the page. */
const placement = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    return { start: view.renderer.start as number, left: doc.defaultView.frameElement.getBoundingClientRect().left }
})

const touch = (cdp: CDPSession, type: string, x?: number, y?: number) => cdp.send('Input.dispatchTouchEvent', {
    type, touchPoints: x === undefined ? [] : [{ x: Math.round(x), y: Math.round(y!) }],
})

/**
 * A selection made the way a long press makes one, as far as a headless browser allows: the finger goes down and
 * holds, the selection appears while it is down, the finger moves (the drag that extends a selection) and lifts.
 * Returns the text selected and how many messages had been recorded just before the finger lifted.
 */
const selectByTouch = async (page: Page, at: [number, number], pick: Pick, by: [number, number] = [40, 0]) => {
    const cdp = await page.context().newCDPSession(page)
    await touch(cdp, 'touchStart', ...at)
    await page.waitForTimeout(300)
    const text = await selectIn(page, pick)
    await page.waitForTimeout(100)
    const steps = 12
    for (let i = 1; i <= steps; i++) {
        await touch(cdp, 'touchMove', at[0] + by[0] * i / steps, at[1] + by[1] * i / steps)
        await page.waitForTimeout(16)
    }
    const beforeLift = await mark(page)
    await touch(cdp, 'touchEnd')
    await cdp.detach()
    return { text, beforeLift }
}

/** A double click of a mouse, which selects the word under it. */
const doubleClick = async (page: Page, x: number, y: number) => {
    const cdp = await page.context().newCDPSession(page)
    for (const clickCount of [1, 2]) {
        for (const type of ['mousePressed', 'mouseReleased'])
            await cdp.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount })
    }
    await cdp.detach()
}

const expectBoundsNear = (actual: Bounds, expected: Bounds | null) => {
    expect(expected, 'the range is visible').not.toBeNull()
    for (const side of ['left', 'top', 'right', 'bottom'] as const)
        expect(Math.abs(actual[side] - expected![side]), side).toBeLessThan(0.01)
    expect(actual.left).toBeLessThanOrEqual(actual.right)
    expect(actual.top).toBeLessThanOrEqual(actual.bottom)
    for (const value of Object.values(actual)) {
        expect(value).toBeGreaterThanOrEqual(0)
        expect(value).toBeLessThanOrEqual(1)
    }
}

const drawings = (page: Page): Promise<number> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const content = view.renderer.getContents()[0]
    return content && content.overlayer ? content.overlayer.element.childElementCount : 0
})

const pageErrors = (page: Page) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(String(e.stack ?? e)))
    return errors
}

const types = (all: Message[]) => all.map(m => m.type)

// ---------------------------------------------------------------- reporting

test('a selection made during a touch is reported when the finger lifts, and nothing else', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    await openBook(page, 'marks')
    await settle(page)
    const frame = await contentFrame(page)
    const snapshot = () => frame.evaluate(() => document.body.innerHTML)
    const before = await snapshot()

    const from = await mark(page)
    const at = await pointOf(page, '#plain', 4, 7)
    const { text, beforeLift } = await selectByTouch(page, at, { selector: '#plain', start: 4, end: 70 })
    // Nothing while the finger is down, long after the selection last changed.
    expect(beforeLift).toBe(from)
    const selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(text)
    expect(text.length).toBe(66)
    expect(selection.truncated).toBe(false)
    expect(selection.sectionIndex).toBe(1)
    expect(selection.highlightIds).toEqual([])
    expect(selection.exactId).toBeNull()
    expect(await resolved(page, selection.cfi)).toEqual({ index: 1, text })
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))

    await settle(page)
    expect(types(await messages(page)).slice(from)).toEqual(['selection'])
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
    expect(await snapshot()).toBe(before)
    expect(errors).toEqual([])
})

test('a word chosen by a double click is reported', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const from = await mark(page)
    // "dog" in "The dog belonged to the whole street".
    await doubleClick(page, ...await pointOf(page, '#plain', 4, 7))
    const selection = await waitFor(page, 'selection', from)
    expect(selection.text.trim()).toBe('dog')
    expect((await resolved(page, selection.cfi)).text).toBe(selection.text)
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
})

test('a selection bounded by elements is moved to text and still round-trips', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'selection')
    await settle(page)

    // The whole of a paragraph: both ends are on the paragraph element.
    let from = await mark(page)
    const whole = await selectIn(page, { contents: '#second' })
    let selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(whole)
    expect(await resolved(page, selection.cfi)).toEqual({ index: 0, text: whole })
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))

    // From inside a paragraph to the start of a later block, past a picture: the end comes back to the last text.
    from = await mark(page)
    const first = await page.evaluate(() => {
        const view = document.getElementById('view') as any
        return view.renderer.getContents()[0].doc.querySelector('#first').textContent as string
    })
    await selectIn(page, { from: '#first', offset: 10, endBefore: '#second' })
    selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(first.slice(10))
    expect(await resolved(page, selection.cfi)).toEqual({ index: 0, text: first.slice(10) })
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
})

test('a selection without text is not reported', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    await openBook(page, 'selection')
    await settle(page)
    const from = await mark(page)
    for (const pick of [{ around: '#figure' }, { contents: '#figure' }, { textAfter: '#c1' }] as Pick[]) {
        await selectIn(page, pick)
        await settle(page, 300)
    }
    // The same way of selecting, with text this time, is reported: nothing above was just slow.
    const text = await selectIn(page, { contents: '#first' })
    const selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(text)
    expect(await since(page, 'selection', from)).toHaveLength(1)
    expect(await since(page, 'selectionCleared', from)).toEqual([])
    expect(errors).toEqual([])
})

test('the text is cut at 10 000 characters', async ({ page }) => {
    await startReader(page)
    // In pages a selection is cut to the page first: a whole chapter can be selected in scrolled layout.
    await openBook(page, 'selection', { settings: { flow: 'scrolled' } })
    await settle(page)
    const from = await mark(page)
    const all = await selectIn(page, { contents: 'body' })
    expect(all.length).toBeGreaterThan(12_000)
    const selection = await waitFor(page, 'selection', from)
    expect(selection.truncated).toBe(true)
    expect(selection.text).toBe(all.trim().slice(0, 10_000))
    expect((await resolved(page, selection.cfi)).text.trim()).toBe(all.trim())
    // Only what is on the page counts for the bounds.
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
    expect(selection.bounds.bottom).toBeLessThanOrEqual(1)
})

test('a selection changed without a touch is reported 150 ms after it last changed', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const timing = await page.evaluate(async () => {
        const view = document.getElementById('view') as any
        const recorded = (window as any).lirelleMessages as Message[]
        const { doc } = view.renderer.getContents()[0]
        const text = doc.querySelector('#third').firstChild
        const selection = doc.getSelection()
        const from = recorded.length
        const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))
        const reported = () => recorded.slice(from).filter(m => m.type === 'selection')
        let last = 0
        // A handle dragged over five characters, one at a time.
        for (let end = 10; end <= 50; end += 10) {
            const range = doc.createRange()
            range.setStart(text, 0)
            range.setEnd(text, end)
            selection.removeAllRanges()
            selection.addRange(range)
            last = performance.now()
            await wait(40)
        }
        const during = reported().length
        while (!reported().length) await wait(5)
        return { during, after: performance.now() - last, text: reported()[0].text, expected: text.data.slice(0, 50) }
    })
    expect(timing.during).toBe(0)
    expect(timing.after).toBeGreaterThanOrEqual(140)
    expect(timing.after).toBeLessThan(400)
    expect(timing.text).toBe(timing.expected)
})

// ---------------------------------------------------------------- touches

test('a drag with a live selection moves no page and taps nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const where = await placement(page)
    const from = await mark(page)
    const at = await pointOf(page, '#third', 0, 3)
    // Far enough to the left to turn the page, were it a swipe.
    const { text } = await selectByTouch(page, at, { selector: '#third', start: 0, end: 40 }, [-216, 0])
    expect((await waitFor(page, 'selection', from)).text).toBe(text)
    await settle(page, 1000)
    expect(types(await messages(page)).slice(from)).toEqual(['selection'])
    expect(await placement(page)).toEqual(where)
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
})

test('a tap with a live selection clears it and is not a tap', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)

    from = await mark(page)
    // In the middle of the page: without a selection this is a tap that shows the controls.
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    await waitFor(page, 'selectionCleared', from)
    await settle(page)
    expect(types(await messages(page)).slice(from)).toEqual(['selectionCleared'])
    expect((await liveSelection(page)).type).not.toBe('Range')

    // The next tap is a tap again.
    from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    await waitFor(page, 'tap', from)
})

test('a tap on a highlight with a live selection only clears the selection', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const cfi = await cfiOf(page, '#third', 0, 120)
    await send(page, { type: 'highlights', items: [{ id: 'h1', cfi, color: YELLOW, style: 'highlight', note: false }] })
    await expect.poll(() => drawings(page)).toBe(1)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)
    from = await mark(page)
    await page.touchscreen.tap(...await pointOf(page, '#third', 10, 14))
    await waitFor(page, 'selectionCleared', from)
    await settle(page)
    expect(types(await messages(page)).slice(from)).toEqual(['selectionCleared'])
})

// ---------------------------------------------------------------- clearing

test('a page turned by the user clears the selection', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)

    // A swipe.
    from = await mark(page)
    await drag(page, [330, 400], [40, 400])
    const turned = await relocatedAfter(page, from)
    expect(turned.page).toBe(2)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
    expect(await since(page, 'tap', from)).toEqual([])
    expect((await liveSelection(page)).type).not.toBe('Range')

    // A turn asked for by the host on the user's behalf (a volume key).
    from = await mark(page)
    const paragraph = await paragraphOnScreen(page)
    await selectByTouch(page, await pointOf(page, paragraph, 0, 3), { selector: paragraph, start: 0, end: 30 })
    await waitFor(page, 'selection', from)
    from = await mark(page)
    expect((await sendAndRelocate(page, { type: 'next' })).page).toBe(3)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
    expect((await liveSelection(page)).type).not.toBe('Range')
})

test('a move asked for by the host keeps the selection, reported while it is on the page', async ({ page }) => {
    await startReader(page)
    const { relocated: one } = await openBook(page, 'marks')
    await sendAndRelocate(page, { type: 'next' })
    const three = await sendAndRelocate(page, { type: 'next' })
    expect(three.page).toBe(3)
    await sendAndRelocate(page, { type: 'goTo', cfi: one.cfi })
    await settle(page)

    let from = await mark(page)
    const { text } = await selectByTouch(page, await pointOf(page, '#plain', 4, 7),
        { selector: '#plain', start: 4, end: 40 })
    const selection = await waitFor(page, 'selection', from)

    // Elsewhere in the same section, as a search result or read-along would: the host has nothing left to show,
    // the browser keeps the selection.
    from = await mark(page)
    expect((await sendAndRelocate(page, { type: 'goTo', cfi: three.cfi })).page).toBe(3)
    await waitFor(page, 'selectionCleared', from)
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
    expect(await since(page, 'selection', from)).toEqual([])

    // Back on its page, it is reported again.
    from = await mark(page)
    expect((await sendAndRelocate(page, { type: 'goTo', cfi: one.cfi })).page).toBe(1)
    expect(await waitFor(page, 'selection', from)).toEqual(selection)
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })

    // While it is off the page, a tap is a tap.
    await sendAndRelocate(page, { type: 'goTo', cfi: three.cfi })
    await settle(page, 300)
    from = await mark(page)
    await page.touchscreen.tap(WIDTH / 2, HEIGHT / 2)
    await waitFor(page, 'tap', from)
})

test('in scrolled layout a move asked for by the host keeps the selection', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks', { settings: { flow: 'scrolled' } })
    await settle(page)
    let from = await mark(page)
    const { text } = await selectByTouch(page, await pointOf(page, '#third', 0, 3),
        { selector: '#third', start: 0, end: 40 })
    const selection = await waitFor(page, 'selection', from)

    // A little further down: the selection moves up and stays on screen.
    from = await mark(page)
    await sendAndRelocate(page, { type: 'goTo', cfi: await cfiOf(page, '#inline', 0, 0) })
    // The engine reports the end of a scroll late, and the page nudges it: still the host's move.
    await settle(page, 1500)
    expect(await since(page, 'selectionCleared', from)).toEqual([])
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
    const moved = await since(page, 'selection', from)
    expect(moved.length).toBeGreaterThan(0)
    const latest = moved[moved.length - 1]
    expect(latest.cfi).toBe(selection.cfi)
    expect(latest.bounds.top).toBeLessThan(selection.bounds.top)
    expectBoundsNear(latest.bounds, await boundsOf(page, latest.cfi))
})

/** In scrolled layout, a selection in the third paragraph, reported, then a move by the host just below the top. */
const selectThenMoveForHost = async (page: Page) => {
    await startReader(page)
    await openBook(page, 'marks', { settings: { flow: 'scrolled' } })
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#third', 0, 3), { selector: '#third', start: 0, end: 40 })
    await waitFor(page, 'selection', from)
    from = await mark(page)
    await send(page, { type: 'goTo', cfi: await cfiOf(page, '#inline', 0, 0) })
    return from
}

test('in scrolled layout a turn by the user right after a move by the host clears the selection', async ({ page }) => {
    const from = await selectThenMoveForHost(page)
    await page.waitForTimeout(100)
    // A volume key.
    await send(page, { type: 'next' })
    await expect.poll(async () => (await liveSelection(page)).type).not.toBe('Range')
    await waitFor(page, 'selectionCleared', from)
    await settle(page)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
})

test('in scrolled layout a scroll by the user right after a move by the host clears the selection', async ({ page }) => {
    const from = await selectThenMoveForHost(page)
    await waitFor(page, 'relocated', from)
    // A short scroll that stops before the finger lifts (no fling), so that it ends, and is reported, well inside
    // the time the end of the host's scroll may still be reported.
    const cdp = await page.context().newCDPSession(page)
    await touch(cdp, 'touchStart', 200, 640)
    for (let i = 1; i <= 6; i++) {
        await touch(cdp, 'touchMove', 200, 640 - 40 * i)
        await page.waitForTimeout(16)
    }
    for (let i = 0; i < 6; i++) {
        await touch(cdp, 'touchMove', 200, 400)
        await page.waitForTimeout(16)
    }
    await touch(cdp, 'touchEnd')
    await cdp.detach()
    await expect.poll(async () => (await liveSelection(page)).type).not.toBe('Range')
    await waitFor(page, 'selectionCleared', from)
    await settle(page)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
    expect(await since(page, 'tap', from)).toEqual([])
})

test('a finger resting on the page during a move by the host can still turn the page', async ({ page }) => {
    await startReader(page)
    const { relocated: one } = await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)

    from = await mark(page)
    const cdp = await page.context().newCDPSession(page)
    await touch(cdp, 'touchStart', 330, 400)
    // The engine collapses the selection when it navigates; the page puts it back while the finger is down.
    await send(page, { type: 'goTo', cfi: one.cfi })
    await settle(page, 800)
    expect((await liveSelection(page)).type).toBe('Range')
    // The host's move is answered where it led.
    expect(await since(page, 'relocated', from)).toMatchObject([{ byHost: true, cfi: one.cfi }])
    const beforeTurn = await mark(page)
    for (let i = 1; i <= 12; i++) {
        await touch(cdp, 'touchMove', 330 - 290 * i / 12, 400)
        await page.waitForTimeout(16)
    }
    await touch(cdp, 'touchEnd')
    await cdp.detach()
    const turned = await relocatedAfter(page, beforeTurn)
    expect(turned.page).toBe(2)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
})

test('leaving the section with a move by the host clears the selection', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)
    from = await mark(page)
    expect((await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })).sectionIndex).toBe(3)
    await waitFor(page, 'selectionCleared', from)
    await settle(page, 300)
    expect(await since(page, 'selectionCleared', from)).toHaveLength(1)
    expect(await since(page, 'selection', from)).toEqual([])
})

test('a selection laid out again by new settings is reported with its new bounds', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    const { text } = await selectByTouch(page, await pointOf(page, '#plain', 4, 7),
        { selector: '#plain', start: 4, end: 40 })
    const selection = await waitFor(page, 'selection', from)

    from = await mark(page)
    await send(page, { type: 'settings', settings: { ...SETTINGS, fontSizePx: 26 } })
    await relocatedAfter(page, from)
    await settle(page)
    const moved = await since(page, 'selection', from)
    expect(moved.length).toBeGreaterThan(0)
    const latest = moved[moved.length - 1]
    expect(latest.cfi).toBe(selection.cfi)
    expect(latest.text).toBe(text)
    expect(latest.bounds).not.toEqual(selection.bounds)
    expectBoundsNear(latest.bounds, await boundsOf(page, latest.cfi))
    expect(await since(page, 'selectionCleared', from)).toEqual([])
})

test('clearSelection clears it', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    let from = await mark(page)
    await selectByTouch(page, await pointOf(page, '#plain', 4, 7), { selector: '#plain', start: 4, end: 40 })
    await waitFor(page, 'selection', from)
    from = await mark(page)
    await send(page, { type: 'clearSelection' })
    await waitFor(page, 'selectionCleared', from)
    expect((await liveSelection(page)).type).not.toBe('Range')
    await settle(page)
    expect(types(await messages(page)).slice(from)).toEqual(['selectionCleared'])
    // Nothing to clear: nothing said.
    await send(page, { type: 'clearSelection' })
    await settle(page, 300)
    expect(types(await messages(page)).slice(from)).toEqual(['selectionCleared'])
})

// ---------------------------------------------------------------- one page at a time

test('a selection reaching past the page does not turn it', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const where = await placement(page)
    const from = await mark(page)
    const at = await pointOf(page, '#third', 0, 3)
    // From the third paragraph to one far down the chapter, on a later page.
    const { text } = await selectByTouch(page, at,
        { selector: '#third', start: 0, end: 20, endSelector: 'body > p:nth-of-type(14)' })
    const selection = await waitFor(page, 'selection', from)
    // Cut at the end of the page.
    expect(text.startsWith(selection.text)).toBe(true)
    expect(selection.text.length).toBeLessThan(text.length)
    // The engine's own turn comes 700 ms after the selection changed.
    await settle(page, 1500)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await placement(page)).toEqual(where)
    // Only the visible part counts for the bounds.
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
})

// ---------------------------------------------------------------- a selection stays on its page

/** Scrolls the engine's own scroller as the browser does when a selection handle is dragged past the page. */
const autoscroll = (page: Page, share: number) => page.evaluate(share => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const container = doc.defaultView.frameElement.closest('#container') as HTMLElement
    container.scrollLeft += container.clientWidth * share
    return container.scrollLeft
}, share)

/** The live selection of the section on screen: whether all of it is on the page, and where it starts. */
const selectionOnPage = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const sel = doc.getSelection()
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    const rects = [...sel.getRangeAt(0).getClientRects()].filter((r: DOMRect) => r.width > 0 && r.height > 0)
    return {
        text: sel.toString() as string,
        inside: rects.every((r: DOMRect) => frame.left + r.left >= -0.5 && frame.left + r.right <= innerWidth + 0.5),
        anchor: [sel.anchorNode.parentElement.id, sel.anchorOffset],
    }
})

test('a scroll of the browser while a selection is live is undone at once: the page stays', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const where = await placement(page)
    let from = await mark(page)
    const text = await selectIn(page, { selector: '#third', start: 0, end: 40 })
    const selection = await waitFor(page, 'selection', from)
    from = await mark(page)
    for (const share of [0.5, 0.3, -0.4]) {
        await autoscroll(page, share)
        await expect.poll(() => placement(page)).toEqual(where)
    }
    await settle(page)
    expect(await placement(page)).toEqual(where)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await since(page, 'selectionCleared', from)).toEqual([])
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
    // Without a selection the scroller is the engine's own business again, and a turn still turns.
    await send(page, { type: 'clearSelection' })
    await settle(page, 200)
    from = await mark(page)
    await drag(page, [330, 400], [40, 400])
    expect((await relocatedAfter(page, from)).page).toBe(2)
})

test('a selection reaching past the end of the page is cut there, where it started kept', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    const from = await mark(page)
    // Without a touch, as a handle dragged on a phone: from the third paragraph to one on a later page.
    const text = await selectIn(page, { selector: '#third', start: 5, end: 20, endSelector: 'body > p:nth-of-type(14)' })
    const selection = await waitFor(page, 'selection', from)
    expect(text.startsWith(selection.text)).toBe(true)
    expect(selection.text.length).toBeLessThan(text.length)
    const seen = await selectionOnPage(page)
    expect(seen.inside).toBe(true)
    expect(seen.text).toBe(selection.text)
    expect(seen.anchor).toEqual(['third', 5])
    expectBoundsNear(selection.bounds, await boundsOf(page, selection.cfi))
    expect(await since(page, 'relocated', from)).toEqual([])
})

/** Makes the engine's visible range of the page run from character `start` to `end` of the text of `#third`. */
const pageOf = (page: Page, start: number, end: number) => page.evaluate(([start, end]) => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const text = doc.querySelector('#third').firstChild
    const range = doc.createRange()
    range.setStart(text, start)
    range.setEnd(text, end)
    view.lastLocation = { ...view.lastLocation, range }
    return { text: text.data as string, visible: range.toString() as string }
}, [start, end] as const)

test('a word cut by the edge of the page stays whole in a selection cut there', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    await settle(page)
    // "By noon th|e market … every|thing except": the page begins and ends inside a word, as a hyphenated one does.
    const { text: third } = await pageOf(page, 10, 40)
    expect(third.slice(8, 11)).toBe('the')
    expect(third.slice(35, 45)).toBe('everything')
    let from = await mark(page)
    // Forwards, from inside the page to far past its end.
    await selectIn(page, { selector: '#third', start: 12, end: 20, endSelector: '#margin' })
    let selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(third.slice(12, 45))
    expect((await selectionOnPage(page)).anchor).toEqual(['third', 12])

    // Backwards, from past the end to before the start: both ends keep their words whole.
    await send(page, { type: 'clearSelection' })
    from = await mark(page)
    await page.evaluate(() => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const text = doc.querySelector('#third').firstChild
        doc.getSelection().setBaseAndExtent(text, 120, text, 2)
    })
    selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(third.slice(8, 45))

    // An edge between two words takes nothing of the next page.
    await send(page, { type: 'clearSelection' })
    await pageOf(page, 10, 34)
    from = await mark(page)
    await selectIn(page, { selector: '#third', start: 12, end: 20, endSelector: '#margin' })
    selection = await waitFor(page, 'selection', from)
    expect(selection.text).toBe(third.slice(12, 34))
})

// ---------------------------------------------------------------- highlights

test('a selection names the highlights it overlaps, and the one it is exactly', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'marks')
    const item = (id: string, cfi: string) => ({ id, cfi, color: YELLOW, style: 'highlight', note: false })
    const items = [
        item('a', await cfiOf(page, '#plain', 4, 40)),
        item('b', await cfiOf(page, '#plain', 30, 60)),
        item('c', await cfiOf(page, '#third', 0, 20)),
    ]
    await send(page, { type: 'highlights', items })
    await expect.poll(() => drawings(page)).toBe(3)

    let from = await mark(page)
    await selectIn(page, { selector: '#plain', start: 4, end: 40 })
    let selection = await waitFor(page, 'selection', from)
    expect(selection.exactId).toBe('a')
    expect([...selection.highlightIds].sort()).toEqual(['a', 'b'])

    from = await mark(page)
    await selectIn(page, { selector: '#plain', start: 35, end: 50 })
    selection = await waitFor(page, 'selection', from)
    expect(selection.exactId).toBeNull()
    expect([...selection.highlightIds].sort()).toEqual(['a', 'b'])

    // Touching the end of a highlight is not overlapping it.
    from = await mark(page)
    await selectIn(page, { selector: '#plain', start: 60, end: 70 })
    selection = await waitFor(page, 'selection', from)
    expect(selection.exactId).toBeNull()
    expect(selection.highlightIds).toEqual([])

    // At most 32 are named.
    const many = []
    for (let i = 0; i < 40; i++) many.push(item(`m${i}`, await cfiOf(page, '#plain', i * 5, i * 5 + 4)))
    await send(page, { type: 'highlights', items: many })
    await expect.poll(() => drawings(page)).toBe(40)
    from = await mark(page)
    await selectIn(page, { contents: '#plain' })
    selection = await waitFor(page, 'selection', from)
    expect(selection.highlightIds).toHaveLength(32)
    expect(new Set(selection.highlightIds).size).toBe(32)
})

// ---------------------------------------------------------------- fixed layout

test('nothing is selectable in a fixed-layout book', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    await openBook(page, 'fixed')
    await settle(page)
    const frame = await contentFrame(page)
    const box = await frame.locator('#caption').boundingBox()
    expect(box).not.toBeNull()
    const from = await mark(page)
    await doubleClick(page, box!.x + box!.width / 2, box!.y + box!.height / 2)
    await settle(page)
    const kinds = await Promise.all(page.frames().filter(f => f.url().startsWith('blob:'))
        .map(f => f.evaluate(() => document.getSelection()!.type)))
    expect(kinds).not.toContain('Range')
    expect(await since(page, 'selection', from)).toEqual([])
    expect(errors).toEqual([])
})
