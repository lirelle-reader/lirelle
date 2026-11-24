import { expect, test, type Page } from '@playwright/test'
import {
    contentFrame, mark, type Message, messages, openBook, pixelsIn, send, sendAndRelocate, SETTINGS, settle,
    since, startReader, waitFor,
} from './harness'

const WIDTH = 390
const HEIGHT = 844
const SEARCH_TYPES = new Set(['searchResults', 'searchProgress', 'searchDone'])
// The sections of `accents`: a title page outside the contents, three chapters, and one missing from the contents.
const LABELS = [null, 'Le cahier du matin', 'Le quai', 'Le long registre', 'Le long registre']
const LONG = 3

type Result = { cfi: string, pre: string, match: string, post: string }
type Group = { sectionIndex: number, label: string | null, results: Result[] }
type Rect = { left: number, top: number, right: number, bottom: number }

// ---------------------------------------------------------------- helpers

/** The messages of one search, in the order they were posted. */
const ofSearch = (all: Message[], searchId: number) =>
    all.filter(m => SEARCH_TYPES.has(m.type) && m.searchId === searchId)

/** The end of a search, once it has been posted. */
const doneOf = async (page: Page, searchId: number, from: number): Promise<Message> => {
    const handle = await page.waitForFunction(([id, f]) => (window as any).lirelleMessages.slice(f)
        .find((m: any) => m.type === 'searchDone' && m.searchId === id), [searchId, from] as const)
    return await handle.jsonValue() as Message
}

/** Runs a search to its end and returns what it posted. */
const search = async (page: Page, searchId: number, query: string) => {
    const from = await mark(page)
    await send(page, { type: 'search', searchId, query })
    const done = await doneOf(page, searchId, from)
    const posted = ofSearch((await messages(page)).slice(from), searchId)
    const batches = posted.filter(m => m.type === 'searchResults')
    const groups = batches.flatMap(b => b.groups as Group[])
    return { from, done, posted, batches, groups, results: groups.flatMap(g => g.results) }
}

/** How many times a query occurs in each section, accents and case aside, counted from the text itself. */
const occurrences = (page: Page, query: string): Promise<Record<string, number>> => page.evaluate(async query => {
    const view = document.getElementById('view') as any
    const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
    const out: Record<string, number> = {}
    for (const [i, section] of view.book.sections.entries()) {
        const doc = await section.createDocument()
        const n = fold(doc.body.textContent).split(fold(query)).length - 1
        if (n) out[i] = n
    }
    return out
}, query)

/** The section each CFI points into. */
const sectionsOf = (page: Page, cfis: string[]): Promise<number[]> => page.evaluate(cfis => {
    const view = document.getElementById('view') as any
    return cfis.map(cfi => view.resolveCFI(cfi).index as number)
}, cfis)

/** The text of a CFI in the section on screen, and whether it starts and ends in the same node. */
const textOf = (page: Page, cfi: string) => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const range = view.resolveCFI(cfi).anchor(doc)
    return { text: range.toString() as string, oneNode: range.startContainer === range.endContainer }
}, cfi)

/** The rects of a CFI in the section on screen, in the coordinates of its document. */
const rectsOf = (page: Page, cfi: string): Promise<Rect[]> => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    return [...view.resolveCFI(cfi).anchor(doc).getClientRects()]
        .filter((r: DOMRect) => r.width > 0 && r.height > 0)
        .map((r: DOMRect) => ({ left: r.left, top: r.top, right: r.right, bottom: r.bottom }))
}, cfi)

/** Whether the range of a CFI lies on the page as drawn. */
const onThePage = (page: Page, cfi: string): Promise<boolean> => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    const rects = [...view.resolveCFI(cfi).anchor(doc).getClientRects()]
        .filter((r: DOMRect) => r.width > 0 && r.height > 0)
    return rects.length > 0 && rects.every((r: DOMRect) => frame.left + r.left >= 0 && frame.left + r.right <= innerWidth
        && frame.top + r.top >= 0 && frame.top + r.bottom <= innerHeight)
}, cfi)

/** The search drawings of the overlayer of the section on screen: outlines, and the current result. */
const drawn = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const content = view.renderer.getContents()[0]
    if (!content || !content.overlayer) return { outlines: 0, current: 0 }
    const svg = content.overlayer.element as SVGElement
    return {
        outlines: svg.querySelectorAll(':scope > [data-lirelle="search"]').length,
        current: svg.querySelectorAll(':scope > [data-lirelle="search-current"]').length,
    }
})

