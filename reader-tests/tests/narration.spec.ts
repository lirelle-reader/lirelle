import { expect, test, type Page } from '@playwright/test'
import {
    mark, type Message, messages, openBook, relocatedAfter, send, sendAndRelocate, SETTINGS, settle, since, startReader,
    waitFor,
} from './harness'

// The read-along book: sentences in spans `s<section>-s<n>`, headings with no id, and in the first chapter an
// interlude of several pages that is not narrated, between s1-s18 and s1-s19.
const BOOK = 'readalong'
const FIRST = [...Array(30).keys()].map(n => `s1-s${n + 1}`)

const LIGHT = { background: '#ffffff', foreground: '#111111', link: '#1a55cc', dark: false }
const DARK = { background: '#12172b', foreground: '#e2ddd1', link: '#f2b544', dark: true }

type Look = {
    line: string, style: string, color: string, thickness: string, offset: string, background: string,
}

// ---------------------------------------------------------------- helpers

type MarkOptions = { paused?: boolean, follow?: boolean }

const markSentence = (page: Page, sectionIndex: number, id: string, more: MarkOptions = {}) =>
    send(page, { type: 'narrationMark', sectionIndex, id, paused: more.paused ?? false, follow: more.follow ?? false })

/** The ids of the elements of the section on screen that carry the narration's mark. */
const marked = (page: Page): Promise<string[]> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    return [...doc.querySelectorAll('.lirelle-narration')].map((el: Element) => el.id)
})

/** The section on screen. */
const sectionOnScreen = (page: Page): Promise<number> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    return view.renderer.getContents()[0].index as number
})

/** How an element of the section on screen is drawn. */
const lookOf = (page: Page, id: string): Promise<Look> => page.evaluate(id => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const style = doc.defaultView.getComputedStyle(doc.getElementById(id))
    return {
        line: style.textDecorationLine, style: style.textDecorationStyle, color: style.textDecorationColor,
        thickness: style.textDecorationThickness, offset: style.textUnderlineOffset,
        background: style.backgroundColor,
    }
}, id)

/** Whether part of an element of the section on screen is on the screen. */
const onScreen = (page: Page, id: string): Promise<boolean> => page.evaluate(id => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const el = doc.getElementById(id)
    if (!el) return false
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    return [...el.getClientRects()].some((r: DOMRect) => r.width > 0 && r.height > 0
        && frame.left + r.right > 0 && frame.left + r.left < innerWidth
        && frame.top + r.bottom > 0 && frame.top + r.top < innerHeight)
}, id)

/** The first of some ids whose element is on the screen, or null. */
const firstOnScreen = async (page: Page, ids: string[]): Promise<string | null> => {
    for (const id of ids) if (await onScreen(page, id)) return id
    return null
}

/** The ids whose element begins in the right half of the screen, whole on it. */
const inRightHalf = (page: Page, ids: string[]): Promise<string[]> => page.evaluate(ids => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    return ids.filter(id => {
        const rects = [...doc.getElementById(id).getClientRects()].filter((r: DOMRect) => r.width > 0)
        return rects.length > 0 && rects.every((r: DOMRect) => frame.left + r.left >= innerWidth / 2
            && frame.left + r.right <= innerWidth && frame.top + r.top >= 0 && frame.top + r.bottom <= innerHeight)
    })
}, ids)

/** The text selected in the section on screen. */
const selectedText = (page: Page): Promise<string> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    return String(view.renderer.getContents()[0].doc.getSelection())
})

/** Turns the spread forward until a sentence begins on its right page, and returns the ids there. */
const toSentenceOnRight = async (page: Page): Promise<string[]> => {
    for (let i = 0; i < 20; i++) {
        const right = await inRightHalf(page, FIRST)
        if (right.length > 0) return right
        await sendAndRelocate(page, { type: 'next' })
    }
    throw new Error('No sentence on a right page')
}

