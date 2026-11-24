import { expect, test, type Page } from '@playwright/test'
import {
    contentFrame, drag, hostAnswerAfter, mark, type Message, messages, openBook, relocatedAfter, sendAndRelocate, send, SETTINGS,
    settle, since, startReader, waitFor,
} from './harness'

const FIELDS = [
    'type', 'cfi', 'fraction', 'sectionIndex', 'sectionCount', 'page', 'pages',
    'tocId', 'tocLabel', 'atEnd', 'moved', 'userMove', 'bookmarkIds', 'byHost', 'byNarration',
]

// Turns pages until the end is reported, and returns every relocation on the way.
const readToTheEnd = async (page: Page, limit = 60): Promise<Message[]> => {
    const seen: Message[] = []
    for (let i = 0; i < limit; i++) {
        const relocated = await sendAndRelocate(page, { type: 'next' })
        seen.push(relocated)
        if (relocated.atEnd) return seen
    }
    throw new Error('The end of the book was never reported')
}

test('a relocation carries every field of the contract', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    expect(Object.keys(relocated).sort()).toEqual([...FIELDS].sort())
    expect(relocated.cfi).toMatch(/^epubcfi\(.+\)$/)
    // A single point, not a range: no comma-separated parts.
    expect(relocated.cfi).not.toContain(',')
    expect(relocated.fraction).toBeGreaterThanOrEqual(0)
    expect(relocated.fraction).toBeLessThanOrEqual(1)
    expect(relocated.sectionIndex).toBe(0)
    expect(relocated.sectionCount).toBe(3)
    expect(relocated.page).toBe(1)
    expect(relocated.pages).toBeGreaterThan(1)
    expect(relocated.tocId).toBe(0)
    expect(relocated.tocLabel).toBe('The Green Lighthouse')
    expect(relocated.atEnd).toBe(false)
    expect(relocated.moved).toBe(false)
})

test('moved is false after opening and true after next', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    expect(relocated.moved).toBe(false)
    await settle(page)
    expect((await since(page, 'relocated', 0)).every(m => m.moved === false)).toBe(true)
    const after = await sendAndRelocate(page, { type: 'next' })
    expect(after.moved).toBe(true)
    expect(after.page).toBe(2)
    expect(after.cfi).not.toBe(relocated.cfi)
})

test('a relocation says whether the user made it: a turn or a swipe does, a resize after them does not',
    async ({ page }) => {
        await startReader(page)
        const { relocated } = await openBook(page, 'basic')
        expect(relocated.userMove).toBe(false)
        const turned = await sendAndRelocate(page, { type: 'next' })
        expect(turned).toMatchObject({ moved: true, userMove: true })
        let from = await mark(page)
        await drag(page, [320, 500], [60, 510])
        expect(await relocatedAfter(page, from)).toMatchObject({ moved: true, userMove: true })
        await settle(page)

        // The window shrinks (a bar comes under the page): the engine lays the page out again, elsewhere.
        from = await mark(page)
        const size = page.viewportSize()!
        await page.setViewportSize({ width: size.width, height: size.height - 160 })
        const resized = await relocatedAfter(page, from)
        // Still moved since the book opened, but not by the user this time.
        expect(resized).toMatchObject({ moved: true, userMove: false })
    })

test('going to a reported position reports the same position', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'next' })
    const there = await sendAndRelocate(page, { type: 'next' })
    const away = await sendAndRelocate(page, { type: 'goTo', fraction: 0.9 })
    expect(away.cfi).not.toBe(there.cfi)
    const back = await sendAndRelocate(page, { type: 'goTo', cfi: there.cfi })
    expect(back.cfi).toBe(there.cfi)
    expect(back.page).toBe(there.page)
    expect(back.sectionIndex).toBe(there.sectionIndex)
})

test('a book reopened at a reported position shows that position', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    const there = await sendAndRelocate(page, { type: 'next' })
    const { relocated } = await openBook(page, 'basic', { cfi: there.cfi })
    expect(relocated.cfi).toBe(there.cfi)
    expect(relocated.sectionIndex).toBe(1)
    expect(relocated.moved).toBe(false)
})

test('an entry of the contents leads to its chapter', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'basic')
    const third = await sendAndRelocate(page, { type: 'goTo', href: opened.toc[3].href })
    expect(third.sectionIndex).toBe(2)
    expect(third.tocId).toBe(3)
    expect(third.moved).toBe(true)
    const reply = await sendAndRelocate(page, { type: 'goTo', href: opened.toc[4].href })
    expect(reply.sectionIndex).toBe(2)
    expect(reply.tocId).toBe(4)
    expect(reply.tocLabel).toBe('The reply')
    expect(reply.page).toBeGreaterThan(1)
})

