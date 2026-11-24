import { expect, type Frame, type Page } from '@playwright/test'
import * as fs from 'node:fs'
import * as path from 'node:path'

export const ORIGIN = 'https://appassets.androidplatform.net'
export const READER_URL = `${ORIGIN}/reader/index.html`

// The policy the host sends as a response header (and the page repeats as a <meta>).
export const CSP = "default-src 'none'; script-src 'self'; style-src 'self' blob: 'unsafe-inline'; "
    + "img-src 'self' blob: data:; font-src 'self' blob: data:; media-src blob:; "
    + "connect-src 'self'; frame-src blob:; object-src 'none'; base-uri 'none'; form-action 'none'"

const ASSETS = path.resolve(__dirname, '../../feature/reader/src/main/assets')
const BOOKS = path.resolve(__dirname, '../books/src')
// The books that are one file to the page: `/book/<file name>/file` is the whole of it.
const WHOLE_BOOKS = path.resolve(__dirname, '../books/out')

const TYPES: Record<string, string> = {
    '.html': 'text/html',
    '.js': 'text/javascript',
    '.json': 'application/json',
    '.css': 'text/css',
    '.xhtml': 'application/xhtml+xml',
    '.opf': 'application/oebps-package+xml',
    '.xml': 'application/xml',
    '.png': 'image/png',
    '.ttf': 'font/ttf',
    '.woff2': 'font/woff2',
}

export type Settings = {
    background: string, foreground: string, link: string, dark: boolean,
    font: 'book' | 'serif' | 'sans' | 'dyslexic', fontSizePx: number, lineHeight: number,
    marginPercent: number, align: 'book' | 'justify' | 'start', hyphenate: boolean,
    flow: 'paginated' | 'scrolled', maxColumns: 1 | 2, brightnessEdge: 'left' | 'right' | 'off',
    highlightBlend: 'multiply' | 'normal', highlightOpacity: number, searchColor: string, footnotePanel: boolean,
    noteLabels?: { goTo: string, title: string },
}

export const SETTINGS: Settings = {
    background: '#ffffff', foreground: '#111111', link: '#1a55cc', dark: false,
    font: 'book', fontSizePx: 18, lineHeight: 1.5, marginPercent: 7, align: 'book',
    hyphenate: true, flow: 'paginated', maxColumns: 1, brightnessEdge: 'left',
    highlightBlend: 'multiply', highlightOpacity: 0.3, searchColor: '#d97706', footnotePanel: true,
}

export type Message = { type: string, [key: string]: any }

const walk = (dir: string, prefix = ''): Record<string, number> => {
    const out: Record<string, number> = {}
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const name = prefix + entry.name
        const full = path.join(dir, entry.name)
        if (entry.isDirectory()) Object.assign(out, walk(full, name + '/'))
        else out[name] = fs.statSync(full).size
    }
    return out
}

// Resolves a request path inside a root, refusing anything that escapes it.
const inside = (root: string, relative: string) => {
    const full = path.resolve(root, relative)
    return full === root || full.startsWith(root + path.sep) ? full : null
}

export type ReaderOptions = {
    /** 'header' (default): header and <meta>; 'meta': the <meta> alone; 'none': no policy at all. */
    csp?: 'header' | 'meta' | 'none',
    /** Entries of books answered with a 404, as `<book>/<path>`. */
    missing?: string[],
    /** Entries of books answered late, as `<book>/<path>` to milliseconds. */
    delays?: Record<string, number>,
    /** false when the page is not expected to say it is ready. */
    ready?: boolean,
}

/**
 * Serves the page, the fonts folder and the unzipped test books under the
 * production origin, refuses every other host, and records what was asked for.
 */
