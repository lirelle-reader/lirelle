import { expect, test, type Page } from '@playwright/test'
import {
    contentFrame, type Format, mark, type Message, messages, openBook, send, sendAndRelocate, SETTINGS, settle,
    startReader, waitFor,
} from './harness'

// The books of books/out that are one file to the page (books/convert.py): the `basic` book as an old Mobipocket
// file, as a KF8 file and as a FictionBook.
type Book = { name: string, format: Format, label: string }

const BOOKS: Book[] = [
    { name: 'basic.mobi', format: 'mobi', label: 'a MOBI' },
    { name: 'basic.azw3', format: 'mobi', label: 'an AZW3' },
    { name: 'basic.fb2', format: 'fb2', label: 'an FB2' },
]

const CHAPTERS = ['The Green Lighthouse', 'The Missing Gull', 'The Morning Boat']

/** The text of the section on screen. */
const textOnScreen = async (page: Page): Promise<string> =>
    (await (await contentFrame(page)).locator('body').innerText()).replace(/\s+/g, ' ')

/** The words at the start of what the page shows. */
const firstWords = (page: Page): Promise<string> => page.evaluate(() => {
    const view = document.getElementById('view') as any
    return String(view.lastLocation.range).replace(/\s+/g, ' ').trim().slice(0, 40)
})

/** Runs a search to its end and returns its results. */
const search = async (page: Page, searchId: number, query: string) => {
    const from = await mark(page)
    await send(page, { type: 'search', searchId, query })
    await page.waitForFunction(([id, f]) => (window as any).lirelleMessages.slice(f as number)
        .find((m: any) => m.type === 'searchDone' && m.searchId === id), [searchId, from] as const)
    return (await messages(page)).slice(from)
        .filter(m => m.type === 'searchResults' && m.searchId === searchId)
        .flatMap(m => m.groups as { sectionIndex: number, results: { cfi: string, pre: string, match: string }[] }[])
        .flatMap(group => group.results.map(result => ({ ...result, sectionIndex: group.sectionIndex })))
}

const openFailure = async (page: Page, name: string, format: Format): Promise<Message> => {
    await send(page, { type: 'open', base: `/book/${name}/`, cfi: null, fraction: null, format, settings: SETTINGS })
    const error = await waitFor(page, 'error')
    await settle(page)
    expect((await messages(page)).some(m => m.type === 'opened')).toBe(false)
    return error
}

