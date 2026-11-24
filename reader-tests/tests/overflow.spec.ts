import { expect, test, type Frame, type Page } from '@playwright/test'
import { contentFrame, openBook, sendAndRelocate, startReader, type Message } from './harness'

const BLOCKS = 'p, div, h1, h2, h3, h4, h5, h6, li, blockquote, dd, dt, section, article, aside, header, '
    + 'footer, figure, table, pre, ul, ol, td, th, img'

type Box = { what: string, left: number, right: number, top: number }

/**
 * Every box of the chapter on screen that leaves its column, in the coordinates of the chapter's
 * own document: the engine lays a chapter out as one row of pages of the same width, with half a
 * gap on either side of the column of each.
 */
const escapees = (frame: Frame) => frame.evaluate(selector => {
    const root = document.documentElement
    const page = parseFloat(root.style.width)
    const gap = parseFloat(getComputedStyle(root).columnGap)
    const out: Box[] = []
    const check = (what: string, rect: DOMRect) => {
        if (rect.width === 0 || rect.height === 0) return
        const index = Math.floor((rect.left + 0.5) / page)
        const left = index * page + gap / 2
        const right = (index + 1) * page - gap / 2
        if (rect.left < left - 0.5 || rect.right > right + 0.5)
            out.push({ what, left: rect.left - left, right: rect.right - right, top: rect.top })
    }
    for (const el of document.body.querySelectorAll(selector)) {
        const name = el.id ? `#${el.id}` : `${el.localName}.${el.className}`
        for (const rect of el.getClientRects()) check(name, rect)
    }
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    const range = document.createRange()
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const parent = node.parentElement!
        // Word by word: the spaces that end a line of a `pre` hang past it, and draw nothing.
        for (const word of (node as Text).data.matchAll(/\S+/g)) {
            range.setStart(node, word.index!)
            range.setEnd(node, word.index! + word[0].length)
            for (const rect of range.getClientRects())
                check(`text of ${parent.id ? '#' + parent.id : parent.localName}`, rect)
        }
    }
    return { page, gap, out }
}, BLOCKS)

/** The lines of text of the chapter, and which of them the screen shows whole or cut. */
const lines = (frame: Frame) => frame.evaluate(() => {
    // The engine shows one page of the chapter at a time, in the middle of the screen.
    const width = parseFloat(document.documentElement.style.width)
    const start = (window.parent.innerWidth - width) / 2
    const offset = window.frameElement!.getBoundingClientRect().left - start
    const whole: string[] = []
    const cut: string[] = []
    const all: string[] = []
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    const range = document.createRange()
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        range.selectNodeContents(node)
        for (const rect of range.getClientRects()) {
            if (rect.width < 1 || rect.height < 1) continue
            const key = `${Math.round(rect.left)},${Math.round(rect.top)},${Math.round(rect.width)}`
            all.push(key)
            const left = offset + rect.left
            const right = offset + rect.right
            if (right <= 0.5 || left >= width - 0.5) continue
            if (left >= -0.5 && right <= width + 0.5) whole.push(key)
            else cut.push(key)
        }
    }
    return { whole, cut, all }
})

/** Turns through the whole chapter on screen and checks that each line is shown once, and whole. */
const readThrough = async (page: Page, first: Message) => {
    const frame = await contentFrame(page)
    const seen = new Map<string, number>()
    let all: string[] = []
    let at = first
    expect(at.page).toBe(1)
    for (let n = 1; ; n++) {
        const now = await lines(frame)
        expect(now.cut, `lines cut by the edge of page ${n}`).toEqual([])
        expect(now.whole.length, `lines on page ${n}`).toBeGreaterThan(0)
        for (const key of now.whole) {
            expect(seen.has(key), `line ${key} of page ${n} was already on page ${seen.get(key)}`).toBe(false)
            seen.set(key, n)
        }
        all = now.all
        if (n === at.pages) break
        const next = await sendAndRelocate(page, { type: 'next' })
        expect(next.sectionIndex).toBe(first.sectionIndex)
        expect(next.page).toBe(n + 1)
        expect(next.pages).toBe(first.pages)
        at = next
    }
    expect(seen.size).toBe(new Set(all).size)
    return at
}

const computed = (frame: Frame, selector: string) => frame.evaluate(s => {
    const style = getComputedStyle(document.querySelector(s)!)
    return {
        marginLeft: style.marginLeft, marginRight: style.marginRight,
        paddingLeft: style.paddingLeft, textIndent: style.textIndent,
    }
}, selector)

test('margins of the book that pull a paragraph out of its column are neutralised', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'overflowing')
    const first = await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    const frame = await contentFrame(page)
    const { page: width, gap, out } = await escapees(frame)
    expect(width).toBeGreaterThan(300)
    expect(width).toBeLessThan(390)
    expect(gap).toBeGreaterThan(0)
    expect(out).toEqual([])

    expect((await computed(frame, '#centred')).marginRight).toBe('0px')
    expect((await computed(frame, '#pulled')).marginLeft).toBe('0px')
    expect((await computed(frame, '#outdented')).textIndent).toBe('0px')
    expect(first.pages).toBeGreaterThan(2)
    await readThrough(page, first)
})