/** The rects of the current result's drawing, in the coordinates of the section's document. */
const currentRects = (page: Page): Promise<Rect[]> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const svg = view.renderer.getContents()[0].overlayer.element as SVGElement
    return [...svg.querySelectorAll('[data-lirelle="search-current-fill"] rect')].map(r => {
        const rect = r as SVGRectElement
        const left = rect.x.baseVal.value
        const top = rect.y.baseVal.value
        return { left, top, right: left + rect.width.baseVal.value, bottom: top + rect.height.baseVal.value }
    })
})

const colours = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const svg = view.renderer.getContents()[0].overlayer.element as SVGElement
    const outline = svg.querySelector(':scope > [data-lirelle="search"]')!
    const current = svg.querySelector(':scope > [data-lirelle="search-current"]')!
    return { outline: getComputedStyle(outline).stroke, current: getComputedStyle(current).fill }
})

/** Records when each message is posted, from now on. */
const stampMessages = (page: Page) => page.evaluate(() => {
    const w = window as any
    const post = w.lirelleHost.postMessage
    w.lirelleStamps = []
    w.lirelleHost.postMessage = (json: string) => {
        const message = JSON.parse(json)
        const results = Array.isArray(message.groups)
            ? message.groups.reduce((n: number, g: any) => n + g.results.length, 0) : undefined
        w.lirelleStamps.push({ type: message.type, searchId: message.searchId, fraction: message.fraction, results,
            at: performance.now() })
        post(json)
    }
})

type Stamp = { type: string, searchId?: number, fraction?: number, results?: number, at: number }

const stamps = (page: Page): Promise<Stamp[]> => page.evaluate(() => (window as any).lirelleStamps)

/** Sends a message and returns when, on the page's clock, it was sent. */
const sendStamped = (page: Page, message: Message): Promise<number> => page.evaluate(json => {
    const at = performance.now()
    ;(window as any).lirelle.receive(json)
    return at
}, JSON.stringify(message))

/** Makes chapters of `accents` slow to load, so that a search takes a while: all of them, or one. */
const slowChapters = (page: Page, ms: number, file = '*.xhtml') =>
    page.route(`**/book/accents/OEBPS/${file}`, async route => {
        await new Promise(resolve => setTimeout(resolve, ms))
        await route.fallback()
    })

/** The largest number of stamps in any one-second window. */
const busiestSecond = (seen: Stamp[]) =>
    Math.max(0, ...seen.map(s => seen.filter(t => t.at >= s.at && t.at < s.at + 1000).length))

/** Checks the batches of a search: at most 100 results each, a section at most once per batch, sections in order,
 * and a section split over two batches only as the last group of one and the first of the next. */
const expectWellBatched = (batches: Message[]) => {
    let previous = -1
    for (const batch of batches) {
        expect(Object.keys(batch).sort()).toEqual(['fraction', 'groups', 'searchId', 'type'])
        expect(batch.fraction).toBeGreaterThan(0)
        expect(batch.fraction).toBeLessThanOrEqual(1)
        const groups = batch.groups as Group[]
        expect(groups.length).toBeGreaterThan(0)
        const count = groups.reduce((n, g) => n + g.results.length, 0)
        expect(count).toBeGreaterThan(0)
        expect(count).toBeLessThanOrEqual(100)
        for (const [i, group] of groups.entries()) {
            expect(Object.keys(group).sort()).toEqual(['label', 'results', 'sectionIndex'])
            expect(group.results.length).toBeGreaterThan(0)
            // Only the first group of a batch may go on with the section the batch before ended with.
            if (i === 0) expect(group.sectionIndex).toBeGreaterThanOrEqual(previous)
            else expect(group.sectionIndex).toBeGreaterThan(previous)
            previous = group.sectionIndex
        }
    }
}

/** Selects characters [start, end) of the first text of an element of the section on screen. */
const selectIn = (page: Page, selector: string, start: number, end: number): Promise<string> =>
    page.evaluate(([selector, start, end]) => {
        const view = document.getElementById('view') as any
        const { doc } = view.renderer.getContents()[0]
        const range = doc.createRange()
        const text = doc.querySelector(selector).firstChild
        range.setStart(text, start)
        range.setEnd(text, end)
        const selection = doc.getSelection()
        selection.removeAllRanges()
        selection.addRange(range)
        return range.toString() as string
    }, [selector, start, end] as const)

const liveSelection = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const selection = doc.getSelection()
    return { type: selection.type as string, text: selection.rangeCount ? selection.getRangeAt(0).toString() : '' }
})

const pageErrors = (page: Page) => {
    const errors: string[] = []
    page.on('pageerror', e => errors.push(String(e.stack ?? e)))
    return errors
}

const near = (a: Rect[], b: Rect[]) => a.length === b.length && a.every((r, i) =>
    (['left', 'top', 'right', 'bottom'] as const).every(side => Math.abs(r[side] - b[i][side]) < 0.5))

// ---------------------------------------------------------------- results