export const startReader = async (page: Page, options: ReaderOptions = {}) => {
    // LIRELLE_SLOW=4 runs the page four times slower, as on a slow runner or device: timings the tests rely on
    // show there first.
    const slow = Number(process.env.LIRELLE_SLOW ?? 1)
    if (slow > 1) {
        const cdp = await page.context().newCDPSession(page)
        await cdp.send('Emulation.setCPUThrottlingRate', { rate: slow })
    }
    const mode = options.csp ?? 'header'
    const outside: string[] = []
    const requested: string[] = []
    const headers = (type: string) => ({
        'content-type': type,
        ...(mode === 'header' ? { 'content-security-policy': CSP } : {}),
    })

    await page.addInitScript(() => {
        const w = window as any
        if (window.top !== window) return
        w.lirelleMessages = []
        w.lirelleHost = { postMessage: (json: string) => w.lirelleMessages.push(JSON.parse(json)) }
    })

    await page.context().route('**/*', async route => {
        const url = new URL(route.request().url())
        if (url.origin !== ORIGIN) {
            outside.push(url.href)
            return route.abort('blockedbyclient')
        }
        requested.push(url.pathname)
        const notFound = () => route.fulfill({ status: 404, headers: headers('text/plain'), body: '' })
        const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent)
        const serve = (full: string | null) => {
            if (!full || !fs.existsSync(full) || !fs.statSync(full).isFile()) return notFound()
            let body: Buffer | string = fs.readFileSync(full)
            if (mode === 'none' && full.endsWith('index.html'))
                body = body.toString('utf8').replace(/<meta http-equiv="Content-Security-Policy"[^>]*>/, '')
            const type = TYPES[path.extname(full)] ?? 'application/octet-stream'
            return route.fulfill({ status: 200, headers: headers(type), body })
        }
        if (parts[0] === 'reader') return serve(inside(path.join(ASSETS, 'reader'), parts.slice(1).join('/')))
        if (parts[0] === 'fonts') return serve(inside(path.join(ASSETS, 'fonts'), parts.slice(1).join('/')))
        if (parts[0] === 'book' && parts.length === 3 && parts[2] === 'file') {
            if (options.missing?.includes(`${parts[1]}/file`)) return notFound()
            return serve(inside(WHOLE_BOOKS, parts[1]))
        }
        if (parts[0] === 'book' && parts.length >= 3) {
            const root = path.join(BOOKS, parts[1])
            const entry = parts.slice(2).join('/')
            if (!fs.existsSync(root)) return notFound()
            if (entry === 'index.json') return route.fulfill({
                status: 200,
                headers: headers('application/json'),
                body: JSON.stringify({ entries: walk(root) }),
            })
            const delay = options.delays?.[`${parts[1]}/${entry}`]
            const missing = options.missing?.includes(`${parts[1]}/${entry}`)
            if (delay) {
                await new Promise(resolve => setTimeout(resolve, delay))
                // The test may be over by then.
                return (missing ? notFound() : serve(inside(root, entry)))?.catch(() => {})
            }
            if (missing) return notFound()
            return serve(inside(root, entry))
        }
        return notFound()
    })

    await page.goto(READER_URL)
    if (options.ready !== false) await waitFor(page, 'ready')
    return { outside, requested }
}

export const messages = (page: Page): Promise<Message[]> =>
    page.evaluate(() => (window as any).lirelleMessages as Message[])

export const mark = (page: Page): Promise<number> =>
    page.evaluate(() => (window as any).lirelleMessages.length as number)

/** The first message of a type at or after an index of the recording. */
export const waitFor = async (page: Page, type: string, from = 0): Promise<Message> => {
    const handle = await page.waitForFunction(
        ([t, f]) => (window as any).lirelleMessages?.slice(f as number).find((m: any) => m.type === t),
        [type, from] as const)
    return await handle.jsonValue() as Message
}

/** Messages of a type recorded at or after an index. */
export const since = async (page: Page, type: string, from: number): Promise<Message[]> =>
    (await messages(page)).slice(from).filter(m => m.type === type)

export const last = async (page: Page, type: string): Promise<Message> => {
    const all = (await messages(page)).filter(m => m.type === type)
    expect(all.length, `a ${type} message`).toBeGreaterThan(0)
    return all[all.length - 1]
}

export const send = (page: Page, message: Message) =>
    page.evaluate(json => (window as any).lirelle.receive(json), JSON.stringify(message))

/**
 * The relocation that follows an index of the recording, once the page has gone quiet. A turn of the page the
 * page announced (`turning`) is answered by one relocation, which is waited for however late it comes: a slow
 * machine may let the page go quiet before it.
 */
export const relocatedAfter = async (page: Page, from: number): Promise<Message> => {
    await waitFor(page, 'relocated', from)
    const turnAt = (await messages(page)).map(m => m.type).lastIndexOf('turning')
    if (turnAt >= from) await waitFor(page, 'relocated', turnAt + 1)
    let count = -1
    for (;;) {
        const now = await mark(page)
        if (now === count) break
        count = now
        await page.waitForTimeout(250)
    }
    const all = await since(page, 'relocated', from)
    return all[all.length - 1]
}

/** The relocation that answers a move of the host made after an index of the recording: there is exactly one. */
export const hostAnswerAfter = async (page: Page, from: number): Promise<Message> => {
    const handle = await page.waitForFunction(
        f => (window as any).lirelleMessages?.slice(f).find((m: any) => m.type === 'relocated' && m.byHost),
        from)
    return await handle.jsonValue() as Message
}

/**
 * Sends a message and waits for the relocation it causes. A move of the host (`goTo`) is answered by one
 * relocation of its own, which is waited for: a busy machine may let the page go quiet before it comes. Other
 * moves are taken as done once the page has gone quiet.
 */
export const sendAndRelocate = async (page: Page, message: Message): Promise<Message> => {
    const from = await mark(page)
    await send(page, message)
    if (message.type === 'goTo') return hostAnswerAfter(page, from)
    return relocatedAfter(page, from)
}

export type Format = 'epub' | 'mobi' | 'fb2'

export type OpenOptions = {
    cfi?: string | null, fraction?: number | null, settings?: Partial<Settings>,
    /** How the book is given to the page: entry by entry ('epub', the default), or as one file. */
    format?: Format,
}