/** Selects characters [start, end) of the first text node of an element of the section on screen. */
const selectIn = (page: Page, selector: string, start: number, end: number) => page.evaluate(
    ([selector, start, end]) => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const text = doc.querySelector(selector).firstChild
        const range = doc.createRange()
        range.setStart(text, start)
        range.setEnd(text, end)
        const selection = doc.getSelection()
        selection.removeAllRanges()
        selection.addRange(range)
    }, [selector, start, end] as const)

/**
 * Puts an element with a narrated id at the end of the section on screen, out of the reading flow, as a book's
 * notes are: hidden, or shown after the text.
 */
const addNoteAtEnd = (page: Page, id: string, hidden: boolean) => page.evaluate(([id, hidden]) => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const ns = 'http://www.w3.org/1999/xhtml'
    const aside = doc.createElementNS(ns, 'aside')
    if (hidden) aside.setAttribute('style', 'display: none')
    const span = doc.createElementNS(ns, 'span')
    span.id = id
    span.textContent = 'A note, read aloud where its call is.'
    aside.append(span)
    doc.body.append(aside)
}, [id, hidden] as const)

/** [ids] with [extra] put after [after]. */
const withAfter = (ids: string[], after: string, extra: string) => {
    const at = ids.indexOf(after) + 1
    return [...ids.slice(0, at), extra, ...ids.slice(at)]
}

let requests = 0

const find = async (page: Page, sectionIndex: number, ids: string[], what: 'page' | 'selection', prefer?: string) => {
    const requestId = ++requests
    const from = await mark(page)
    await send(page, { type: 'narrationFind', requestId, sectionIndex, ids, what, prefer })
    const found = await waitFor(page, 'narrationFound', from)
    expect(found.requestId).toBe(requestId)
    expect(found.sectionIndex).toBe(sectionIndex)
    return found.id as string | null
}

/** The position of a sentence's start, and how far into the book it is. */
const positionOf = async (page: Page, sectionIndex: number, id: string) => {
    const requestId = ++requests
    const from = await mark(page)
    await send(page, { type: 'narrationCfi', requestId, sectionIndex, id })
    const answer = await waitFor(page, 'narrationCfiOf', from)
    expect(answer.requestId).toBe(requestId)
    return { cfi: answer.cfi as string | null, fraction: answer.fraction as number | null }
}

const cfiOf = async (page: Page, sectionIndex: number, id: string) => (await positionOf(page, sectionIndex, id)).cfi

/** Where each section starts in the book, as relocations count it, and where the last one ends. */
const sectionFractions = (page: Page): Promise<number[]> =>
    page.evaluate(() => (document.getElementById('view') as any).getSectionFractions())

/** Turns the page forward until an element is no longer on the screen. */
const turnPast = async (page: Page, id: string): Promise<Message> => {
    for (let i = 0; i < 20; i++) {
        const relocated = await sendAndRelocate(page, { type: 'next' })
        if (!await onScreen(page, id)) return relocated
    }
    throw new Error(`${id} never left the screen`)
}

const pageErrors = (page: Page) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(String(e)))
    return errors
}

// ---------------------------------------------------------------- the underline

test('the sentence heard is underlined in the accent on a light tint, dotted with no tint while paused',
    async ({ page }) => {
        const errors = pageErrors(page)
        await startReader(page)
        await openBook(page, BOOK, { settings: LIGHT })
        await markSentence(page, 0, 's1-s2')
        await expect.poll(() => marked(page)).toEqual(['s1-s2'])
        expect(await lookOf(page, 's1-s2')).toEqual({
            line: 'underline', style: 'solid', color: 'rgb(26, 85, 204)', thickness: '2px', offset: '5px',
            background: 'rgba(26, 85, 204, 0.07)',
        })
        // Not the highlights' fills: an underline in the theme's accent.
        expect(await lookOf(page, 's1-s1')).toMatchObject({ line: 'none', background: 'rgba(0, 0, 0, 0)' })

        await markSentence(page, 0, 's1-s2', { paused: true })
        await expect.poll(async () => (await lookOf(page, 's1-s2')).style).toBe('dotted')
        expect(await lookOf(page, 's1-s2')).toMatchObject({
            line: 'underline', color: 'rgb(26, 85, 204)', background: 'rgba(0, 0, 0, 0)',
        })

        await markSentence(page, 0, 's1-s3')
        await expect.poll(() => marked(page)).toEqual(['s1-s3'])
        expect(await lookOf(page, 's1-s3')).toMatchObject({ style: 'solid', background: 'rgba(26, 85, 204, 0.07)' })
        expect(await lookOf(page, 's1-s2')).toMatchObject({ line: 'none' })
        expect(errors).toEqual([])
    })