test('results come grouped by section, at most 100 to a message, with the section and its label', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'accents')
    expect(opened.sectionLabels).toEqual(LABELS)
    const expected = await occurrences(page, 'brume')
    expect(expected[LONG]).toBeGreaterThan(300)

    const { done, batches, groups, results, posted } = await search(page, 7, 'brume')
    expect(posted[posted.length - 1]).toEqual({ type: 'searchDone', searchId: 7, total: results.length, truncated: false })
    expect(done.total).toBe(Object.values(expected).reduce((a, b) => a + b, 0))
    expectWellBatched(batches)
    expect(batches.length).toBeGreaterThanOrEqual(Math.ceil(done.total / 100))

    const counted: Record<string, number> = {}
    for (const group of groups) {
        expect(group.label).toBe(LABELS[group.sectionIndex])
        expect(new Set(await sectionsOf(page, group.results.map(r => r.cfi)))).toEqual(new Set([group.sectionIndex]))
        counted[group.sectionIndex] = (counted[group.sectionIndex] ?? 0) + group.results.length
    }
    expect(counted).toEqual(expected)

    for (const result of results) {
        expect(Object.keys(result).sort()).toEqual(['cfi', 'match', 'post', 'pre'])
        expect(result.cfi).toMatch(/^epubcfi\(.+\)$/)
        expect(result.match.toLowerCase()).toBe('brume')
        expect(result.pre.length).toBeLessThanOrEqual(200)
        expect(result.post.length).toBeLessThanOrEqual(200)
    }
    expect(new Set(results.map(r => r.cfi)).size).toBe(results.length)
    const fog = results.find(r => r.pre.endsWith('Ce matin-là, la '))
    expect(fog).toBeDefined()
    expect(fog!.post.startsWith(' cachait encore la digue')).toBe(true)
})

test('a book of many short sections is answered in few messages', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'leaves')
    await stampMessages(page)
    const { done, batches, groups, from } = await search(page, 2, 'brume')
    expect(done).toEqual({ type: 'searchDone', searchId: 2, total: 300, truncated: false })
    expectWellBatched(batches)
    expect(groups.map(g => g.sectionIndex)).toEqual([...Array(300).keys()])
    expect(groups.slice(0, 50).every(g => g.label === 'Liasse 1')).toBe(true)
    expect(groups[299].label).toBe('Liasse 6')
    await settle(page, 300)
    // Everything the page posted while it searched, whatever its type.
    expect((await messages(page)).slice(from).length).toBeLessThanOrEqual(64)
    expect(busiestSecond(await stamps(page))).toBeLessThanOrEqual(20)
})

test('every batch of results says how far the search has got', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'leaves')
    const { batches, posted } = await search(page, 3, 'brume')
    // The engine reports a section as searched before handing over its results: a batch is at least as far as its
    // last section.
    for (const batch of batches) {
        const last = Math.max(...(batch.groups as Group[]).map(g => g.sectionIndex))
        expect(batch.fraction).toBeGreaterThanOrEqual((last + 1) / 300 - 1e-9)
    }
    const fractions = posted.filter(m => m.type !== 'searchDone').map(m => m.fraction as number)
    expect(fractions).toEqual([...fractions].sort((a, b) => a - b))
    expect(fractions[fractions.length - 1]).toBe(1)
})

test('results and progress are sent at most four times a second, besides full batches', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await slowChapters(page, 200)
    await stampMessages(page)
    const from = await mark(page)
    const sent = await sendStamped(page, { type: 'search', searchId: 3, query: 'brume' })
    await doneOf(page, 3, from)
    const seen = (await stamps(page)).filter(s => s.searchId === 3)
    // The ticks of the clock: what is not a full batch, nor the rest sent just before the end.
    const ticks = seen.filter((s, i) => s.type === 'searchProgress'
        || (s.type === 'searchResults' && s.results! < 100 && seen[i + 1].type !== 'searchDone'))
    expect(ticks.length).toBeGreaterThanOrEqual(2)
    let at = sent
    for (const tick of ticks) {
        expect(tick.at - at).toBeGreaterThanOrEqual(245)
        at = tick.at
    }
})

test('progress is sent at most four times a second while nothing is found', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await slowChapters(page, 200)
    await stampMessages(page)
    const from = await mark(page)
    const sent = await sendStamped(page, { type: 'search', searchId: 3, query: 'xq' })
    await doneOf(page, 3, from)
    const seen = (await stamps(page)).filter(s => s.searchId === 3)
    const progress = seen.filter(s => s.type === 'searchProgress')
    const end = seen[seen.length - 1].at
    // Five chapters at 200 ms each: the search lasts over a second.
    expect(end - sent).toBeGreaterThan(1000)
    expect(progress.length).toBeGreaterThanOrEqual(2)
    expect(progress.length).toBeLessThanOrEqual(Math.floor((end - sent) / 250) + 1)
    let at = sent
    let fraction = 0
    for (const p of progress) {
        expect(p.at - at).toBeGreaterThanOrEqual(245)
        expect(p.fraction!).toBeGreaterThan(fraction)
        expect(p.fraction!).toBeLessThanOrEqual(1)
        at = p.at
        fraction = p.fraction!
    }
})