test('a fraction of the book can be gone to', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const middle = await sendAndRelocate(page, { type: 'goTo', fraction: 0.5 })
    expect(middle.sectionIndex).toBe(1)
    expect(Math.abs(middle.fraction - 0.5)).toBeLessThan(0.15)
    const end = await sendAndRelocate(page, { type: 'goTo', fraction: 1 })
    expect(end.sectionIndex).toBe(2)
    expect(end.atEnd).toBe(true)
})

for (const cfi of ['epubcfi(/6/998!/4/2/1:0)', 'epubcfi(/6/4!/4/999/999/1:0)', 'not a position']) {
    test(`an unusable position (${cfi}) falls back to the fraction`, async ({ page }) => {
        await startReader(page)
        const { relocated } = await openBook(page, 'basic', { cfi, fraction: 0.8 })
        expect(relocated.sectionIndex).toBe(2)
        expect(Math.abs(relocated.fraction - 0.8)).toBeLessThan(0.15)
        expect(relocated.moved).toBe(false)
    })
}

test('with neither a position nor a fraction the book opens at its start', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic', { cfi: 'not a position', fraction: null })
    expect(relocated.sectionIndex).toBe(0)
    expect(relocated.page).toBe(1)
})

test('next reaches the end and stays there; the fraction ends at 1', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    const seen = await readToTheEnd(page)
    const fractions = [relocated, ...seen].map(m => m.fraction)
    expect(fractions).toEqual([...fractions].sort((a, b) => a - b))
    const end = seen[seen.length - 1]
    expect(end.sectionIndex).toBe(2)
    expect(end.page).toBe(end.pages)
    expect(end.atEnd).toBe(true)
    expect(end.fraction).toBe(1)
    expect(seen.slice(0, -1).every(m => m.atEnd === false)).toBe(true)
    const from = await mark(page)
    await send(page, { type: 'next' })
    await settle(page)
    // Only the place on screen, said again after the turn that could not happen: nobody's move.
    const again = await since(page, 'relocated', from)
    expect(again).toHaveLength(1)
    expect(again[0]).toEqual({ ...end, userMove: false })
    expect(await since(page, 'error', 0)).toEqual([])
})

test('next at the end says the reader went past it, each time, and only there', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const seen = await readToTheEnd(page)
    expect(await since(page, 'pastEnd', 0)).toEqual([])
    const from = await mark(page)
    await send(page, { type: 'next' })
    await settle(page)
    // The place on screen said again is nobody's move.
    expect((await messages(page)).slice(from))
        .toEqual([{ type: 'turning' }, { type: 'pastEnd' }, { ...seen.at(-1), userMove: false }])
    await send(page, { type: 'next' })
    await settle(page)
    expect(await since(page, 'pastEnd', from)).toHaveLength(2)
    // Back from the end, a turn forward is a page turned again.
    await sendAndRelocate(page, { type: 'prev' })
    const again = await mark(page)
    const end = await sendAndRelocate(page, { type: 'next' })
    expect(end.cfi).toBe(seen[seen.length - 1].cfi)
    expect(await since(page, 'pastEnd', again)).toEqual([])
})

test('the end is reported as a position before the reader is said to go past it', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const end = await sendAndRelocate(page, { type: 'goTo', fraction: 1 })
    expect(end.atEnd).toBe(true)
    await sendAndRelocate(page, { type: 'prev' })
    const from = await mark(page)
    // Two turns in a row: the second one comes before the first has been reported.
    await page.evaluate(() => {
        const { lirelle } = window as any
        lirelle.receive('{"type":"next"}')
        lirelle.receive('{"type":"next"}')
    })
    await waitFor(page, 'pastEnd', from)
    const types = (await messages(page)).slice(from).map(m => m.type)
    // Each turn is announced; the end, reported on the way, also answers the second one.
    expect(types).toEqual(['turning', 'turning', 'relocated', 'pastEnd'])
    expect((await since(page, 'relocated', from))[0].atEnd).toBe(true)
})

test('a book opened at its end says the reader went past it on the first turn forward', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic', { fraction: 1 })
    expect(relocated.atEnd).toBe(true)
    expect(relocated.moved).toBe(false)
    await settle(page)
    const from = await mark(page)
    await send(page, { type: 'next' })
    await waitFor(page, 'pastEnd', from)
})

