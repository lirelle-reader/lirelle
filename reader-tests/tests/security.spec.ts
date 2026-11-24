import { expect, test } from '@playwright/test'
import {
    contentFrame, mark, messages, noteFrame, openBook, ORIGIN, send, SETTINGS, settle, since,
    startReader, tapElement, waitFor,
} from './harness'

const TITLE = 'Lirelle reader'

const traces = async (page: import('@playwright/test').Page) => {
    const frame = await contentFrame(page)
    return {
        pageTitle: await page.title(),
        flag: await page.evaluate(() => (window as any).lirelleScriptRan ?? null),
        chapterTitle: await frame.evaluate(() => document.title),
    }
}

for (const csp of ['header', 'meta'] as const) {
    test(`no script of a book runs (policy sent as ${csp})`, async ({ page }) => {
        const violations: string[] = []
        page.on('console', m => { if (m.text().includes('Content Security Policy')) violations.push(m.text()) })
        const { outside, requested } = await startReader(page, { csp })
        const { opened } = await openBook(page, 'scripted')
        expect(opened.title).toBe('The Book That Tried')
        const frame = await contentFrame(page)
        await expect(frame.locator('#trap')).toBeVisible()

        // The handler and the script link get their chance too.
        let from = await mark(page)
        await tapElement(page, '#trap')
        await waitFor(page, 'tap', from)
        from = await mark(page)
        await tapElement(page, '#jslink')
        const link = await waitFor(page, 'link', from)
        // A javascript: address is handed to the host as an external link, never followed.
        expect(link.external).toBe(true)
        expect(link.href).toMatch(/^javascript:/)
        await settle(page)

        expect(await traces(page)).toEqual({ pageTitle: TITLE, flag: null, chapterTitle: 'A Quiet Chapter' })
        expect(outside).toEqual([])
        // The script file of the book is never even asked for.
        expect(requested.filter(p => p.endsWith('script.js'))).toEqual([])
        // The policy is what stopped the inline script, the handler and the picture from elsewhere.
        expect(violations.some(v => /inline script/i.test(v))).toBe(true)
        expect(violations.some(v => /inline event handler/i.test(v))).toBe(true)
        expect(violations.some(v => v.includes('evil.example/pixel.png'))).toBe(true)
        expect(context(page).pages()).toHaveLength(1)
    })
}

const context = (page: import('@playwright/test').Page) => page.context()

// The note panel shows a book document in a second view: the same two layers apply to it.
for (const csp of ['header', 'meta'] as const) {
    test(`with a note open no script of the book runs and nothing leaves the origin (policy sent as ${csp})`,
        async ({ page }) => {
            const violations: string[] = []
            page.on('console', m => { if (m.text().includes('Content Security Policy')) violations.push(m.text()) })
            const seen: string[] = []
            const stopped: string[] = []
            page.on('request', request => seen.push(request.url()))
            page.on('requestfailed', request => {
                if (request.failure()?.errorText === 'csp') stopped.push(request.url())
            })
            const { outside, requested } = await startReader(page, { csp })
            await openBook(page, 'scripted')
            let from = await mark(page)
            await tapElement(page, '#noteref')
            expect((await waitFor(page, 'noteOpened', from)).href).toBe('OEBPS/notes.xhtml#trapnote')
            const note = await noteFrame(page)
            await expect(note.locator('#notetrap')).toBeVisible()
            await expect(note.locator('h1')).toHaveCount(0)
            // The note's own handler gets its chance.
            await expect.poll(() => page.evaluate(() => document.getElementById('note-layer')!.dataset.state))
                .toBe('open')
            const box = (await note.locator('#notetrap').boundingBox())!
            await page.touchscreen.tap(box.x + 10, box.y + box.height / 2)
            await settle(page)

            expect(await page.title()).toBe(TITLE)
            expect(await page.evaluate(() => (window as any).lirelleScriptRan ?? null)).toBeNull()
            expect(await note.evaluate(() => document.title)).toBe('Notes That Tried')
            expect(outside).toEqual([])
            expect(requested.filter(p => p.endsWith('script.js'))).toEqual([])
            const elsewhere = seen.filter(url => !url.startsWith(ORIGIN + '/')
                && !url.startsWith('blob:' + ORIGIN + '/') && !url.startsWith('data:') && !stopped.includes(url))
            expect(elsewhere).toEqual([])
            expect(stopped.sort()).toEqual(['https://evil.example/note.png', 'https://evil.example/pixel.png'])
            expect(violations.some(v => /inline event handler/i.test(v))).toBe(true)
            from = await mark(page)
            await send(page, { type: 'closeNote' })
            await waitFor(page, 'noteClosed', from)
            expect(context(page).pages()).toHaveLength(1)
        })
}

// The control: without the policy the inline script does run, so the test
// above can tell the difference. The script file stays refused by the page.
test('without any policy the inline script of the book would run', async ({ page }) => {
    const { requested } = await startReader(page, { csp: 'none' })
    await openBook(page, 'scripted')
    await settle(page)
    const seen = await traces(page)
    expect(seen.pageTitle).toBe('SCRIPT RAN inline')
    expect(seen.flag).toBe('inline')
    expect(requested.filter(p => p.endsWith('script.js'))).toEqual([])
})