test('a search ignores accents and case: "eleve" finds the three spellings', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { done, groups, results } = await search(page, 1, 'eleve')
    expect(done).toEqual({ type: 'searchDone', searchId: 1, total: 3, truncated: false })
    expect(groups.map(g => g.sectionIndex)).toEqual([1])
    expect(results.map(r => r.match)).toEqual(['élève', 'Élève', 'eleve'])
    // The chapter on screen is the one they are in.
    for (const [i, result] of results.entries())
        expect((await textOf(page, result.cfi)).text).toBe(['élève', 'Élève', 'eleve'][i])
})

test('a match cut in two by inline markup is found', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { done, results } = await search(page, 1, 'gouvernail')
    expect(done.total).toBe(1)
    expect(results[0].match).toBe('gouvernail')
    expect(results[0].pre.endsWith('Le pilote tenait le ')).toBe(true)
    expect(await textOf(page, results[0].cfi)).toEqual({ text: 'gouvernail', oneNode: false })
})

test('a search without a usable id is dropped', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const from = await mark(page)
    for (const searchId of ['5', 6.5, -1, 2147483648, null, undefined])
        await send(page, { type: 'search', searchId, query: 'brume' })
    await settle(page, 1500)
    expect((await messages(page)).slice(from).filter(m => SEARCH_TYPES.has(m.type))).toEqual([])
    expect(await drawn(page)).toEqual({ outlines: 0, current: 0 })
    // The largest id there is, and a query of exactly two characters, make a search.
    const { done } = await search(page, 2147483647, 'xq')
    expect(done).toEqual({ type: 'searchDone', searchId: 2147483647, total: 0, truncated: false })
})

test('a refused query is answered with no result, and stops the search running', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    for (const [searchId, query] of [[1, 'é'], [2, '  b  '], [3, 'b'.repeat(201)], [4, 42], [5, '']] as const) {
        const from = await mark(page)
        await send(page, { type: 'search', searchId, query })
        expect(await doneOf(page, searchId, from)).toEqual({ type: 'searchDone', searchId, total: 0, truncated: false })
    }
    // Two hundred characters are still a query; white space at either end does not count.
    const { done } = await search(page, 6, ` ${'b'.repeat(200)} `)
    expect(done.total).toBe(0)

    await slowChapters(page, 300)
    let from = await mark(page)
    await send(page, { type: 'search', searchId: 7, query: 'brume' })
    await waitFor(page, 'searchResults', from)
    from = await mark(page)
    await send(page, { type: 'search', searchId: 8, query: 'x' })
    expect(await doneOf(page, 8, from)).toEqual({ type: 'searchDone', searchId: 8, total: 0, truncated: false })
    await settle(page, 1200)
    expect(ofSearch((await messages(page)).slice(from), 7)).toEqual([])
    await expect.poll(() => drawn(page)).toEqual({ outlines: 0, current: 0 })
})

test('a search on a common word stops at 1 000 results and never floods the channel', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { done, results, from, batches } = await search(page, 4, 'le')
    expect(done).toEqual({ type: 'searchDone', searchId: 4, total: 1000, truncated: true })
    expect(results).toHaveLength(1000)
    expectWellBatched(batches)
    await settle(page, 500)
    // Everything the page posted while it searched, whatever its type.
    expect((await messages(page)).slice(from).length).toBeLessThan(64)
})

// ---------------------------------------------------------------- stopping

test('a new search cancels the one running', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await slowChapters(page, 300)
    const from = await mark(page)
    await send(page, { type: 'search', searchId: 1, query: 'le' })
    await waitFor(page, 'searchResults', from)
    const second = await mark(page)
    await send(page, { type: 'search', searchId: 2, query: 'brume' })
    await doneOf(page, 2, second)
    await settle(page, 800)
    expect(ofSearch((await messages(page)).slice(second), 1)).toEqual([])
    expect(ofSearch((await messages(page)).slice(from), 1).filter(m => m.type === 'searchDone')).toEqual([])
    // Only the second search is outlined in the chapter on screen.
    const expected = await occurrences(page, 'brume')
    await expect.poll(async () => (await drawn(page)).outlines).toBe(expected[1])
})