for (const { name, format, label } of BOOKS) {
    test.describe(label, () => {
        test('opens with its title, its direction and its text, and asks nothing of the network', async ({ page }) => {
            const { outside } = await startReader(page)
            const { opened, relocated } = await openBook(page, name, { format })
            expect(opened.title).toBe('The Ledger of Port Vesper')
            expect(opened.dir).toBe('ltr')
            expect(opened.fixedLayout).toBe(false)
            expect(relocated.cfi).toMatch(/^epubcfi\(.+\)$/)
            expect(relocated.fraction).toBeGreaterThanOrEqual(0)
            expect(relocated.fraction).toBeLessThan(0.2)
            expect(relocated.moved).toBe(false)
            expect(relocated.atEnd).toBe(false)
            expect(await textOnScreen(page)).toContain('Port Vesper')
            expect(outside).toEqual([])
        })

        test('lists its contents, names the chapter on screen and goes to an entry', async ({ page }) => {
            await startReader(page)
            const { opened, relocated } = await openBook(page, name, { format })
            const top = (opened.toc as { label: string, href: string, depth: number, id: number }[])
                .filter(entry => entry.depth === 0)
            expect(top.map(entry => entry.label).filter(entry => CHAPTERS.includes(entry))).toEqual(CHAPTERS)
            expect(opened.sectionLabels).toHaveLength(relocated.sectionCount)
            const entry = top.find(e => e.label === 'The Missing Gull')!
            const there = await sendAndRelocate(page, { type: 'goTo', href: entry.href })
            expect(there.byHost).toBe(true)
            expect(there.tocLabel).toBe('The Missing Gull')
            expect(there.tocId).toBe(entry.id)
            expect(opened.sectionLabels[there.sectionIndex]).toBe('The Missing Gull')
            expect(await textOnScreen(page)).toContain('The Missing Gull')
        })

        test('turns its pages forward and back, each with a place of its own', async ({ page }) => {
            await startReader(page)
            const { relocated } = await openBook(page, name, { format })
            const second = await sendAndRelocate(page, { type: 'next' })
            expect(second).toMatchObject({ moved: true, userMove: true })
            expect(second.cfi).not.toBe(relocated.cfi)
            const third = await sendAndRelocate(page, { type: 'next' })
            expect(third.cfi).not.toBe(second.cfi)
            expect(third.fraction).toBeGreaterThan(relocated.fraction)
            const back = await sendAndRelocate(page, { type: 'prev' })
            expect(back.cfi).toBe(second.cfi)
        })

        test('opens again where it was left, by its place and by its fraction', async ({ page }) => {
            await startReader(page)
            await openBook(page, name, { format })
            await sendAndRelocate(page, { type: 'next' })
            await sendAndRelocate(page, { type: 'next' })
            const left = await sendAndRelocate(page, { type: 'next' })
            const words = await firstWords(page)
            expect(words.length).toBeGreaterThan(10)

            const byPlace = await openBook(page, name, { format, cfi: left.cfi })
            expect(byPlace.relocated.cfi).toBe(left.cfi)
            expect(byPlace.relocated.moved).toBe(false)
            expect(await firstWords(page)).toBe(words)

            const byFraction = await openBook(page, name, { format, fraction: 0.6 })
            expect(byFraction.relocated.fraction).toBeGreaterThan(0.4)
            expect(byFraction.relocated.fraction).toBeLessThan(0.8)
        })

        test('is read to its end, in pages and in a scroll', async ({ page }) => {
            await startReader(page)
            await openBook(page, name, { format, fraction: 0.97 })
            let last: Message | null = null
            for (let i = 0; i < 12 && !(last && last.atEnd); i++) last = await sendAndRelocate(page, { type: 'next' })
            expect(last).toMatchObject({ atEnd: true, fraction: 1 })

            const { relocated } = await openBook(page, name, { format, settings: { flow: 'scrolled' } })
            expect(relocated.page).toBeNull()
            expect(await textOnScreen(page)).toContain('Port Vesper')
            const down = await sendAndRelocate(page, { type: 'next' })
            expect(down.cfi).not.toBe(relocated.cfi)
        })

        test('finds a word in every chapter and goes to a result', async ({ page }) => {
            await startReader(page)
            await openBook(page, name, { format })
            const results = await search(page, 1, 'barometer')
            expect(results.length).toBeGreaterThan(2)
            for (const result of results) expect(result.match.toLowerCase()).toBe('barometer')
            const target = results[results.length - 1]
            const there = await sendAndRelocate(page, { type: 'showSearchResult', cfi: target.cfi })
            expect(there.sectionIndex).toBe(target.sectionIndex)
            const shown = await page.evaluate(cfi => {
                const view = document.getElementById('view') as any
                const { doc } = view.renderer.getContents()[0]
                const range = view.resolveCFI(cfi).anchor(doc)
                const frame = doc.defaultView.frameElement.getBoundingClientRect()
                const [rect] = range.getClientRects()
                return { text: String(range), left: frame.left + rect.left, right: frame.left + rect.right }
            }, target.cfi)
            expect(shown.text.toLowerCase()).toBe('barometer')
            expect(shown.left).toBeGreaterThanOrEqual(0)
            expect(shown.right).toBeLessThanOrEqual(390)
        })

        test('keeps a highlight and a bookmark at the place they were made', async ({ page }) => {
            await startReader(page)
            await openBook(page, name, { format })
            const [found] = await search(page, 1, 'ledger')
            const there = await sendAndRelocate(page, { type: 'showSearchResult', cfi: found.cfi })
            const drawn = () => page.evaluate(() => {
                const view = document.getElementById('view') as any
                const { overlayer } = view.renderer.getContents()[0]
                return overlayer.element.querySelectorAll('rect').length as number
            })
            await send(page, { type: 'clearSearch' })
            await expect.poll(drawn).toBe(0)
            await send(page, {
                type: 'highlights',
                items: [{ id: 'h1', cfi: found.cfi, color: '#facc15', style: 'highlight', note: false }],
            })
            await expect.poll(drawn).toBeGreaterThan(0)

            const from = await mark(page)
            await send(page, { type: 'bookmarks', items: [{ id: 'b1', cfi: there.cfi }] })
            await page.waitForFunction(f => (window as any).lirelleMessages.slice(f)
                .find((m: any) => m.type === 'relocated' && m.bookmarkIds.includes('b1')), from)
        })

        test('opens after an EPUB, and an EPUB after it', async ({ page }) => {
            await startReader(page)
            await openBook(page, 'rtl')
            const { opened } = await openBook(page, name, { format })
            expect(opened.title).toBe('The Ledger of Port Vesper')
            const epub = await openBook(page, 'basic')
            expect(epub.relocated.sectionCount).toBe(3)
            expect(epub.opened.toc).toHaveLength(5)
        })
    })
}