test('on a dark page the underline keeps its tint, and a theme change recolours it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK, { settings: DARK })
    await markSentence(page, 0, 's1-s4')
    await expect.poll(() => marked(page)).toEqual(['s1-s4'])
    expect(await lookOf(page, 's1-s4')).toEqual({
        line: 'underline', style: 'solid', color: 'rgb(242, 181, 68)', thickness: '2px', offset: '5px',
        background: 'rgba(242, 181, 68, 0.07)',
    })
    await markSentence(page, 0, 's1-s4', { paused: true })
    await expect.poll(async () => (await lookOf(page, 's1-s4')).style).toBe('dotted')
    expect((await lookOf(page, 's1-s4')).background).toBe('rgba(0, 0, 0, 0)')

    await send(page, { type: 'settings', settings: { ...SETTINGS, ...LIGHT } })
    await expect.poll(async () => (await lookOf(page, 's1-s4')).color).toBe('rgb(26, 85, 204)')
})

test('clearing takes the underline away', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    await markSentence(page, 0, 's1-s5')
    await expect.poll(() => marked(page)).toEqual(['s1-s5'])
    await send(page, { type: 'narrationClear' })
    await expect.poll(() => marked(page)).toEqual([])
    expect((await lookOf(page, 's1-s5')).line).toBe('none')
})

test('a sentence of a section not shown is underlined once the section loads, and nothing moves', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const from = await mark(page)
    await markSentence(page, 1, 's2-s3')
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await sectionOnScreen(page)).toBe(0)

    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    await expect.poll(() => marked(page)).toEqual(['s2-s3'])
})

test('an unknown sentence is reported missing and underlines nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    await markSentence(page, 0, 's1-s2')
    await expect.poll(() => marked(page)).toEqual(['s1-s2'])
    const from = await mark(page)
    await markSentence(page, 0, 'nowhere', { follow: true })
    const missing = await waitFor(page, 'narrationMissing', from)
    expect(missing).toEqual({ type: 'narrationMissing', sectionIndex: 0, id: 'nowhere' })
    expect(await marked(page)).toEqual([])
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await positionOf(page, 0, 'nowhere')).toEqual({ cfi: null, fraction: null })
    expect(await find(page, 0, ['nowhere'], 'page')).toBeNull()
})

// ---------------------------------------------------------------- following

test('following turns to the sentence, into the next section too, and says the move was the narration\'s',
    async ({ page }) => {
        await startReader(page)
        await openBook(page, BOOK)
        let from = await mark(page)
        await markSentence(page, 0, 's1-s1', { follow: true })
        await expect.poll(() => marked(page)).toEqual(['s1-s1'])
        await settle(page)
        // Already on the page: nothing moves.
        expect(await since(page, 'relocated', from)).toEqual([])

        expect(await onScreen(page, 's1-s20')).toBe(false)
        await selectIn(page, '#s1-s2', 4, 20)
        const selected = await selectedText(page)
        from = await mark(page)
        await markSentence(page, 0, 's1-s20', { follow: true })
        const turned = await relocatedAfter(page, from)
        expect(turned).toMatchObject({ sectionIndex: 0, byNarration: true, byHost: false, moved: true })
        expect(await onScreen(page, 's1-s20')).toBe(true)
        expect(await marked(page)).toEqual(['s1-s20'])
        // Not a turn by the user: none is announced, and the selection is kept.
        expect(await since(page, 'turning', from)).toEqual([])
        expect(await selectedText(page)).toBe(selected)

        from = await mark(page)
        await markSentence(page, 1, 's2-s2', { follow: true })
        const next = await relocatedAfter(page, from)
        expect(next).toMatchObject({ sectionIndex: 1, byNarration: true, byHost: false })
        await expect.poll(() => marked(page)).toEqual(['s2-s2'])
        expect(await onScreen(page, 's2-s2')).toBe(true)

        // A turn by the user is not the narration's.
        const user = await sendAndRelocate(page, { type: 'next' })
        expect(user.byNarration).toBe(false)
    })