test('the indents a book is right to ask for are kept', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'overflowing')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    const frame = await contentFrame(page)
    expect((await computed(frame, '#plain')).marginLeft).toBe('0px')
    expect((await computed(frame, '#indented')).marginLeft).toBe('48px')
    const hanging = await computed(frame, '#hanging')
    expect(hanging.paddingLeft).toBe('36px')
    expect(hanging.textIndent).toBe('-36px')
    // Pulled back into the padding of its parent: it never leaves the column.
    const boxed = await computed(frame, '#boxed')
    expect(boxed.marginLeft).toBe('-20px')
    expect(boxed.marginRight).toBe('-20px')
    // Drawn where the book put them.
    const lefts = await frame.evaluate(() => {
        const left = (s: string) => document.querySelector(s)!.getBoundingClientRect().left
        const firstLine = (s: string) => {
            const range = document.createRange()
            range.selectNodeContents(document.querySelector(s)!)
            return range.getClientRects()[0].left
        }
        return {
            plain: left('#plain'), indented: left('#indented'),
            hanging: left('#hanging'), hangingFirst: firstLine('#hanging'), box: left('#box'), boxed: left('#boxed'),
        }
    })
    expect(lefts.indented - lefts.plain).toBeCloseTo(48, 0)
    // The first line hangs back to the edge of the column, the others are indented.
    expect(lefts.hangingFirst - lefts.hanging).toBeCloseTo(0, 0)
    expect(lefts.boxed - lefts.box).toBeCloseTo(20, 0)
})

test('long words, a wide table and a fixed width stay on their page', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'overflowing')
    const first = await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    const frame = await contentFrame(page)
    const { out } = await escapees(frame)
    expect(out).toEqual([])
    // Nothing in the chapter scrolls sideways by itself: a swipe always turns the page.
    const scrollers = await frame.evaluate(() => [...document.body.querySelectorAll('*')]
        .filter(el => el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflowX !== 'visible')
        .map(el => el.id || el.localName))
    expect(scrollers).toEqual([])
    expect(await frame.evaluate(() => document.querySelectorAll('#table tr:first-child th').length)).toBe(8)
    await readThrough(page, first)
})

test('the same content stays inside the column in scrolled layout and with two columns', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'overflowing', { settings: { flow: 'scrolled' } })
    for (const href of ['OEBPS/ch1.xhtml', 'OEBPS/ch2.xhtml']) {
        await sendAndRelocate(page, { type: 'goTo', href })
        const frame = await contentFrame(page)
        const wide = await frame.evaluate(() => {
            const body = document.body.getBoundingClientRect()
            return [...document.body.querySelectorAll('*')].filter(el => {
                const rect = el.getBoundingClientRect()
                return rect.width > 0 && (rect.left < body.left - 0.5 || rect.right > body.right + 0.5)
            }).map(el => el.id || el.localName)
        })
        expect(wide, href).toEqual([])
    }

    await page.setViewportSize({ width: 900, height: 600 })
    await startReader(page)
    await openBook(page, 'overflowing', { settings: { maxColumns: 2 } })
    for (const href of ['OEBPS/ch1.xhtml', 'OEBPS/ch2.xhtml']) {
        await sendAndRelocate(page, { type: 'goTo', href })
        const frame = await contentFrame(page)
        const columns = await frame.evaluate(() => {
            const root = document.documentElement
            const style = getComputedStyle(root)
            const gap = parseFloat(style.columnGap)
            // Two columns to a page, half a gap outside each.
            const pitch = parseFloat(root.style.width) / 2
            const column = pitch - gap
            const out: string[] = []
            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
            const range = document.createRange()
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                for (const word of (node as Text).data.matchAll(/\S+/g)) {
                    range.setStart(node, word.index!)
                    range.setEnd(node, word.index! + word[0].length)
                    for (const rect of range.getClientRects()) {
                        if (rect.width < 1) continue
                        const left = Math.floor((rect.left - gap / 2 + 0.5) / pitch) * pitch + gap / 2
                        if (rect.left < left - 0.5 || rect.right > left + column + 0.5)
                            out.push(node.parentElement!.id || node.parentElement!.localName)
                    }
                }
            }
            return out
        })
        expect(columns, href).toEqual([])
    }
})

test('a page that holds a single picture is one page, and is recognised as a cover', async ({ page }) => {
    await startReader(page)
    const { relocated } = await openBook(page, 'overflowing')
    expect(relocated.sectionIndex).toBe(0)
    expect(relocated.page).toBe(1)
    expect(relocated.pages).toBe(1)
    const frame = await contentFrame(page)
    const box = await frame.locator('#cover').boundingBox()
    expect(box).not.toBeNull()
    expect(box!.width).toBeGreaterThan(100)
    expect(box!.width / box!.height).toBeCloseTo(120 / 180, 1)
    expect(await frame.evaluate(() => document.documentElement.hasAttribute('data-lirelle-cover'))).toBe(true)

    // A chapter with text next to its picture is left alone.
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch1.xhtml' })
    const chapter = await contentFrame(page)
    expect(await chapter.evaluate(() => document.documentElement.hasAttribute('data-lirelle-cover'))).toBe(false)
    expect(await chapter.evaluate(() => getComputedStyle(document.body).display)).toBe('block')
})

test('a picture among text is not taken for a cover', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    await sendAndRelocate(page, { type: 'goTo', href: 'OEBPS/ch2.xhtml' })
    const frame = await contentFrame(page)
    expect(await frame.evaluate(() => document.documentElement.hasAttribute('data-lirelle-cover'))).toBe(false)
    const plate = await frame.locator('#plate').boundingBox()
    expect(plate!.width).toBe(160)
    expect(plate!.height).toBe(100)
})

test('a fixed-layout book keeps the positions it sets', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'fixed')
    const frame = await contentFrame(page)
    const touched = await frame.evaluate(() => ({
        inline: [...document.querySelectorAll('body *')].filter(el => (el as HTMLElement).style.length > 0).length,
        cover: document.documentElement.hasAttribute('data-lirelle-cover'),
        ready: document.documentElement.hasAttribute('data-lirelle-ready'),
    }))
    expect(touched).toEqual({ inline: 0, cover: false, ready: false })
})