test('a book given as one file is asked for once, whole', async ({ page }) => {
    const { requested } = await startReader(page)
    await openBook(page, 'basic.mobi', { format: 'mobi' })
    await sendAndRelocate(page, { type: 'next' })
    expect(requested.filter(path => path.startsWith('/book/'))).toEqual(['/book/basic.mobi/file'])
})

test('a link of a FictionBook to one of its notes opens the note in the panel', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'notes.fb2', { format: 'fb2' })
    expect(opened.title).toBe('The Tide Tables')
    const from = await mark(page)
    const frame = await contentFrame(page)
    const box = (await frame.locator('a[href="#n1"]').boundingBox())!
    await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2)
    const note = await waitFor(page, 'noteOpened', from)
    expect(note.href).toBe('#n1')
    await expect(page.locator('#note-layer')).toHaveAttribute('data-state', 'open')
    const shown = page.frames().filter(f => f.url().startsWith('blob:'))
    const texts = await Promise.all(shown.map(f => f.locator('body').innerText().catch(() => '')))
    expect(texts.some(text => text.includes('It had been dropped twice') && !text.includes('The harbour master')))
        .toBe(true)
})

test('a resource hint of a Kindle book is taken out of the document before it is shown', async ({ page }) => {
    await startReader(page)
    for (const name of ['basic.mobi', 'basic.azw3']) {
        await openBook(page, name, { format: 'mobi' })
        const written = await page.evaluate(() => {
            const book = (document.getElementById('view') as any).book
            const doc = new DOMParser().parseFromString(`<html><head>
                <link rel="dns-prefetch" href="https://tracker.example/"/>
                <meta http-equiv="x-dns-prefetch-control" content="on"/>
                <link rel="stylesheet" href="style.css"/></head>
                <body><p>One</p><link rel="preconnect" href="https://tracker.example/"/><p>Two</p></body></html>`,
            'text/html')
            return book.serializer.serializeToString(doc) as string
        })
        expect(written).not.toContain('tracker.example')
        expect(written).not.toContain('dns-prefetch')
        expect(written).toContain('stylesheet')
        // In the body, the hint keeps its place: the positions of the book count the elements there.
        expect(written).toMatch(/<p>One<\/p><link[^>]*\/?>(<\/link>)?<p>Two<\/p>/)
    }
})