test('the sentence followed to is reported visible after the relocation that brought it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    let from = await mark(page)
    await markSentence(page, 0, 's1-s20')
    expect((await waitFor(page, 'narrationVisible', from)).visible).toBe(false)

    from = await mark(page)
    await markSentence(page, 0, 's1-s20', { follow: true })
    await waitFor(page, 'narrationVisible', from)
    await settle(page)
    const order = (await messages(page)).slice(from)
        .filter(m => m.type === 'relocated' || m.type === 'narrationVisible')
        .map(m => m.type === 'relocated' ? `relocated:${m.byNarration}` : `visible:${m.visible}`)
    expect(order).toEqual(['relocated:true', 'visible:true'])
})

test('the page is not moved from under a finger on it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 400 }] })
    const from = await mark(page)
    await markSentence(page, 0, 's1-s20', { follow: true })
    await expect.poll(() => marked(page)).toEqual(['s1-s20'])
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await onScreen(page, 's1-s20')).toBe(false)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await cdp.detach()
})

/** Whether a follow mark of a sentence further on moves the page to it. */
const followMoves = async (page: Page, id: string) => {
    const from = await mark(page)
    await markSentence(page, 0, id, { follow: true })
    await relocatedAfter(page, from)
    return onScreen(page, id)
}

test('a touch cancelled (the system took it, as a shade pulled down) no longer holds the page', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 400 }] })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
    await cdp.detach()

    expect(await followMoves(page, 's1-s20')).toBe(true)
})

test('a touch left on the page when it is hidden no longer holds it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 400 }] })
    // The app goes to the background with the finger's end never told.
    await page.evaluate(() => {
        Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
        document.dispatchEvent(new Event('visibilitychange'))
        Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
        document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(await followMoves(page, 's1-s20')).toBe(true)
    await cdp.detach()
})

test('a touch that has neither moved nor ended for a while no longer holds the page', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 200, y: 400 }] })
    // Past the hold of a touch nothing more is heard of (a few seconds).
    await page.waitForTimeout(3_500)

    expect(await followMoves(page, 's1-s20')).toBe(true)
    await cdp.detach()
})

test('without following, the mark moves nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const from = await mark(page)
    await markSentence(page, 0, 's1-s25')
    await expect.poll(() => marked(page)).toEqual(['s1-s25'])
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
    expect(await onScreen(page, 's1-s25')).toBe(false)
})

test('following in scrolled layout brings the sentence on screen', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK, { settings: { flow: 'scrolled' } })
    expect(await onScreen(page, 's1-s25')).toBe(false)
    const from = await mark(page)
    await markSentence(page, 0, 's1-s25', { follow: true })
    const moved = await relocatedAfter(page, from)
    expect(moved.byNarration).toBe(true)
    expect(await onScreen(page, 's1-s25')).toBe(true)
    expect((await lookOf(page, 's1-s25')).line).toBe('underline')
})