test('cancelSearch stops the search and keeps the outlines', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const expected = await occurrences(page, 'brume')
    await slowChapters(page, 300)
    const from = await mark(page)
    await send(page, { type: 'search', searchId: 3, query: 'brume' })
    await page.waitForFunction(f => (window as any).lirelleMessages.slice(f)
        .some((m: any) => m.type === 'searchResults' && m.groups.some((g: any) => g.sectionIndex === 1)), from)
    const before = await mark(page)
    await send(page, { type: 'cancelSearch' })
    await settle(page, 1500)
    expect(ofSearch((await messages(page)).slice(before), 3)).toEqual([])
    expect(ofSearch((await messages(page)).slice(from), 3).filter(m => m.type === 'searchDone')).toEqual([])
    expect((await drawn(page)).outlines).toBe(expected[1])
})

// The title page and the first chapter are found at once; the second chapter then takes long to load (see
// `slowSecondChapter`), and their results wait for the clock.
const slowSecondChapter = (page: Page) => slowChapters(page, 1500, 'ch2.xhtml')

const searchWaitingOnTheSecondChapter = async (page: Page, searchId: number) => {
    const from = await mark(page)
    await send(page, { type: 'search', searchId, query: 'brume' })
    await settle(page, 600)
    expect(ofSearch((await messages(page)).slice(from), searchId)).toEqual([])
    return from
}

test('cancelSearch sends the results still waiting, then nothing more', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const expected = await occurrences(page, 'brume')
    await slowSecondChapter(page)
    const from = await searchWaitingOnTheSecondChapter(page, 4)
    await send(page, { type: 'cancelSearch' })
    await settle(page, 2500)
    const posted = ofSearch((await messages(page)).slice(from), 4)
    expect(posted.map(m => m.type)).toEqual(['searchResults'])
    expect((posted[0].groups as Group[]).map(g => [g.sectionIndex, g.results.length]))
        .toEqual([[0, expected[0]], [1, expected[1]]])
})

test('a new search, a clear or a new book drop the results still waiting', async ({ page }) => {
    test.setTimeout(60_000)
    await startReader(page)
    await openBook(page, 'accents')
    await slowSecondChapter(page)
    let from = await searchWaitingOnTheSecondChapter(page, 5)
    await send(page, { type: 'search', searchId: 6, query: 'gouvernail' })
    await doneOf(page, 6, from)
    await settle(page, 1500)
    expect(ofSearch((await messages(page)).slice(from), 5)).toEqual([])

    from = await searchWaitingOnTheSecondChapter(page, 7)
    await send(page, { type: 'clearSearch' })
    await settle(page, 2000)
    expect(ofSearch((await messages(page)).slice(from), 7)).toEqual([])

    from = await searchWaitingOnTheSecondChapter(page, 8)
    await openBook(page, 'accents')
    await settle(page, 1000)
    expect(ofSearch((await messages(page)).slice(from), 8)).toEqual([])
})

test('clearSearch removes the outlines and the current result', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { results } = await search(page, 1, 'brume')
    const expected = await occurrences(page, 'brume')
    await expect.poll(async () => (await drawn(page)).outlines).toBe(expected[1])
    // On the page already: the page does not move.
    await send(page, { type: 'showSearchResult', cfi: results[1].cfi })
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[1], current: 1 })
    const from = await mark(page)
    await send(page, { type: 'clearSearch' })
    await expect.poll(() => drawn(page)).toEqual({ outlines: 0, current: 0 })
    expect((await messages(page)).slice(from).filter(m => SEARCH_TYPES.has(m.type))).toEqual([])
})

test('opening a book stops the search and leaves nothing of it', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await slowChapters(page, 200)
    const from = await mark(page)
    await send(page, { type: 'search', searchId: 5, query: 'le' })
    await waitFor(page, 'searchResults', from)
    const { opened } = await openBook(page, 'accents')
    await settle(page, 1500)
    const all = await messages(page)
    const openedAt = all.findIndex((m, i) => i >= from && m.type === 'opened')
    expect(openedAt).toBeGreaterThan(0)
    expect(ofSearch(all.slice(openedAt), 5)).toEqual([])
    expect(opened.type).toBe('opened')
    expect(await drawn(page)).toEqual({ outlines: 0, current: 0 })
})

// ---------------------------------------------------------------- showing a result