test('next past the last plate of a fixed-layout book says so', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    const last = await sendAndRelocate(page, { type: 'next' })
    expect(await since(page, 'pastEnd', 0)).toEqual([])
    const from = await mark(page)
    await send(page, { type: 'next' })
    await settle(page)
    expect((await messages(page)).slice(from))
        .toEqual([{ type: 'turning' }, { type: 'pastEnd' }, { ...last, userMove: false }])
})

test('a note file outside the reading order has no end to go past', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'footnote')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/notes.xhtml' })
    const from = await mark(page)
    await send(page, { type: 'next' })
    await settle(page)
    expect(await since(page, 'pastEnd', from)).toEqual([])
})

test('prev at the start moves nothing, and says the place on screen again', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    await settle(page)
    const from = await mark(page)
    await send(page, { type: 'prev' })
    await settle(page)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
    const next = await sendAndRelocate(page, { type: 'next' })
    expect(next.page).toBe(2)
    const back = await sendAndRelocate(page, { type: 'prev' })
    expect(back.cfi).toBe(relocated.cfi)
})

test('prev crosses back into the previous chapter, at its last page', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    const back = await sendAndRelocate(page, { type: 'prev' })
    expect(back.sectionIndex).toBe(0)
    expect(back.page).toBe(back.pages)
})

test('a right-to-left book says so, and next still advances in reading order', async ({ page }) => {
    await startReader(page)
    const { opened, relocated } = await openBook(page, 'rtl')
    expect(opened.dir).toBe('rtl')
    expect(relocated.sectionIndex).toBe(0)
    const second = await sendAndRelocate(page, { type: 'next' })
    expect(second.page).toBe(2)
    expect(second.fraction).toBeGreaterThan(relocated.fraction)
    const seen = await readToTheEnd(page)
    expect(seen[seen.length - 1].sectionIndex).toBe(1)
    const back = await sendAndRelocate(page, { type: 'prev' })
    expect(back.fraction).toBeLessThan(1)
})

test('a fixed-layout book says so and has no pages of a section', async ({ page }) => {
    await startReader(page)
    const { opened, relocated } = await openBook(page, 'fixed')
    expect(opened.fixedLayout).toBe(true)
    expect(opened.toc.map((t: any) => t.label)).toEqual(['First plate', 'Second plate'])
    expect(relocated.page).toBeNull()
    expect(relocated.pages).toBeNull()
    expect(relocated.sectionIndex).toBe(0)
    expect(relocated.atEnd).toBe(false)
    expect(relocated.cfi).toMatch(/^epubcfi\(.+\)$/)
    const frame = await contentFrame(page)
    await expect(frame.locator('#caption')).toHaveText('The quay at low water')
    const second = await sendAndRelocate(page, { type: 'next' })
    expect(second.sectionIndex).toBe(1)
    expect(second.atEnd).toBe(true)
    expect(second.moved).toBe(true)
    expect(second.page).toBeNull()
    const back = await sendAndRelocate(page, { type: 'goTo', cfi: relocated.cfi })
    expect(back.sectionIndex).toBe(0)
})

test('a section that cannot be loaded is reported', async ({ page }) => {
    await startReader(page, { missing: ['basic/OEBPS/ch2.xhtml'] })
    await openBook(page, 'basic')
    const from = await mark(page)
    await send(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    await expect.poll(async () => (await since(page, 'error', from)).map(m => m.code)).toEqual(['section_failed'])
    // The move that failed is answered too, with the place on screen; then the reader is still usable.
    expect((await hostAnswerAfter(page, from)).sectionIndex).toBe(0)
    const third = await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })
    expect(third.sectionIndex).toBe(2)
})

test('turning the page into a section that cannot be loaded is reported, and the reader stays where it was', async ({ page }) => {
    await startReader(page, { missing: ['basic/OEBPS/ch2.xhtml'] })
    const { relocated } = await openBook(page, 'basic')
    let latest = relocated
    while (latest.page < latest.pages) latest = await sendAndRelocate(page, { type: 'next' })
    const from = await mark(page)
    await send(page, { type: 'next' })
    await expect.poll(async () => (await since(page, 'error', from)).map(m => m.code)).toEqual(['section_failed'])
    await settle(page)
    // The turn announced did not happen: the place on screen is said again, as nobody's move.
    expect(await since(page, 'turning', from)).toHaveLength(1)
    expect(await since(page, 'relocated', from)).toEqual([{ ...latest, userMove: false }])
    // Page turns still work.
    const back = await sendAndRelocate(page, { type: 'prev' })
    expect(back.sectionIndex).toBe(0)
    expect(back.page).toBe(latest.page - 1)
})