// The books of books/encodings.py. The engine reads the one-byte encoding a FictionBook names in its declaration,
// and the check made before it does not refuse the book for it.
test('a FictionBook in windows-1251 opens with its title and its text, as they were written', async ({ page }) => {
    await startReader(page)
    const { opened } = await openBook(page, 'windows-1251.fb2', { format: 'fb2' })
    expect(opened.title).toBe('Таблицы приливов')
    expect(await textOnScreen(page)).toContain('Начальник порта вёл журнал всех приливов')
    const top = (opened.toc as { label: string, depth: number }[]).filter(entry => entry.depth === 0)
    expect(top.map(entry => entry.label)).toEqual(['Барометр', 'Утренний катер'])
})

// The book of books/untitled.py: its sections have no title, so the engine names each after its first paragraph,
// and gives no name to the one that holds a picture alone.
test.describe('a FictionBook whose sections have no title', () => {
    const LEDGER = 'The harbour master kept a ledger of every tide he had ever seen, and of every…'
    const LAMP = 'The lamp was lit at dusk, and it stayed lit until the last of the boats had…'

    test('lists a short label for each section that has text, and none for the picture', async ({ page }) => {
        await startReader(page)
        const { opened, relocated } = await openBook(page, 'untitled.fb2', { format: 'fb2' })
        const toc = opened.toc as { label: string, href: string, depth: number, id: number }[]
        expect(toc.map(entry => entry.label)).toEqual([LEDGER, 'A short room.', LAMP])
        for (const entry of toc) {
            expect(entry.label.length).toBeLessThanOrEqual(80)
            expect(entry.depth).toBe(0)
        }
        // The picture is still a section of the book, read as the others are; it has no label.
        expect(relocated.sectionCount).toBe(4)
        expect(relocated.sectionIndex).toBe(0)
        expect(relocated.tocLabel).toBeNull()
        expect(opened.sectionLabels).toEqual([null, LEDGER, 'A short room.', LAMP])
    })

    test('names the section on screen by its short label, and goes to an entry', async ({ page }) => {
        await startReader(page)
        const { opened } = await openBook(page, 'untitled.fb2', { format: 'fb2' })
        const next = await sendAndRelocate(page, { type: 'next' })
        expect(next.sectionIndex).toBe(1)
        expect(next.tocLabel).toBe(LEDGER)
        const entry = (opened.toc as { label: string, href: string, id: number }[])[2]
        const there = await sendAndRelocate(page, { type: 'goTo', href: entry.href })
        expect(there.sectionIndex).toBe(3)
        expect(there.tocLabel).toBe(LAMP)
        expect(there.tocId).toBe(entry.id)
        expect(await textOnScreen(page)).toContain('Then the keeper went down to his supper.')
    })

    test('groups what a search finds under the short label', async ({ page }) => {
        await startReader(page)
        await openBook(page, 'untitled.fb2', { format: 'fb2' })
        const from = await mark(page)
        await send(page, { type: 'search', searchId: 1, query: 'keeper' })
        await page.waitForFunction(f => (window as any).lirelleMessages.slice(f)
            .find((m: any) => m.type === 'searchDone' && m.searchId === 1), from)
        const groups = (await messages(page)).slice(from)
            .filter(m => m.type === 'searchResults').flatMap(m => m.groups as { sectionIndex: number, label: string }[])
        expect(groups.map(group => [group.sectionIndex, group.label])).toEqual([[3, LAMP]])
    })
})