test('showSearchResult goes there, marks it as the current result and selects nothing', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { results } = await search(page, 1, 'brume')
    const expected = await occurrences(page, 'brume')
    const inLong = results.filter(r => r.pre.includes('la couleur de la'))
    const target = inLong[400]
    const from = await mark(page)
    const relocated = await sendAndRelocate(page, { type: 'showSearchResult', cfi: target.cfi })
    expect(relocated.sectionIndex).toBe(LONG)
    expect(await onThePage(page, target.cfi)).toBe(true)
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[LONG], current: 1 })
    expect(near(await currentRects(page), await rectsOf(page, target.cfi))).toBe(true)
    expect(await since(page, 'selection', from)).toEqual([])
    // A move of the host, no turn of the page.
    expect(await since(page, 'turning', from)).toEqual([])
    expect((await liveSelection(page)).type).not.toBe('Range')

    // The next result, on the same page, replaces it as the current one; the page does not move.
    const next = inLong[401]
    expect(await onThePage(page, next.cfi)).toBe(true)
    const before = (await messages(page)).filter(m => m.type === 'relocated').at(-1)!
    let at = await mark(page)
    await send(page, { type: 'showSearchResult', cfi: next.cfi })
    await expect.poll(async () => near(await currentRects(page), await rectsOf(page, next.cfi))).toBe(true)
    expect((await drawn(page)).current).toBe(1)
    await settle(page)
    // The host hears where the page is, even though it did not move.
    const answers = await since(page, 'relocated', at)
    expect(answers).toHaveLength(1)
    expect(answers[0]).toMatchObject({ byHost: true, cfi: before.cfi })

    // One further on, on another page.
    const later = inLong[450]
    expect(await onThePage(page, later.cfi)).toBe(false)
    expect((await sendAndRelocate(page, { type: 'showSearchResult', cfi: later.cfi })).sectionIndex).toBe(LONG)
    expect(await onThePage(page, later.cfi)).toBe(true)
    await expect.poll(async () => near(await currentRects(page), await rectsOf(page, later.cfi))).toBe(true)
    expect((await drawn(page)).current).toBe(1)
    expect(await since(page, 'selection', from)).toEqual([])
})

test('showSearchResult is a move by the host: a live selection is kept', async ({ page }) => {
    await startReader(page)
    const { relocated: first } = await openBook(page, 'accents')
    expect(first.page).toBe(1)
    const { results } = await search(page, 1, 'brume')
    const fog = results.find(r => r.post.startsWith(' cachait encore'))!
    const late = results.find(r => r.post.startsWith(' revint sur le port'))!
    await settle(page)

    let from = await mark(page)
    const text = await selectIn(page, '#fog', 0, 20)
    const selection = await waitFor(page, 'selection', from)

    from = await mark(page)
    const there = await sendAndRelocate(page, { type: 'showSearchResult', cfi: late.cfi })
    expect(there.sectionIndex).toBe(1)
    expect(there.page).toBeGreaterThan(1)
    await waitFor(page, 'selectionCleared', from)
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })

    from = await mark(page)
    expect((await sendAndRelocate(page, { type: 'showSearchResult', cfi: fog.cfi })).page).toBe(1)
    expect(await waitFor(page, 'selection', from)).toEqual(selection)
    expect(await liveSelection(page)).toEqual({ type: 'Range', text })
})

test('outlines and the current result are drawn again when their chapter loads', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { results } = await search(page, 1, 'brume')
    const expected = await occurrences(page, 'brume')
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[1], current: 0 })
    const target = results.filter(r => r.pre.includes('la couleur de la'))[10]
    await sendAndRelocate(page, { type: 'showSearchResult', cfi: target.cfi })
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[LONG], current: 1 })

    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    await expect.poll(() => drawn(page)).toEqual({ outlines: 0, current: 0 })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[LONG], current: 1 })
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[1], current: 0 })
})

test('the outlines and the current result take the search colour of the settings', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { results } = await search(page, 1, 'brume')
    const expected = await occurrences(page, 'brume')
    // On the page already: the page does not move.
    await send(page, { type: 'showSearchResult', cfi: results[1].cfi })
    await expect.poll(() => drawn(page)).toEqual({ outlines: expected[1], current: 1 })
    expect(await colours(page)).toEqual({ outline: 'rgb(217, 119, 6)', current: 'rgb(217, 119, 6)' })
    await send(page, { type: 'settings', settings: { ...SETTINGS, searchColor: '#2563EB' } })
    await expect.poll(() => colours(page)).toEqual({ outline: 'rgb(37, 99, 235)', current: 'rgb(37, 99, 235)' })
    // A colour that is not one falls back to the default.
    await send(page, { type: 'settings', settings: { ...SETTINGS, searchColor: 'red' } })
    await expect.poll(() => colours(page)).toEqual({ outline: 'rgb(217, 119, 6)', current: 'rgb(217, 119, 6)' })
})

/** The first rect of a CFI in the section on screen, on the page, checked to be on it. */
const rectOnPage = (page: Page, cfi: string): Promise<Rect> => page.evaluate(cfi => {
    const view = document.getElementById('view') as any
    const { doc } = view.renderer.getContents()[0]
    const frame = doc.defaultView.frameElement.getBoundingClientRect()
    const r = [...view.resolveCFI(cfi).anchor(doc).getClientRects()]
        .find((r: DOMRect) => r.width > 0 && r.height > 0) as DOMRect
    return { left: frame.left + r.left, top: frame.top + r.top, right: frame.left + r.right, bottom: frame.top + r.bottom }
}, cfi)