// ---------------------------------------------------------------- answers to the host

test('a goTo of the host to the place on screen is answered by exactly one relocation', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    expect(relocated.byHost).toBe(false)
    await settle(page)
    for (const target of [{ cfi: relocated.cfi }, { href: 'OEBPS/ch1.xhtml' }, { fraction: 0 }]) {
        const from = await mark(page)
        await send(page, { type: 'goTo', ...target })
        await settle(page)
        const answers = await since(page, 'relocated', from)
        expect(answers, JSON.stringify(target)).toHaveLength(1)
        expect(answers[0]).toMatchObject({ byHost: true, cfi: relocated.cfi, moved: false })
    }
})

test('a goTo of the host elsewhere is answered by one relocation by the host, where it led', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    for (const flow of ['paginated', 'scrolled'] as const) {
        if (flow === 'scrolled') {
            const from = await mark(page)
            await send(page, { type: 'settings', settings: { ...SETTINGS, flow } })
            await relocatedAfter(page, from)
        }
        for (const href of ['OEBPS/ch2.xhtml', 'OEBPS/ch3.xhtml#reply']) {
            const from = await mark(page)
            await send(page, { type: 'goTo', href })
            await hostAnswerAfter(page, from)
            await settle(page, 1200)
            const answers = (await since(page, 'relocated', from)).filter(m => m.byHost)
            expect(answers, `${flow} ${href}`).toHaveLength(1)
            const all = await since(page, 'relocated', from)
            expect(all[all.length - 1].sectionIndex).toBe(href.includes('ch2') ? 1 : 2)
            expect(answers[0].sectionIndex).toBe(href.includes('ch2') ? 1 : 2)
        }
    }
})

test('a turn by the user is no answer to the host', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    let from = await mark(page)
    await drag(page, [320, 500], [60, 510])
    const swiped = await relocatedAfter(page, from)
    expect(swiped.page).toBe(2)
    expect(swiped.byHost).toBe(false)
    from = await mark(page)
    const turned = await sendAndRelocate(page, { type: 'next' })
    expect(turned.byHost).toBe(false)
    expect((await since(page, 'relocated', from)).every(m => m.byHost === false)).toBe(true)
})

// ---------------------------------------------------------------- turning

test('next and prev say the page is turning before it is reported', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    for (const type of ['next', 'next', 'prev']) {
        const from = await mark(page)
        await send(page, { type })
        const relocated = await relocatedAfter(page, from)
        expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
    }
})

test('a move of the host is no turn of the page', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await settle(page)
    for (const target of [{ href: 'OEBPS/ch2.xhtml' }, { fraction: 0.9 }, { fraction: 0 }]) {
        const from = await mark(page)
        await send(page, { type: 'goTo', ...target })
        await relocatedAfter(page, from)
        expect(await since(page, 'turning', from)).toEqual([])
    }
})

test('a turn of a fixed-layout book is announced too', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    await settle(page)
    let from = await mark(page)
    let relocated = await sendAndRelocate(page, { type: 'next' })
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
    from = await mark(page)
    await drag(page, [60, 500], [320, 510])
    relocated = await relocatedAfter(page, from)
    expect(relocated.sectionIndex).toBe(0)
    expect((await messages(page)).slice(from)).toEqual([{ type: 'turning' }, relocated])
})

test('in scrolled layout nothing is said of a turn', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    await settle(page)
    const from = await mark(page)
    await sendAndRelocate(page, { type: 'next' })
    await sendAndRelocate(page, { type: 'prev' })
    await drag(page, [200, 640], [200, 300])
    await relocatedAfter(page, from)
    // Past the bottom of the chapter: the next one.
    await sendAndRelocate(page, { type: 'goTo', fraction: 0.32 })
    await drag(page, [200, 640], [200, 200])
    await settle(page)
    expect(await since(page, 'turning', from)).toEqual([])
})