test('a two-page spread shows both pages: a sentence on the right one is not followed to', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await startReader(page)
    await openBook(page, BOOK, { settings: { maxColumns: 2 } })
    const right = await toSentenceOnRight(page)
    const from = await mark(page)
    await markSentence(page, 0, right[0], { follow: true })
    const visible = await waitFor(page, 'narrationVisible', from)
    expect(visible.visible).toBe(true)
    await settle(page)
    expect(await since(page, 'relocated', from)).toEqual([])
})

/** The ids whose element begins on screen but past the right edge of the paginator's visible container. */
const pastTheContainer = (page: Page, ids: string[]): Promise<string[]> => page.evaluate(ids => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement
    let container = frame.parentElement
    while (container && container.id !== 'container') {
        container = container.parentElement || (container.getRootNode() as any).host
    }
    const right = container.getBoundingClientRect().right
    const left = frame.getBoundingClientRect().left
    return ids.filter(id => {
        const xs = [...doc.getElementById(id).getClientRects()].filter((r: DOMRect) => r.width > 0)
            .map((r: DOMRect) => left + r.left)
        return xs.length > 0 && xs.every(x => x >= right && x < innerWidth)
    })
}, ids)

test('a wide window with margins: a sentence of the next spread, behind the margin, is not on the page (nor a start)', async ({ page }) => {
    // As a tablet in landscape: two columns of at most their width each, the window wider than the two.
    await page.setViewportSize({ width: 1600, height: 877 })
    await startReader(page)
    await openBook(page, BOOK, { settings: { maxColumns: 2 } })
    let hidden: string[] = []
    for (let i = 0; i < 20 && hidden.length === 0; i++) {
        hidden = await pastTheContainer(page, FIRST)
        if (hidden.length === 0) await sendAndRelocate(page, { type: 'next' })
    }
    expect(hidden).not.toHaveLength(0)
    let from = await mark(page)
    await markSentence(page, 0, hidden[0])
    expect((await waitFor(page, 'narrationVisible', from)).visible).toBe(false)

    from = await mark(page)
    await markSentence(page, 0, hidden[0], { follow: true })
    const moved = await relocatedAfter(page, from)
    expect(moved.byNarration).toBe(true)
})

// ---------------------------------------------------------------- visibility

test('turning away from the sentence and back reports its visibility, once per change', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    let from = await mark(page)
    await markSentence(page, 0, 's1-s2')
    expect((await waitFor(page, 'narrationVisible', from)).visible).toBe(true)

    from = await mark(page)
    await turnPast(page, 's1-s2')
    await sendAndRelocate(page, { type: 'next' })
    await settle(page)
    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([false])

    from = await mark(page)
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    await settle(page)
    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([true])

    // The same sentence again, or the page laid out again, says nothing new; the next sentence on the page is told.
    from = await mark(page)
    await markSentence(page, 0, 's1-s2', { paused: true })
    await page.setViewportSize({ width: 1000, height: 700 })
    await settle(page)
    expect(await since(page, 'narrationVisible', from)).toEqual([])
    await markSentence(page, 0, 's1-s3')
    await settle(page)
    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([true])
})

test('a page turned ahead of the narration reports the sentence visible once the narration reaches it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    let from = await mark(page)
    await markSentence(page, 0, 's1-s1')
    expect((await waitFor(page, 'narrationVisible', from)).visible).toBe(true)

    // The user turns ahead; the narration goes on, sentence by sentence, without following.
    from = await mark(page)
    await turnPast(page, 's1-s1')
    await settle(page)
    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([false])
    const ahead = await firstOnScreen(page, FIRST)
    expect(ahead).not.toBeNull()
    from = await mark(page)
    for (const id of FIRST.slice(1, FIRST.indexOf(ahead!) + 1)) await markSentence(page, 0, id)
    await settle(page)

    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([true])
    expect(await since(page, 'relocated', from)).toEqual([])
})