// What the page does to every label it hands to the host (labels.js).
test.describe('a label', () => {
    const labels = (page: Page) => page.evaluateHandle(() => import('/reader/labels.js' as string))

    test('is kept as written when short, its white space made single', async ({ page }) => {
        await startReader(page)
        const module = await labels(page)
        const short = (value: unknown) => module.evaluate((m: any, v) => m.shortLabel(v), value)
        expect(await short('The Green Lighthouse')).toBe('The Green Lighthouse')
        expect(await short('  The\n   Green Lighthouse\t')).toBe('The Green Lighthouse')
        expect(await short('x'.repeat(80))).toBe('x'.repeat(80))
        expect(await short(null)).toBe('')
        expect(await short(' \n ')).toBe('')
    })

    test('is cut at 80 characters, between words where it has some, and ends with an ellipsis', async ({ page }) => {
        await startReader(page)
        const module = await labels(page)
        const short = (value: unknown) => module.evaluate((m: any, v) => m.shortLabel(v), value)
        // The cut falls after a word: nothing more is taken away.
        expect(await short('word '.repeat(30))).toBe('word '.repeat(15) + 'word…')
        // One word and no other: it is cut where the room ends.
        expect(await short('x'.repeat(81))).toBe('x'.repeat(79) + '…')
        // A character of two code units is not cut in two.
        expect(await short('😀'.repeat(81))).toBe('😀'.repeat(79) + '…')
        // A label already cut stays as it is.
        const once = await short('word '.repeat(30))
        expect(await short(once)).toBe(once)
    })

    test('that is empty leaves its entry out of the contents, the entries under it taking its place', async ({ page }) => {
        await startReader(page)
        const module = await labels(page)
        const toc = [
            { id: 0, label: '  ', href: 'a', subitems: [
                { id: 1, label: 'One', href: 'a#1', subitems: [{ id: 2, label: 'Deep', href: 'a#2' }] },
                { id: 3, label: null, href: 'a#3' },
            ] },
            { id: 4, label: 'Two', href: 'b', subitems: [{ id: 5, label: 'Under two', href: 'b#1' }] },
        ]
        expect(await module.evaluate((m: any, t) => m.contentsEntries(t).filter((e: any) => e.label), toc)).toEqual([
            { id: 1, label: 'One', href: 'a#1', depth: 0 },
            { id: 2, label: 'Deep', href: 'a#2', depth: 1 },
            { id: 4, label: 'Two', href: 'b', depth: 0 },
            { id: 5, label: 'Under two', href: 'b#1', depth: 1 },
        ])
    })
})

test.describe('a file the engine cannot read', () => {
    test('a Kindle book whose text is encrypted is refused as it is opened', async ({ page }) => {
        await startReader(page)
        const error = await openFailure(page, 'protected.mobi', 'mobi')
        expect(error.code).toBe('cannot_open')
        expect(error.message).toContain('protected')
        expect(page.frames().filter(f => f.url().startsWith('blob:'))).toHaveLength(0)
    })

    test('a Kindle book cut short is refused', async ({ page }) => {
        await startReader(page)
        expect((await openFailure(page, 'damaged.mobi', 'mobi')).code).toBe('cannot_open')
    })

    test('a FictionBook cut short is refused', async ({ page }) => {
        await startReader(page)
        expect((await openFailure(page, 'damaged.fb2', 'fb2')).code).toBe('cannot_open')
    })

    // The engine reads the declaration as UTF-8 to learn the encoding, and finds none in UTF-16: left to it, the
    // book would open with no title and nothing to read.
    test('a FictionBook in UTF-16, which the engine does not read, is refused', async ({ page }) => {
        await startReader(page)
        expect((await openFailure(page, 'utf-16.fb2', 'fb2')).code).toBe('cannot_open')
    })

    test('a file that is not of the format it is given as is refused', async ({ page }) => {
        await startReader(page)
        expect((await openFailure(page, 'basic.fb2', 'mobi')).code).toBe('cannot_open')
        const from = await mark(page)
        await send(page, {
            type: 'open', base: '/book/basic.epub/', cfi: null, fraction: null, format: 'fb2', settings: SETTINGS,
        })
        expect((await waitFor(page, 'error', from)).code).toBe('cannot_open')
    })

    test('a file that cannot be fetched is a failure of the page, not of the book', async ({ page }) => {
        await startReader(page, { missing: ['basic.mobi/file'] })
        expect((await openFailure(page, 'basic.mobi', 'mobi')).code).toBe('open_failed')
    })

    test('a book opens after one that was refused', async ({ page }) => {
        await startReader(page)
        await openFailure(page, 'protected.mobi', 'mobi')
        const { opened } = await openBook(page, 'basic.azw3', { format: 'mobi' })
        expect(opened.title).toBe('The Ledger of Port Vesper')
    })
})