test('in scrolled layout, prev at the top of the book does nothing and leaves the page working', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic', { settings: { flow: 'scrolled' } })
    await settle(page)
    const from = await mark(page)
    await send(page, { type: 'prev' })
    // A pull down at the very top, which asks for the same.
    await drag(page, [200, 300], [200, 640])
    await settle(page)
    expect(await since(page, 'error', from)).toEqual([])
    expect(await since(page, 'turning', from)).toEqual([])
    for (const m of await since(page, 'relocated', from)) expect(m.cfi).toBe(relocated.cfi)
    // Still alive: turns and moves of the host work.
    const next = await sendAndRelocate(page, { type: 'next' })
    expect(next.fraction).toBeGreaterThan(relocated.fraction)
    expect((await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch3.xhtml' })).sectionIndex).toBe(2)
    expect(await since(page, 'error', from)).toEqual([])
})

test('in scrolled layout, next at the bottom of a note file outside the reading order leaves the page working',
    async ({ page }) => {
        await startReader(page)
        await openBook(page, 'footnote', { settings: { flow: 'scrolled' } })
        await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/notes.xhtml#story' })
        const from = await mark(page)
        // Down to the bottom of the file, and once more.
        for (let i = 0; i < 12; i++) {
            await send(page, { type: 'next' })
            await settle(page, 250)
        }
        await settle(page)
        expect(await since(page, 'error', from)).toEqual([])
        expect(await since(page, 'pastEnd', from)).toEqual([])
        const back = await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
        expect(back.sectionIndex).toBe(0)
        expect(await since(page, 'error', from)).toEqual([])
    })

test('a goTo of the host that leads nowhere is answered all the same', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'basic')
    await settle(page)
    for (const target of [{}, { href: 'OEBPS/nowhere.xhtml' }, { cfi: 'epubcfi(/6/99!/4/2)' }, { fraction: 'half' }]) {
        const from = await mark(page)
        await send(page, { type: 'goTo', ...target })
        await settle(page)
        const answers = await since(page, 'relocated', from)
        expect(answers, JSON.stringify(target)).toHaveLength(1)
        expect(answers[0]).toMatchObject({ byHost: true, cfi: relocated.cfi })
    }
})

test('a turn by the user right after a move by the host leaves the host its answer', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'fixed')
    await settle(page)
    const from = await mark(page)
    // Both at once: the turn starts while the host's answer is still to come.
    await page.evaluate(cfi => {
        const w = window as any
        w.lirelle.receive(JSON.stringify({ type: 'goTo', cfi }))
        w.lirelle.receive(JSON.stringify({ type: 'next' }))
    }, relocated.cfi)
    await settle(page)
    const all = await since(page, 'relocated', from)
    expect(all.map(m => [m.byHost, m.sectionIndex])).toEqual([[true, 0], [false, 1]])
})

test('a load of a book closed meanwhile is dropped: no failure on the next book', async ({ page }) => {
    await startReader(page, {
        delays: { 'footnote/OEBPS/notes.xhtml': 1500 }, missing: ['footnote/OEBPS/notes.xhtml'],
    })
    await openBook(page, 'footnote')
    await settle(page)
    // A note of the footnote book loads its file: the other book opens first, and then the file fails.
    const frame = await contentFrame(page)
    const box = (await frame.locator('#ref1').boundingBox())!
    await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2)
    await page.waitForTimeout(200)
    const from = await mark(page)
    await openBook(page, 'basic')
    await page.waitForTimeout(2500)
    expect(await since(page, 'error', from)).toEqual([])
    expect((await since(page, 'relocated', from)).every(m => m.sectionIndex === 0)).toBe(true)
})

test('a section of a closed book that arrives late leaves no document behind', async ({ page }) => {
    test.setTimeout(60_000)
    await startReader(page, { delays: { 'footnote/OEBPS/notes.xhtml': 10_000 } })
    await openBook(page, 'footnote')
    await settle(page)
    await page.evaluate(() => {
        const w = window as any
        w.lirelleBlobs = new Map()
        const create = URL.createObjectURL.bind(URL)
        const revoke = URL.revokeObjectURL.bind(URL)
        URL.createObjectURL = (blob: Blob) => {
            const url = create(blob)
            w.lirelleBlobs.set(url, blob)
            return url
        }
        URL.revokeObjectURL = (url: string) => {
            w.lirelleBlobs.delete(url)
            revoke(url)
        }
    })
    // The move gives up after 8 s; the book is closed for another before the file comes.
    const from = await mark(page)
    await send(page, { type: 'goTo', href: 'OEBPS/notes.xhtml' })
    await send(page, { type: 'open', base: '/book/basic/', cfi: null, fraction: null, settings: SETTINGS })
    await waitFor(page, 'opened', from)
    await page.waitForTimeout(4000)
    const notes = () => page.evaluate(async () => {
        const texts = await Promise.all([...(window as any).lirelleBlobs.values()].map((b: Blob) => b.text()))
        return texts.filter(t => t.includes('<title>Notes</title>')).length
    })
    expect(await notes()).toBe(0)
    // The other book's own sections are still there.
    expect(await page.evaluate(() => (window as any).lirelleBlobs.size)).toBeGreaterThan(0)
})