/** The first of some ids whose element is on the screen and goes on past its right edge, or null. */
const goingOnPastPage = (page: Page, ids: string[]): Promise<string | null> => page.evaluate(ids => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    return ids.find(id => {
        const el = doc.getElementById(id)
        const rects = el ? [...el.getClientRects()].filter((r: DOMRect) => r.width > 0 && r.height > 0) : []
        const on = rects.some((r: DOMRect) => frame.left + r.left >= 0 && frame.left + r.right <= innerWidth
            && r.width > 2)
        return on && rects.some((r: DOMRect) => frame.left + r.left >= innerWidth)
    }) ?? null
}, ids)

test('turned onto the rest of the sentence heard, the next sentence on the page is reported visible', async ({ page }) => {
    // Short pages: a sentence cut by a page break is soon found.
    await page.setViewportSize({ width: 360, height: 260 })
    await startReader(page)
    await openBook(page, BOOK)
    let across: string | null = null
    for (let i = 0; i < 20 && !across; i++) {
        across = await goingOnPastPage(page, FIRST)
        if (!across) await sendAndRelocate(page, { type: 'next' })
    }
    expect(across).not.toBeNull()
    let from = await mark(page)
    await markSentence(page, 0, across!)
    expect((await waitFor(page, 'narrationVisible', from)).visible).toBe(true)

    // Turned onto its rest it is still on the page: nothing changed; the next sentence heard there is told.
    from = await mark(page)
    await sendAndRelocate(page, { type: 'next' })
    await settle(page)
    expect(await onScreen(page, across!)).toBe(true)
    expect(await since(page, 'narrationVisible', from)).toEqual([])
    const next = FIRST[FIRST.indexOf(across!) + 1]
    expect(await onScreen(page, next)).toBe(true)
    await markSentence(page, 0, next)
    await settle(page)

    expect((await since(page, 'narrationVisible', from)).map(m => m.visible)).toEqual([true])
    expect(await since(page, 'relocated', from)).toHaveLength(1)
})

// ---------------------------------------------------------------- finding

test('the page prefers the sentence last heard when it shows, else starts from its own first one', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const first = await firstOnScreen(page, FIRST)
    const later = FIRST[FIRST.indexOf(first!) + 1]
    expect(await onScreen(page, later)).toBe(true)

    expect(await find(page, 0, FIRST, 'page', later)).toBe(later)

    const far = FIRST[FIRST.length - 1]
    expect(await onScreen(page, far)).toBe(false)
    expect(await find(page, 0, FIRST, 'page', far)).toBe(first)
    expect(await find(page, 0, FIRST, 'page', 'not-narrated')).toBe(first)
})

test('the sentence to start from on the page: the first on it, or the first after it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    expect(await find(page, 0, FIRST, 'page')).toBe('s1-s1')

    await sendAndRelocate(page, { type: 'next' })
    const first = await firstOnScreen(page, FIRST)
    expect(first, 'a sentence on the second page').not.toBeNull()
    expect(first).not.toBe('s1-s1')
    expect(await find(page, 0, FIRST, 'page')).toBe(first)

    // A page of the interlude, with no sentence of the narration on it.
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml#interlude' })
    await sendAndRelocate(page, { type: 'next' })
    expect(await firstOnScreen(page, FIRST)).toBeNull()
    expect(await find(page, 0, FIRST, 'page')).toBe('s1-s19')
    // A later section has all its sentences after the page; an earlier one none.
    expect(await find(page, 1, ['s2-s1', 's2-s2'], 'page')).toBe('s2-s1')
})

test('the sentence to start from on a spread counts the right page', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    await startReader(page)
    await openBook(page, BOOK, { settings: { maxColumns: 2 } })
    const right = await toSentenceOnRight(page)
    // The left page holds the interlude alone: the first sentence shown is on the right one.
    expect(await firstOnScreen(page, FIRST)).toBe(right[0])
    expect(await find(page, 0, FIRST, 'page')).toBe(right[0])
})