const rgbOf = (hex: string) => {
    const n = parseInt(hex.slice(1), 16)
    return [n >> 16, (n >> 8) & 255, n & 255]
}

/** The share of the pixels inside a rect that have a colour, give or take a few units. */
const shareOf = async (page: Page, rect: Rect, rgb: number[]): Promise<number> => {
    const pixels = await pixelsIn(page, {
        x: Math.ceil(rect.left), y: Math.ceil(rect.top),
        width: Math.max(1, Math.floor(rect.right - rect.left) - 1),
        height: Math.max(1, Math.floor(rect.bottom - rect.top) - 1),
    })
    return pixels.filter(p => p.every((c, i) => Math.abs(c - rgb[i]) <= 4)).length / pixels.length
}

/** How the fill of the current result is drawn. */
const currentFill = (page: Page) => page.evaluate(() => {
    const view = document.getElementById('view') as any
    const svg = view.renderer.getContents()[0].overlayer.element as SVGElement
    const fill = svg.querySelector('[data-lirelle="search-current-fill"]')!
    const ring = svg.querySelector('[data-lirelle="search-current"] [data-lirelle="search-current-ring"]')!
    const outline = svg.querySelector(':scope > [data-lirelle="search"]')!
    const style = getComputedStyle(fill)
    return {
        opacity: Number(style.opacity), blend: style.mixBlendMode, fill: style.fill,
        ring: getComputedStyle(ring).stroke, outlineFill: getComputedStyle(outline).fill,
    }
})

for (const [theme, settings] of [
    ['black', { background: '#000000', foreground: '#c8c8c8', link: '#88aaff', dark: true, highlightBlend: 'normal' }],
    ['light', { background: '#ffffff', dark: false, highlightBlend: 'multiply' }],
] as const) {
    test(`the current result is filled clearly, the others only outlined (${theme} theme)`, async ({ page }) => {
        await startReader(page)
        await openBook(page, 'accents', { settings })
        const { results } = await search(page, 1, 'brume')
        const expected = await occurrences(page, 'brume')
        expect(await onThePage(page, results[0].cfi)).toBe(true)
        expect(await onThePage(page, results[1].cfi)).toBe(true)
        // On the page already: the page does not move.
        await send(page, { type: 'showSearchResult', cfi: results[1].cfi })
        await expect.poll(() => drawn(page)).toEqual({ outlines: expected[1], current: 1 })
        const seen = await currentFill(page)
        expect(seen.opacity).toBeGreaterThanOrEqual(0.45)
        expect(seen.opacity).toBeLessThanOrEqual(0.55)
        expect(seen.blend).toBe(settings.highlightBlend)
        expect(seen.fill).toBe('rgb(217, 119, 6)')
        // An outline around it as well, and none of the plain results is filled.
        expect(seen.ring).toBe('rgb(217, 119, 6)')
        expect(seen.outlineFill).toBe('none')

        // As drawn: the paper of the current result takes about half of the search colour, the plain one none.
        const paper = rgbOf(settings.background)
        const tinted = rgbOf(SETTINGS.searchColor).map((c, i) => settings.highlightBlend === 'multiply'
            ? Math.round(paper[i] * (1 - seen.opacity + seen.opacity * c / 255))
            : Math.round(paper[i] * (1 - seen.opacity) + c * seen.opacity))
        const current = await rectOnPage(page, results[1].cfi)
        const plain = await rectOnPage(page, results[0].cfi)
        expect(await shareOf(page, current, tinted)).toBeGreaterThan(0.3)
        expect(await shareOf(page, plain, tinted)).toBe(0)
        expect(await shareOf(page, plain, paper)).toBeGreaterThan(0.3)
    })
}

test('showSearchResult ignores a CFI that points nowhere', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    await openBook(page, 'accents')
    await search(page, 1, 'brume')
    const before = (await messages(page)).filter(m => m.type === 'relocated').at(-1)!
    const from = await mark(page)
    for (const cfi of ['epubcfi(/6/40!/4/2,/1:0,/1:5)', 'not a cfi', 42, null])
        await send(page, { type: 'showSearchResult', cfi })
    await settle(page)
    // Nothing moves, and each is answered all the same: the host counts its moves.
    const answers = await since(page, 'relocated', from)
    expect(answers).toHaveLength(4)
    expect(answers.every(m => m.byHost === true && m.cfi === before.cfi)).toBe(true)
    expect(await since(page, 'error', from)).toEqual([])
    expect((await drawn(page)).current).toBe(0)
    expect(errors).toEqual([])
})