test('a book document cannot start a second reader or reach the host', async ({ page }) => {
    await startReader(page)
    await openBook(page, 'basic')
    const frame = await contentFrame(page)
    const before = (await messages(page)).length
    const seen = await frame.evaluate(async () => {
        const w = window as any
        // The page's own script, loaded inside the book's frame, must stay inert.
        await import('https://appassets.androidplatform.net/reader/reader.js').catch(() => null)
        return { lirelle: typeof w.lirelle, host: typeof w.lirelleHost }
    })
    expect(seen).toEqual({ lirelle: 'undefined', host: 'undefined' })
    await settle(page, 300)
    expect((await messages(page)).length).toBe(before)
})

for (const book of ['basic', 'rtl', 'fixed', 'footnote', 'scripted', 'fonts', 'marks', 'bare']) {
    test(`reading ${book} makes no request outside the app's origin`, async ({ page }) => {
        const seen: string[] = []
        // A request stopped by the policy is announced by the browser and fails at once, unsent.
        const stopped: string[] = []
        page.on('request', request => seen.push(request.url()))
        page.on('requestfailed', request => {
            if (request.failure()?.errorText === 'csp') stopped.push(request.url())
        })
        const { outside } = await startReader(page)
        const { opened } = await openBook(page, book, { settings: { font: 'serif', dark: true } })
        for (let i = 0; i < 4; i++) {
            await send(page, { type: 'next' })
            await settle(page, 300)
        }
        if (opened.toc.length) await send(page, { type: 'goTo', href: opened.toc[0].href })
        await settle(page, 300)
        await send(page, { type: 'settings', settings: { ...SETTINGS, flow: 'scrolled', font: 'sans' } })
        await settle(page)
        expect(outside).toEqual([])
        const elsewhere = seen.filter(url => !url.startsWith(ORIGIN + '/') && !url.startsWith('blob:' + ORIGIN + '/')
            && !url.startsWith('data:') && !stopped.includes(url))
        expect(elsewhere).toEqual([])
        expect(stopped).toEqual(book === 'scripted' ? ['https://evil.example/pixel.png'] : [])
        expect(await since(page, 'error', 0)).toEqual([])
    })
}

// A resource hint is no request the policy could stop: the browser looks the
// host up, or connects to it, by itself. The page takes them out of the book.
test('the resource hints of a book are taken out of its documents', async ({ page }) => {
    const { outside } = await startReader(page)
    const { opened } = await openBook(page, 'hinted')
    expect(opened.title).toBe('The Book That Hinted')
    const frame = await contentFrame(page)
    await expect(frame.locator('h1')).toBeVisible()
    await settle(page)

    const seen = await frame.evaluate(() => ({
        links: [...document.head.querySelectorAll('link')].map(link => link.getAttribute('rel')),
        prefetchControl: document.querySelectorAll('meta[http-equiv]').length,
        heading: getComputedStyle(document.querySelector('h1')!).color,
    }))
    // The book's own style sheet stays, and still applies.
    expect(seen).toEqual({ links: ['stylesheet'], prefetchControl: 0, heading: 'rgb(34, 68, 136)' })
    expect(outside).toEqual([])
    expect(await since(page, 'error', 0)).toEqual([])
})

// Positions count the elements of the body: a hint there is made inert, not taken out, so that
// every position after it keeps pointing where it did in the book's own file.
test('a resource hint outside the head is made inert and keeps its place', async ({ page }) => {
    const { outside } = await startReader(page)
    await openBook(page, 'hinted')
    const frame = await contentFrame(page)
    await expect(frame.locator('#after')).toBeVisible()
    const seen = await frame.evaluate(() => {
        const children = [...document.body.children]
        const link = document.body.querySelector('link')
        const meta = document.body.querySelector('meta')
        const root = [...document.documentElement.children]
        const between = root.find(el => el.localName === 'link')
        return {
            names: children.slice(0, 5).map(el => el.localName),
            link: link && link.getAttributeNames(),
            meta: meta && meta.getAttributeNames(),
            // The body stays the third child of the root, as in the book's file.
            root: root.map(el => el.localName),
            between: between ? between.getAttributeNames() : null,
        }
    })
    expect(seen).toEqual({
        names: ['h1', 'p', 'link', 'meta', 'p'], link: [], meta: ['content'],
        root: ['head', 'link', 'body'], between: [],
    })
    expect(outside).toEqual([])
    expect(await since(page, 'error', 0)).toEqual([])
})

test('a protected book never reaches another host either, whatever it shows', async ({ page }) => {
    const { outside } = await startReader(page)
    await send(page, { type: 'open', base: '/book/protected/', cfi: null, fraction: null, settings: SETTINGS })
    await expect.poll(async () => (await messages(page)).some(m => m.type === 'opened' || m.type === 'error')).toBe(true)
    await settle(page)
    expect(outside).toEqual([])
    expect(await page.title()).toBe(TITLE)
})