test('the sentence to start from at a selection: the one holding it, or the next one', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    await selectIn(page, '#s1-s3', 10, 20)
    expect(await find(page, 0, FIRST, 'selection')).toBe('s1-s3')

    // A heading that is not narrated.
    await selectIn(page, 'h1', 0, 5)
    expect(await find(page, 0, FIRST, 'selection')).toBe('s1-s1')

    await send(page, { type: 'clearSelection' })
    expect(await find(page, 0, FIRST, 'selection')).toBeNull()
})

test('a narrated id with no element in its section is never where to start from', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    // As in a real book: the map lists a sentence the text does not have (a note's call between two sentences).
    const ids = withAfter(FIRST, 's1-s1', 's1-s1b')
    expect(await find(page, 0, ids, 'page')).toBe('s1-s1')
    await sendAndRelocate(page, { type: 'next' })
    const first = (await firstOnScreen(page, FIRST))!
    expect(first).not.toBe('s1-s1')

    expect(await find(page, 0, ids, 'page')).toBe(first)
    await selectIn(page, `#${first}`, 2, 6)
    expect(await find(page, 0, ids, 'selection')).toBe(first)
    // A section not shown is read to know it: its first sentence that is there.
    expect(await find(page, 1, ['s2-missing', 's2-s1', 's2-s2'], 'page')).toBe('s2-s1')
})

for (const hidden of [true, false]) {
    test(`a narrated id out of the reading flow (a note ${hidden ? 'hidden' : 'after the text'}) is never where to start from`,
        async ({ page }) => {
            await startReader(page)
            await openBook(page, BOOK)
            await addNoteAtEnd(page, 's1-n1', hidden)
            const ids = withAfter(FIRST, 's1-s1', 's1-n1')
            await sendAndRelocate(page, { type: 'next' })
            const first = (await firstOnScreen(page, FIRST))!

            expect(await find(page, 0, ids, 'page')).toBe(first)
            await selectIn(page, `#${first}`, 2, 6)
            expect(await find(page, 0, ids, 'selection')).toBe(first)

            // A page with no sentence on it starts at the next one in the text, not at the note.
            await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml#interlude' })
            await sendAndRelocate(page, { type: 'next' })
            expect(await find(page, 0, ids, 'page')).toBe('s1-s19')
        })
}

// ---------------------------------------------------------------- positions

test('the position of a sentence leads back to it', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const cfi = await cfiOf(page, 0, 's1-s20')
    expect(cfi).toMatch(/^epubcfi\(.+\)$/)
    expect(await onScreen(page, 's1-s20')).toBe(false)
    await sendAndRelocate(page, { type: 'goTo', cfi })
    expect(await onScreen(page, 's1-s20')).toBe(true)

    // A sentence of a section that is not loaded.
    const later = await cfiOf(page, 1, 's2-s9')
    expect(later).toMatch(/^epubcfi\(.+\)$/)
    const relocated = await sendAndRelocate(page, { type: 'goTo', cfi: later })
    expect(relocated.sectionIndex).toBe(1)
    expect(await onScreen(page, 's2-s9')).toBe(true)
})

test('the position of a sentence says how far into the book it is, as a relocation there does', async ({ page }) => {
    await startReader(page)
    await openBook(page, BOOK)
    const fractions = await sectionFractions(page)
    const early = await positionOf(page, 0, 's1-s2')
    const later = await positionOf(page, 1, 's2-s9')

    expect(early.fraction!).toBeGreaterThanOrEqual(fractions[0] - 1e-9)
    expect(early.fraction!).toBeLessThan(fractions[1])
    expect(later.fraction!).toBeGreaterThan(fractions[1])
    expect(later.fraction!).toBeLessThan(fractions[2])
    // A relocation counts to the end of the page shown: within a page of the sentence's start.
    const relocated = await sendAndRelocate(page, { type: 'goTo', cfi: later.cfi })
    expect(relocated.sectionIndex).toBe(1)
    const tolerance = (fractions[2] - fractions[1]) / 2
    expect(Math.abs((relocated.fraction as number) - later.fraction!)).toBeLessThan(tolerance)
})