export const openBook = async (page: Page, name: string, options: OpenOptions = {}) => {
    const from = await mark(page)
    await send(page, {
        type: 'open',
        base: `/book/${name}/`,
        cfi: options.cfi ?? null,
        fraction: options.fraction ?? null,
        ...(options.format ? { format: options.format } : {}),
        settings: { ...SETTINGS, ...options.settings },
    })
    const opened = await waitFor(page, 'opened', from)
    const relocated = await relocatedAfter(page, from)
    return { opened, relocated }
}

/** Lets pending animations, snaps and debounced handlers finish. */
export const settle = (page: Page, ms = 700) => page.waitForTimeout(ms)

/** The document of the section on screen (a blob: frame made by the engine). */
export const contentFrame = async (page: Page): Promise<Frame> => {
    await expect.poll(() => page.frames().filter(f => f.url().startsWith('blob:')).length).toBeGreaterThan(0)
    const frames = page.frames().filter(f => f.url().startsWith('blob:'))
    return frames[frames.length - 1]
}

/** A one-finger drag through raw touch events. */
export const drag = async (page: Page, from: [number, number], to: [number, number], steps = 12) => {
    const cdp = await page.context().newCDPSession(page)
    const point = (x: number, y: number) => [{ x: Math.round(x), y: Math.round(y) }]
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: point(...from) })
    for (let i = 1; i <= steps; i++) {
        const x = from[0] + (to[0] - from[0]) * i / steps
        const y = from[1] + (to[1] - from[1]) * i / steps
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: point(x, y) })
        await page.waitForTimeout(16)
    }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await cdp.detach()
}

/** Taps the middle of an element of the section on screen. */
export const tapElement = async (page: Page, selector: string) => {
    const frame = await contentFrame(page)
    const box = await frame.locator(selector).boundingBox()
    expect(box, `${selector} is laid out`).not.toBeNull()
    await page.touchscreen.tap(box!.x + box!.width / 2, box!.y + box!.height / 2)
}

export type Clip = { x: number, y: number, width: number, height: number }

/** The pixels of a part of the page as drawn, row by row, as [r, g, b] triples. */
export const pixelsIn = async (page: Page, clip: Clip): Promise<number[][]> => {
    const shot = await page.screenshot({ clip })
    const helper = await page.context().browser()!.newPage()
    try {
        return await helper.evaluate(async ([base64, width, height]) => {
            const image = new Image()
            image.src = 'data:image/png;base64,' + base64
            await image.decode()
            const canvas = document.createElement('canvas')
            canvas.width = width as number
            canvas.height = height as number
            const context = canvas.getContext('2d')!
            context.drawImage(image, 0, 0)
            const data = context.getImageData(0, 0, width as number, height as number).data
            const out: number[][] = []
            for (let i = 0; i < data.length; i += 4) out.push([data[i], data[i + 1], data[i + 2]])
            return out
        }, [shot.toString('base64'), clip.width, clip.height] as const)
    } finally {
        await helper.close()
    }
}

/** The colour of one pixel of the page as drawn, as [r, g, b]. */
export const pixelAt = async (page: Page, x: number, y: number): Promise<number[]> =>
    (await pixelsIn(page, { x: Math.round(x), y: Math.round(y), width: 1, height: 1 }))[0]

// ---------------------------------------------------------------- the note panel

/**
 * Where a frame is drawn: by a view of the note panel ('note'), by one the panel let go of and that waits to be
 * disposed ('retired'), by the main view (null), or nowhere any more ('gone').
 */
export const notePanelPlace = (frame: Frame): Promise<'note' | 'retired' | 'gone' | null> => frame.evaluate(() => {
    let el: Element | null = window.frameElement
    let retired = false
    while (el) {
        if (el.localName === 'foliate-view' && el.hasAttribute('data-lirelle-retired')) retired = true
        if (el.closest('#note-panel')) return retired ? 'retired' : 'note'
        const root = el.getRootNode() as ShadowRoot
        el = root.host ?? null
    }
    return null
}).catch(() => 'gone' as const)

const blobFrames = (page: Page) => page.frames().filter(f => f.url().startsWith('blob:') && !f.isDetached())

/** The document of the main view. */
export const mainFrame = async (page: Page): Promise<Frame> => {
    let found: Frame | null = null
    await expect.poll(async () => {
        for (const frame of blobFrames(page)) if (await notePanelPlace(frame) === null) found = frame
        return !!found
    }).toBe(true)
    return found!
}

/** The document of the note shown in the panel. */
export const noteFrame = async (page: Page): Promise<Frame> => {
    let found: Frame | null = null
    await expect.poll(async () => {
        for (const frame of blobFrames(page)) if (await notePanelPlace(frame) === 'note') found = frame
        return !!found
    }).toBe(true)
    return found!
}

/** How many documents the note panel holds, shown or waiting to be disposed. */
export const noteFrameCount = async (page: Page) => {
    let count = 0
    for (const frame of blobFrames(page)) {
        const place = await notePanelPlace(frame)
        if (place === 'note' || place === 'retired') count++
    }
    return count
}