// ---------------------------------------------------------------- fixed layout, the book itself

test('in a fixed-layout book results are found and shown, and nothing is drawn', async ({ page }) => {
    await startReader(page)
    const errors = pageErrors(page)
    const { relocated } = await openBook(page, 'fixed')
    expect(relocated.sectionIndex).toBe(0)
    const { done, groups, results } = await search(page, 1, 'quay')
    expect(done).toEqual({ type: 'searchDone', searchId: 1, total: 2, truncated: false })
    expect(groups.map(g => [g.sectionIndex, g.label])).toEqual([[0, 'First plate'], [1, 'Second plate']])
    const shown = await sendAndRelocate(page, { type: 'showSearchResult', cfi: results[1].cfi })
    expect(shown.sectionIndex).toBe(1)
    const overlayers = await page.evaluate(() => {
        const view = document.getElementById('view') as any
        return view.renderer.getContents().filter((c: any) => c.overlayer).length
    })
    expect(overlayers).toBe(0)
    await send(page, { type: 'clearSearch' })
    await settle(page, 300)
    expect(await since(page, 'error', 0)).toEqual([])
    expect(errors).toEqual([])
})

test('the book\'s own document is never changed by a search', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await settle(page)
    const frame = await contentFrame(page)
    const snapshot = () => frame.evaluate(() => ({
        elements: document.body.getElementsByTagName('*').length,
        html: document.body.innerHTML,
    }))
    const before = await snapshot()
    const { results } = await search(page, 1, 'brume')
    // On the page already: the page does not move.
    await send(page, { type: 'showSearchResult', cfi: results[1].cfi })
    await expect.poll(async () => (await drawn(page)).current).toBe(1)
    expect(await snapshot()).toEqual(before)
    await send(page, { type: 'clearSearch' })
    await expect.poll(() => drawn(page)).toEqual({ outlines: 0, current: 0 })
    expect(await snapshot()).toEqual(before)
})

// The time is the desktop's, not a phone's: it is written down in the report of the test, not judged.
test('a search of the whole book, long chapter included, ends within a few seconds', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    expect(await page.evaluate(() => [innerWidth, innerHeight])).toEqual([WIDTH, HEIGHT])
    await stampMessages(page)
    const from = await mark(page)
    const sent = await sendStamped(page, { type: 'search', searchId: 9, query: 'brume' })
    await doneOf(page, 9, from)
    const seen = (await stamps(page)).filter(s => s.searchId === 9)
    const whole = seen[seen.length - 1].at - sent
    // The long chapter alone, matched as the engine does it, its loading aside.
    const long = await page.evaluate(async index => {
        const view = document.getElementById('view') as any
        const { searchMatcher } = await import('/reader/foliate/search.js' as string)
        const { textWalker } = await import('/reader/foliate/text-walker.js' as string)
        const doc = await view.book.sections[index].createDocument()
        const started = performance.now()
        const found = Array.from(searchMatcher(textWalker, {})(doc, 'brume')).length
        return { ms: performance.now() - started, found, characters: doc.body.textContent.length }
    }, LONG)
    const report = `whole book: ${Math.round(whole)} ms; long chapter (${long.characters} characters, `
        + `${long.found} results): ${Math.round(long.ms)} ms`
    test.info().annotations.push({ type: 'timing', description: report })
    console.log(`search of accents for "brume": ${report}`)
    expect(long.characters).toBeGreaterThan(290_000)
    expect(whole).toBeLessThan(10_000)
})

test('a page can be turned while a search runs', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    await slowChapters(page, 300)
    const from = await mark(page)
    await send(page, { type: 'search', searchId: 1, query: 'brume' })
    await send(page, { type: 'next' })
    const turned = await waitFor(page, 'relocated', from)
    expect(turned.page).toBe(2)
    // The page turned before the search ended.
    expect(await since(page, 'searchDone', from)).toEqual([])
    await doneOf(page, 1, from)
})

test('showSearchResult twice on the same page is answered twice', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'accents')
    const { results } = await search(page, 1, 'brume')
    const inLong = results.filter(r => r.pre.includes('la couleur de la'))
    const first = await sendAndRelocate(page, { type: 'showSearchResult', cfi: inLong[400].cfi })
    expect(first.byHost).toBe(true)
    expect(await onThePage(page, inLong[401].cfi)).toBe(true)
    const from = await mark(page)
    await send(page, { type: 'showSearchResult', cfi: inLong[401].cfi })
    await send(page, { type: 'showSearchResult', cfi: inLong[400].cfi })
    await settle(page)
    const answers = await since(page, 'relocated', from)
    expect(answers).toHaveLength(2)
    expect(answers.every(m => m.byHost === true && m.cfi === first.cfi)).toBe(true)
})
