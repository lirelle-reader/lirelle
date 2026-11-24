// The reader page: drives foliate-js and talks to the host over a JSON bridge.
//
// Host -> page: window.lirelle.receive(jsonString)
// Page -> host: window.lirelleHost.postMessage(jsonString)
//
// The message and field names are a contract shared with the Kotlin side
// (feature/reader/src/main/kotlin/app/tfdn/lirelle/feature/reader/bridge/Messages.kt).
// `open` carries a `format`: `epub` (the default) for a book read entry by entry
// from `<base>`, `mobi` or `fb2` for one read whole from `<base>file`
// (formats.js); a file the engine cannot read is the error `cannot_open`.

import { attachArrival, dropFlash, flashTarget } from './arrival.js'
import {
    applyFindSettings, attachFind, cancelSearch, clearSearch, showSearchResult, startSearch, stopSearch,
} from './find.js'
import {
    applyMarkSettings, attachMarks, bookmarksOn, clearMarks, highlightAt, putHighlight, removeHighlight,
    setBookmarks, setHighlights,
} from './marks.js'
import {
    applyNarrationSettings, attachNarration, narrationCfi, narrationClear, narrationFind, narrationMark,
    narrationRelocated, resetNarration,
} from './narration.js'
import {
    applyNoteSettings, attachNotes, closeNote, dropLoadingNote, isNoteReference, noteLink, noteOpen,
} from './notes.js'
import {
    attachSelection, clearSelection, holdSelection, resetSelection, selectionLive, selectionRelocated, selectionShown,
    selectionTouchEnded, selectionTouchStarted, watchSelection,
} from './selection.js'
import { openWholeBook, UnreadableBook } from './formats.js'
import { dropResourceHints } from './hints.js'
import { contentsEntries, shortLabel } from './labels.js'
import { clamp01, isColour } from './values.js'

const TAP_MAX_MS = 500
const TAP_MAX_PX = 10
const SWIPE_MIN_PX = 50
const EDGE_STRIP = 0.15
const NAVIGATION_TIMEOUT_MS = 8000
const LINK_INTENT_MS = 3000
const MESSAGE_MAX = 200
const NOTE_LABEL_MAX = 200
const BAND_MARGIN_PX = 24
const RELOCATION_QUIET_MS = 40
const SCROLL_QUIET_MS = 400
// How long after a move by the host, in scrolled layout, the end of its scroll can still be reported.
const HOST_SCROLL_SLACK_MS = 300
// How long after a move by the host its own relocation is awaited before the place on screen is reported again.
const HOST_ANSWER_WAIT_MS = 120
const SCROLL_END_PX = 2
// How long a page turned by a swipe is awaited (the engine's animation, or a section to load) before the place on
// screen is reported again.
const SWIPE_TURN_WAIT_MS = 3000
// How long after a drag in pages the engine is left to snap the page, a live selection or not.
const SNAP_FREE_MS = 600
const RECOVER_AFTER_MS = 150
const RECOVER = 'recoverFromFailedSection'
const USER_REASONS = new Set(['snap', 'page', 'scroll'])
// How the host gives a book: an EPUB entry by entry, a Kindle book (`mobi`) and a FictionBook as one file.
const BOOK_FORMATS = ['epub', 'mobi', 'fb2']
// How long a touch that neither moves nor ends still holds the page from the narration's moves: one whose end the
// page was never told (the system took it) does not hold it for good.
const TOUCH_HOLD_MS = 3000

const FONTS_BASE = new URL('/fonts/', location.href).href
const FONTS = {
    serif: { family: 'LirelleSerif', generic: 'serif' },
    sans: { family: 'LirelleSans', generic: 'sans-serif' },
    dyslexic: { family: 'LirelleDyslexic', generic: 'sans-serif' },
}
const FONT_FACES = [
    { file: 'Regular', weight: 'normal', style: 'normal' },
    { file: 'Italic', weight: 'normal', style: 'italic' },
    { file: 'Bold', weight: 'bold', style: 'normal' },
]

const DEFAULT_SETTINGS = {
    background: '#ffffff',
    foreground: '#000000',
    link: '#0000ee',
    dark: false,
    font: 'book',
    fontSizePx: 18,
    lineHeight: 1.5,
    marginPercent: 7,
    align: 'book',
    hyphenate: true,
    flow: 'paginated',
    maxColumns: 1,
    brightnessEdge: 'left',
    highlightBlend: 'multiply',
    highlightOpacity: 0.3,
    searchColor: '#d97706',
    footnotePanel: true,
    // The note panel's texts, in the language of the app.
    noteLabels: { goTo: 'Go to the note', title: 'Note' },
}

const state = {
    view: null,
    book: null,
    settings: { ...DEFAULT_SETTINGS },
    appliedCss: null,
    appliedAttributes: {},
    opening: false,
    pendingRelocation: null,
    pendingMessage: null,
    relocationTimer: 0,
    unplaced: false,
    relocations: 0,
    scrollTimer: 0,
    lastReason: null,
    lastCfi: null,
    lastIndex: null,
    lastSent: null,
    moved: false,
    intent: 0,
    intentUntil: 0,
    // Moves asked for by the host in progress, and until when their trailing relocations are still theirs.
    hostMoves: 0,
    hostMoveUntil: 0,
    // Moves by the host done and not answered yet with a relocation `byHost`, and the timer that answers them anyway.
    hostAnswers: 0,
    hostAnswerTimer: 0,
    // Moves made to follow the narration in progress, and until when their trailing relocations are still theirs.
    narrationMoves: 0,
    narrationUntil: 0,
    sectionFailure: null,
    gesture: null,
    unsupported: null,
    // A turn of the page announced to the host and not answered yet with a relocation: { timer }.
    turn: null,
    // The engine's turn under way (a page turned, or a scroll nudged by a pixel), settled either way; null when
    // none. A turn locks the engine, which then drops any other navigation without a word.
    navigating: null,
    // Navigations begun since the book opened, and their count when a section last failed to load.
    navigations: 0,
    failedAt: 0,
    // In pages, the offset of the engine's scroller at its last relocation, and until when a drag's snap is let be.
    pageOffset: null,
    freeUntil: 0,
    // The section on screen and its visible range, as last reported.
    visible: null,
    // One label per section, as sent with `opened`.
    sectionLabels: [],
}

// ---------------------------------------------------------------- bridge

const post = message => {
    try {
        window.lirelleHost.postMessage(JSON.stringify(message))
    } catch (e) {
        console.warn('No host to post to', e)
    }
}

const shortMessage = e => {
    const text = String((e && e.message) || e || 'Unknown error').split('\n')[0]
    return text.length > MESSAGE_MAX ? text.slice(0, MESSAGE_MAX) : text
}

const postError = (code, e) => post({ type: 'error', code, message: shortMessage(e) })

// ---------------------------------------------------------------- engine check

const missingFeature = () => {
    const checks = {
        'Object.groupBy': () => typeof Object.groupBy === 'function',
        'Map.groupBy': () => typeof Map.groupBy === 'function',
        'Array.prototype.findLastIndex': () => typeof Array.prototype.findLastIndex === 'function',
        'Array.prototype.at': () => typeof Array.prototype.at === 'function',
        'ResizeObserver': () => typeof ResizeObserver === 'function',
        'customElements': () => typeof customElements === 'object',
        'visualViewport': () => typeof visualViewport === 'object',
        'crypto.subtle': () => typeof crypto === 'object' && !!crypto.subtle,
        'CSS nesting': () => CSS.supports('selector(&)'),
        'CSS container queries': () => CSS.supports('container-type', 'size'),
    }
    for (const [name, check] of Object.entries(checks)) {
        try {
            if (!check()) return name
        } catch (e) {
            return name
        }
    }
    return null
}

// ---------------------------------------------------------------- settings

const colour = (value, fallback) =>
    isColour(value) ? value.toLowerCase() : fallback

const number = (value, fallback, min, max) =>
    typeof value === 'number' && isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback

const oneOf = (value, allowed, fallback) => allowed.includes(value) ? value : fallback

const label = (value, fallback) => {
    const text = typeof value === 'string' ? value.trim() : ''
    return text && text.length <= NOTE_LABEL_MAX ? text : fallback
}

const noteLabels = raw => {
    const labels = raw && typeof raw === 'object' ? raw : {}
    const d = DEFAULT_SETTINGS.noteLabels
    return { goTo: label(labels.goTo, d.goTo), title: label(labels.title, d.title) }
}

const cleanSettings = raw => {
    const s = raw && typeof raw === 'object' ? raw : {}
    const d = DEFAULT_SETTINGS
    return {
        background: colour(s.background, d.background),
        foreground: colour(s.foreground, d.foreground),
        link: colour(s.link, d.link),
        dark: s.dark === true,
        font: oneOf(s.font, ['book', 'serif', 'sans', 'dyslexic'], d.font),
        fontSizePx: Math.round(number(s.fontSizePx, d.fontSizePx, 6, 96)),
        lineHeight: number(s.lineHeight, d.lineHeight, 0.8, 4),
        marginPercent: number(s.marginPercent, d.marginPercent, 0, 40),
        align: oneOf(s.align, ['book', 'justify', 'start'], d.align),
        hyphenate: s.hyphenate !== false,
        flow: oneOf(s.flow, ['paginated', 'scrolled'], d.flow),
        maxColumns: s.maxColumns === 2 ? 2 : 1,
        brightnessEdge: oneOf(s.brightnessEdge, ['left', 'right', 'off'], d.brightnessEdge),
        highlightBlend: oneOf(s.highlightBlend, ['multiply', 'normal'], d.highlightBlend),
        highlightOpacity: number(s.highlightOpacity, d.highlightOpacity, 0.1, 1),
        searchColor: colour(s.searchColor, d.searchColor),
        footnotePanel: s.footnotePanel !== false,
        noteLabels: noteLabels(s.noteLabels),
    }
}

const fontFaces = ({ family }) => FONT_FACES.map(face => `@font-face {
    font-family: "${family}";
    src: url("${FONTS_BASE}${family}-${face.file}.woff2") format("woff2");
    font-weight: ${face.weight};
    font-style: ${face.style};
    font-display: swap;
}`).join('\n')

// The style sheet injected into every section. `font: "book"` and
// `align: "book"` add no rule at all, and nothing here sizes, filters or
// recolours images.
const BLOCKS = ':is(p, div, h1, h2, h3, h4, h5, h6, li, blockquote, dl, dd, dt, section, article, '
    + 'aside, header, footer, main, nav, figure, figcaption, pre, ul, ol)'

const buildCss = s => {
    const text = ':is(p, li, blockquote, dd)'
    const rules = []
    const font = FONTS[s.font]
    if (font) rules.push(fontFaces(font))
    rules.push(`html {
    color-scheme: ${s.dark ? 'dark' : 'light'};
    font-size: ${s.fontSizePx}px !important;
    line-height: ${s.lineHeight} !important;
    /* A phone's web view enlarges the text of a document without a viewport of its own, after
       the columns were measured: lines then run past the page. */
    -webkit-text-size-adjust: 100% !important;
    text-size-adjust: 100% !important;
    hanging-punctuation: allow-end last;
    orphans: 2;
    widows: 2;
    -webkit-user-select: text !important;
    user-select: text !important;
    -webkit-touch-callout: none;
    -webkit-tap-highlight-color: transparent;
}
body { font-size: 1rem !important; }
html, body {
    color: ${s.foreground} !important;
    background: ${s.background} !important;
}
a:any-link { color: ${s.link} !important; }
${text} {
    line-height: ${s.lineHeight} !important;
    hyphens: ${s.hyphenate ? 'auto' : 'manual'} !important;
    -webkit-hyphens: ${s.hyphenate ? 'auto' : 'manual'} !important;
}
pre { white-space: pre-wrap !important; }
/* Nothing runs past its column: long words and addresses break, and a block is never wider than
   the column, whatever width the book gives it. Pictures are sized by the engine. */
body, body * { overflow-wrap: break-word !important; }
${BLOCKS} { max-width: 100% !important; }
/* A table cannot be narrower than its longest word: in a cell a word may break anywhere, so a
   table too wide for the column is squeezed into it instead of running into the next page. */
table { max-width: 100% !important; }
:is(td, th) { overflow-wrap: anywhere !important; }`)
    // Plain black-on-white keeps the publisher's own colours.
    if (s.dark || s.background !== '#ffffff') rules.push(
        `body *:not(img, svg, svg *, video, picture, canvas) {
    color: inherit !important;
    border-color: currentColor !important;
    background-color: transparent !important;
}`)
    if (font) rules.push(`body, body *:not(pre, pre *, code, kbd, samp, tt) {
    font-family: "${font.family}", ${font.generic} !important;
}`)
    // Elements the book itself centres or pushes to the end are marked with
    // `data-lirelle-keep` when the section loads, and keep their alignment.
    if (s.align !== 'book') rules.push(
        `html[data-lirelle-ready] ${text}:not([data-lirelle-keep]) {
    text-align: ${s.align === 'justify' ? 'justify' : 'start'} !important;
}`)
    return rules.join('\n')
}

const layoutAttributes = s => ({
    'margin': `${BAND_MARGIN_PX}px`,
    'gap': `${s.marginPercent}%`,
    'max-column-count': String(s.maxColumns),
})

const applySettings = raw => {
    const s = cleanSettings(raw)
    state.settings = s
    document.documentElement.style.background = s.background
    document.body.style.background = s.background
    applyMarkSettings(s)
    applyNarrationSettings(s)
    applyFindSettings(s)
    applyNoteSettings(s)
    const { view } = state
    const renderer = view && view.renderer
    if (!renderer || view.isFixedLayout) return

    const css = buildCss(s)
    if (css !== state.appliedCss) {
        renderer.setStyles(css)
        state.appliedCss = css
    }
    const wanted = layoutAttributes(s)
    let changed = false
    for (const [name, value] of Object.entries(wanted)) {
        if (state.appliedAttributes[name] === value) continue
        renderer.setAttribute(name, value)
        state.appliedAttributes[name] = value
        changed = true
    }
    // `flow` goes last: it is the attribute that makes the paginator lay out again.
    if (changed || state.appliedAttributes.flow !== s.flow) {
        renderer.setAttribute('flow', s.flow)
        state.appliedAttributes.flow = s.flow
    }
}

// Marks what the book aligns itself, before our alignment rule switches on.
const markOwnAlignment = doc => {
    try {
        const win = doc.defaultView
        for (const el of doc.querySelectorAll('p, li, blockquote, dd')) {
            const align = win.getComputedStyle(el).textAlign
            if (align === 'center' || align === 'right' || align === 'end'
                || align === '-webkit-center' || align === '-webkit-right')
                el.setAttribute('data-lirelle-keep', '')
        }
    } catch (e) {
        console.warn(e)
    }
    doc.documentElement.setAttribute('data-lirelle-ready', '')
}

// ---------------------------------------------------------------- content inside the column

// A book's own style sheet can push a block out of its column: a word
// processor's export gives paragraphs `margin-right: -48px`, a first line a
// negative indent, a box a width in pixels. In pages, what leaves the column
// is drawn on the next page. No rule of a style sheet can undo that without
// flattening the indents a book is right to ask for, so each document is
// walked once when it loads, before the engine lays it out and counts pages.
//
// What is neutralised, with an inline `!important` declaration:
//  - a negative left or right margin that pulls the block further out than
//    the room its ancestors leave on that side (their margins and paddings);
//  - a negative `text-indent` larger than the room on the side the lines
//    start from, the block's own padding and margin included, so that a
//    hanging indent (`padding-left: 2em; text-indent: -2em`) is kept;
//  - `white-space: nowrap` on a block;
//  - a `min-width`, which would win over the `max-width` of the style sheet:
//    it is capped at the width of the column;
//  - the box model of a block with a width of its own, so that its padding
//    counts inside that width.
// Fixed-layout books are never walked, nor is anything inside an `svg`.
const WALKED = 'body, p, div, h1, h2, h3, h4, h5, h6, li, blockquote, dl, dd, dt, section, article, '
    + 'aside, header, footer, main, nav, figure, figcaption, table, td, th, pre, ul, ol'
const SLACK_PX = 0.5

// A computed length in pixels. The frame is not laid out yet, so a percentage
// is still a percentage: a negative one counts as leaving the column, a
// positive one as no room at all.
const lengthOf = value => {
    const n = parseFloat(value)
    if (!(n < 0 || n > 0)) return 0
    if (value.endsWith('px')) return n
    return n < 0 ? -Infinity : 0
}

const keepInsideColumn = doc => {
    const started = performance.now()
    try {
        const win = doc.defaultView
        const fix = (el, name, value) => el.style.setProperty(name, value, 'important')
        // For each element walked, the room its content has before the edge
        // of the column, on the left and on the right.
        const rooms = new Map()
        const none = { left: 0, right: 0 }
        for (const el of doc.querySelectorAll(WALKED)) {
            if (el.namespaceURI !== 'http://www.w3.org/1999/xhtml' || el.closest('svg')) continue
            const style = win.getComputedStyle(el)
            if (style.writingMode !== 'horizontal-tb') continue
            let parent = el.parentElement
            while (parent && !rooms.has(parent)) parent = parent.parentElement
            const around = (parent && rooms.get(parent)) || none
            const isBody = el === doc.body
            const cell = el.localName === 'td' || el.localName === 'th'
            const room = {}
            for (const side of ['left', 'right']) {
                // The engine sets the margins of the body itself, and the
                // margins of a cell do nothing.
                let margin = isBody || cell ? 0 : lengthOf(style.getPropertyValue(`margin-${side}`))
                if (margin < 0 && around[side] + margin < -SLACK_PX) {
                    fix(el, `margin-${side}`, '0')
                    margin = 0
                }
                // A cell starts a column of its own inside the table.
                room[side] = (cell ? 0 : around[side]) + margin
                    + lengthOf(style.getPropertyValue(`padding-${side}`))
            }
            rooms.set(el, room)
            if (isBody) continue
            const indent = lengthOf(style.textIndent)
            if (indent < 0 && room[style.direction === 'rtl' ? 'right' : 'left'] + indent < -SLACK_PX)
                fix(el, 'text-indent', '0')
            if (style.whiteSpace === 'nowrap') fix(el, 'white-space', 'normal')
            if (cell) continue
            const minWidth = style.minWidth
            const hasMin = minWidth !== 'auto' && minWidth !== '0px' && minWidth !== '0%'
            if (hasMin) fix(el, 'min-width', `min(${minWidth}, 100%)`)
            if ((hasMin || style.width !== 'auto') && el.localName !== 'table') fix(el, 'box-sizing', 'border-box')
        }
    } catch (e) {
        console.warn(e)
    }
    // Kept where the page's own timings are: the cost of the walk on a long
    // chapter can be read from a device.
    try {
        performance.measure('lirelle-inside-column', { start: started })
    } catch (e) {
        // Timings are a convenience.
    }
}

// ---------------------------------------------------------------- cover page

// A document whose body holds one picture and no text, such as the cover that
// opens most books: the engine draws it at the top of the page, with the
// empty space below. Marking the document lets the style sheet centre it
// (see buildCss); nothing else in the document is changed.
const MEDIA_BESIDE_A_COVER = 'video, audio, canvas, object, embed, iframe, table, hr, input, button, math'

const withoutSpace = text => (text ?? '').replace(/\s+/g, '')

const markCover = doc => {
    try {
        const { body } = doc
        if (!body || body.querySelector(MEDIA_BESIDE_A_COVER)) return
        const pictures = [...body.querySelectorAll('img, svg')].filter(el => !el.parentElement.closest('svg'))
        if (pictures.length !== 1) return
        const [picture] = pictures
        // The text of an `svg` (a title, a description) is not text of the page.
        const own = picture.localName === 'svg' ? withoutSpace(picture.textContent) : ''
        if (withoutSpace(body.textContent) !== own) return
        const style = doc.defaultView.getComputedStyle(body)
        if (style.writingMode !== 'horizontal-tb') return
        doc.documentElement.setAttribute('data-lirelle-cover', '')
    } catch (e) {
        console.warn(e)
    }
}

// ---------------------------------------------------------------- book loading

const makeLoader = async base => {
    const response = await fetch(base + 'index.json')
    if (!response.ok) throw new Error(`Book index not available (${response.status})`)
    const index = await response.json()
    if (!index || typeof index.entries !== 'object' || !index.entries)
        throw new Error('Book index is malformed')
    const sizes = new Map(Object.entries(index.entries))
    const urlOf = name => base + name.split('/').map(encodeURIComponent).join('/')
    const load = async (name, read) => {
        if (!sizes.has(name)) return null
        try {
            const res = await fetch(urlOf(name))
            return res.ok ? await read(res) : null
        } catch (e) {
            console.warn(e)
            return null
        }
    }
    return {
        loadText: name => load(name, res => res.text()),
        loadBlob: name => load(name, res => res.blob()),
        getSize: name => Number(sizes.get(name)) || 0,
    }
}

// One label per section: the contents entry the section belongs to. An entry
// pointing into the section counts first; otherwise the last entry before it;
// null before the first entry.
const sectionLabels = async (book, entries) => {
    const first = new Map()
    const last = new Map()
    for (const entry of entries) {
        let index = null
        try {
            // A Kindle book of the newer kind answers in its own time.
            const resolved = await book.resolveHref(entry.href)
            if (resolved && Number.isInteger(resolved.index) && resolved.index >= 0) index = resolved.index
        } catch (e) {
            // An entry that points nowhere labels nothing.
        }
        if (index === null) continue
        const label = entry.label || null
        if (!first.has(index)) first.set(index, label)
        last.set(index, label)
    }
    let carried = null
    return book.sections.map((_, i) => {
        const label = first.has(i) ? first.get(i) : carried
        if (last.has(i)) carried = last.get(i)
        return label
    })
}

const bookTitle = book => {
    let title = book.metadata && book.metadata.title
    if (title && typeof title === 'object') title = Object.values(title)[0]
    title = typeof title === 'string' ? title.trim() : ''
    return title || null
}

const isLinear = section => section && section.linear !== 'no'

// ---------------------------------------------------------------- sections

// A section whose document cannot be loaded makes the paginator show nothing
// and say nothing. Wrapping `load` lets us notice.
//
// The note panel loads sections in a view of its own, often the very section
// on screen. The engine's loader counts a section's users badly: a second user
// of a loaded section is counted only the first time, so the second note
// closed would revoke the document of the main view, and every resource it
// still needs with it. Each section therefore counts its users here: the
// engine loads it for the first and unloads it after the last, and the others
// share its address.
const watchSections = book => {
    for (const [index, section] of book.sections.entries()) {
        const load = section.load
        // A section of a Kindle book that holds nothing has nothing to load, and the engine never asks it to.
        if (typeof load !== 'function') continue
        // Only an EPUB gives its sections back: the other books keep theirs until they are closed.
        const unload = typeof section.unload === 'function' ? section.unload : () => {}
        let users = 0
        // The address of the section's document, while it is loaded or loading.
        let shared = null
        const loadOnce = async () => {
            let src = null
            let cause = null
            try {
                src = await load()
            } catch (e) {
                cause = e
            }
            // The book was closed meanwhile: what arrives late is let go, and is no failure of the next book.
            if (state.book !== book) {
                if (src) unload()
                throw new Error(`Section ${index} of a closed book`)
            }
            if (src) return src
            const error = cause || new Error(`Section ${index} cannot be loaded`)
            state.sectionFailure = { index, error }
            if (!state.opening) {
                postError('section_failed', error)
                state.failedAt = state.navigations
                setTimeout(() => receive({ type: RECOVER }), RECOVER_AFTER_MS)
            }
            throw error
        }
        section.load = async () => {
            for (;;) {
                if (!shared) {
                    const started = loadOnce()
                    shared = started
                    started.catch(() => {
                        if (shared === started) shared = null
                    })
                }
                const pending = shared
                const src = await pending
                if (state.book !== book) throw new Error(`Section ${index} of a closed book`)
                // Let go of by its last user while this one waited: loaded again.
                if (shared === pending) {
                    users++
                    return src
                }
            }
        }
        section.unload = () => {
            if (users === 0 || --users > 0) return
            shared = null
            unload()
        }
        // For the tests: how many views hold the section loaded.
        Object.defineProperty(section, 'lirelleUsers', { get: () => users })
    }
}

// A failed section leaves the paginator without a current section, and page
// turns stop working. Going back to the last good position repairs it.
const recover = async () => {
    const { view, lastCfi } = state
    // A navigation asked since the failure goes where it was asked: going back would undo it.
    if (!lastCfi || state.navigations !== state.failedAt) return
    try {
        await view.goTo(lastCfi)
    } catch (e) {
        console.warn(e)
    }
}

const closeBook = () => {
    const { view, book } = state
    // The note view shows the same book: it goes first.
    closeNote({ focus: false })
    try {
        if (view && view.renderer) view.close()
    } catch (e) {
        console.warn(e)
    }
    try {
        if (book) book.destroy()
    } catch (e) {
        console.warn(e)
    }
    clearTimeout(state.relocationTimer)
    clearTimeout(state.scrollTimer)
    clearTimeout(state.hostAnswerTimer)
    clearTimeout(state.turn && state.turn.timer)
    clearMarks()
    resetNarration()
    resetSelection()
    dropFlash()
    Object.assign(state, {
        relocations: 0, scrollTimer: 0,
        book: null, appliedCss: null, appliedAttributes: {}, pendingRelocation: null,
        pendingMessage: null, relocationTimer: 0, unplaced: false,
        lastReason: null, lastCfi: null, lastIndex: null, lastSent: null, moved: false, intent: 0,
        intentUntil: 0, hostMoveUntil: 0, hostAnswers: 0, hostAnswerTimer: 0, narrationUntil: 0,
        sectionFailure: null, gesture: null, navigating: null, navigations: 0, failedAt: 0,
        visible: null, sectionLabels: [], turn: null, pageOffset: null, freeUntil: 0,
    })
}

// ---------------------------------------------------------------- position

const validIndex = index => Number.isInteger(index) && index >= 0
    && index < state.book.sections.length

// True when the target was shown.
const show = async target => {
    const { view } = state
    state.sectionFailure = null
    const resolved = view.resolveNavigation(target)
    if (!resolved || !validIndex(resolved.index)) return false
    const done = await view.goTo(target)
    return !!done && !state.sectionFailure
}

const restorePosition = async ({ cfi, fraction }) => {
    const { book } = state
    if (typeof cfi === 'string' && cfi && await show(cfi).catch(() => false)) return
    if (typeof fraction === 'number' && fraction > 0 && fraction <= 1
        && await show({ fraction }).catch(() => false)) return
    const first = book.sections.findIndex(isLinear)
    if (await show(first < 0 ? 0 : first)) return
    throw (state.sectionFailure && state.sectionFailure.error)
        || new Error('The book has nothing to show')
}

const atEnd = index => {
    const { view, book, settings } = state
    const { sections } = book
    if (!isLinear(sections[index])) return false
    if (sections.slice(index + 1).some(isLinear)) return false
    const { renderer } = view
    if (view.isFixedLayout) return true
    if (settings.flow === 'scrolled') return renderer.viewSize - renderer.end <= SCROLL_END_PX
    return renderer.page >= renderer.pages - 2
}

// The start of the visible range, moved past white space to the first
// character that is really drawn on the page. White space at a page break
// has no box of its own, and a position on it would resolve to the page before.
const firstVisiblePoint = visible => {
    const point = visible.cloneRange()
    point.collapse(true)
    let node = point.startContainer
    if (node.nodeType !== Node.TEXT_NODE) return point
    let offset = point.startOffset
    const walker = node.ownerDocument.createTreeWalker(node.ownerDocument.body, NodeFilter.SHOW_TEXT)
    walker.currentNode = node
    while (node) {
        const rest = node.data.slice(offset).search(/\S/)
        if (rest >= 0) {
            if (visible.comparePoint(node, offset + rest) > 0) break
            point.setStart(node, offset + rest)
            point.collapse(true)
            break
        }
        node = walker.nextNode()
        offset = 0
    }
    return point
}

const pointCfi = (detail, index) => {
    if (!detail.range) return detail.cfi
    return state.view.getCFI(index, firstVisiblePoint(detail.range))
}

// The engine relocates several times in a row while a section settles (first
// with the scroll offset of the section it replaces, then once per layout
// pass). Only the last of a burst is sent.
const flushRelocation = () => {
    clearTimeout(state.relocationTimer)
    state.relocationTimer = 0
    const pending = state.pendingMessage
    state.pendingMessage = null
    if (!pending || !state.book) return
    const { message, byUser, visible, byNarration } = pending
    if (byUser && state.lastCfi !== null && message.cfi !== state.lastCfi) state.moved = true
    state.lastCfi = message.cfi
    state.lastIndex = message.sectionIndex
    state.visible = visible
    // `moved` is "the user moved since the book opened"; `userMove` says whether this very relocation is theirs (a
    // relayout after a resize is not, wherever it lands).
    message.moved = state.moved
    message.userMove = !!byUser
    message.bookmarkIds = bookmarksOn(visible)
    postRelocation(message, takeHostAnswer(), byNarration)
    narrationRelocated()
}

// A relocation that says nothing new (a second layout pass, a drag that snaps
// back) is not repeated, unless it answers a move by the host or a turn of the
// page announced: every one of them is answered, wherever it led. `byNarration`
// marks a move the page made to follow the narration.
const postRelocation = (message, byHost = false, byNarration = false) => {
    message.byHost = false
    message.byNarration = false
    // A place sent again (new bookmarks, an answer repeated for the host) is nobody's move.
    message.userMove = message.userMove === true
    // Whose move it was is not news: the same place again is not sent for that alone.
    const json = JSON.stringify({ ...message, userMove: undefined })
    const { turn } = state
    if (json === state.lastSent && !byHost && !byNarration && !turn) return
    if (turn) {
        clearTimeout(turn.timer)
        state.turn = null
    }
    state.lastSent = json
    post(byHost || byNarration ? { ...message, byHost, byNarration } : message)
}

// Whether the relocation about to be sent answers a move by the host.
const takeHostAnswer = () => {
    if (state.hostAnswers === 0) return false
    state.hostAnswers--
    if (state.hostAnswers > 0) awaitHostAnswer()
    return true
}

// A move by the host is done. Its own relocation answers it; when none comes
// (the engine had nothing to move, or reported it already), the place on
// screen is reported again.
const hostMoveDone = () => {
    state.hostAnswers++
    awaitHostAnswer()
}

const awaitHostAnswer = () => {
    clearTimeout(state.hostAnswerTimer)
    state.hostAnswerTimer = setTimeout(answerHost, HOST_ANSWER_WAIT_MS)
}

const answerHost = () => {
    state.hostAnswerTimer = 0
    if (state.hostAnswers === 0 || !state.book) return
    // A relocation on its way answers it; so does one the engine may still report for the move (the end of a
    // scroll, late on a busy device): the place on screen is said again only once none can come.
    if (state.pendingMessage || Date.now() < state.hostMoveUntil) return awaitHostAnswer()
    repeatForHost()
}

// Answers every move by the host still waiting, with the place on screen.
const repeatForHost = () => {
    clearTimeout(state.hostAnswerTimer)
    state.hostAnswerTimer = 0
    while (state.hostAnswers > 0) {
        const message = state.lastSent ? JSON.parse(state.lastSent) : freshRelocation()
        // The engine has never said where it is: there is nothing to answer with.
        if (!message) {
            state.hostAnswers = 0
            return
        }
        state.hostAnswers--
        postRelocation(message, true)
    }
}

// The place on screen as the engine last reported it, for a page that has reported nothing yet.
const freshRelocation = () => {
    const detail = state.view.lastLocation
    const index = detail && detail.section && detail.section.current
    if (!validIndex(index)) return null
    const message = relocationMessage(detail, index)
    message.moved = state.moved
    message.bookmarkIds = bookmarksOn({ index, range: detail.range || null })
    return message
}

// A move by the user begins: the host's moves still waiting for their answer
// get it first, so that no relocation of the user is taken for one.
const settleHostAnswers = () => {
    if (state.hostAnswers === 0 || !state.book) return
    if (state.pendingMessage) flushRelocation()
    repeatForHost()
}

// The bookmarks changed: the page on screen is reported again if the set it
// holds changed. A relocation still to be sent will carry the new set itself.
const bookmarksChanged = items => {
    setBookmarks(items)
    if (state.pendingMessage || !state.lastSent || !state.visible) return
    const message = JSON.parse(state.lastSent)
    message.bookmarkIds = bookmarksOn(state.visible)
    postRelocation(message)
}

// ---------------------------------------------------------------- turning

// Turns of the page by the user, in pages (not in scrolled layout), are
// announced before they happen: the host hides at once what belongs to the
// page it leaves. The next relocation answers the announcement, even one that
// says nothing new; a turn that cannot happen is answered with the place on
// screen. Moves of the host are no turns.
const turnsArePaged = () => !!state.view && !scrolled()

const beginTurn = () => {
    clearTimeout(state.turn && state.turn.timer)
    state.turn = { timer: 0 }
    post({ type: 'turning' })
}

// Waits for the relocation of the turn announced; when none comes, the place on screen is reported again.
const awaitTurn = ms => {
    const { turn } = state
    if (!turn) return
    clearTimeout(turn.timer)
    turn.timer = setTimeout(() => {
        if (state.turn !== turn) return
        // A relocation on its way answers it.
        if (state.pendingMessage) return awaitTurn(HOST_ANSWER_WAIT_MS)
        turnUndone()
    }, ms)
}

// The turn announced did not happen: the host hears the place on screen again.
const turnUndone = () => {
    const { turn } = state
    if (!turn) return
    clearTimeout(turn.timer)
    if (state.book && state.pendingMessage) flushRelocation()
    if (state.turn !== turn) return
    const message = state.book && (state.lastSent ? JSON.parse(state.lastSent) : freshRelocation())
    if (message) postRelocation(message)
    state.turn = null
}

// The linear section the engine would turn to from the one on screen, one way
// or the other, or null when there is none.
const sectionBeyond = forward => {
    const content = state.view.renderer.getContents()[0]
    const { sections } = state.book
    if (!content || !Number.isInteger(content.index)) return null
    for (let i = content.index + (forward ? 1 : -1); i >= 0 && i < sections.length; i += forward ? 1 : -1)
        if (isLinear(sections[i])) return i
    return null
}

// In scrolled layout the engine turns into the next or previous section from
// either end of the one on screen, even when there is none: it then locks
// itself for good. Whether a step that way would do so.
const stuckInScroll = forward => {
    if (!scrolled()) return false
    const { renderer } = state.view
    const atEdge = forward ? renderer.viewSize - renderer.end <= SCROLL_END_PX : renderer.start <= 0
    return atEdge && sectionBeyond(forward) === null
}

// A turn asked for by `next` or `prev`: announced, then answered once it is done.
// Nothing at all where the engine has nothing to turn to in scrolled layout.
const turnPage = (forward, turn) => {
    if (turnsArePaged()) beginTurn()
    if (forward && atEndNow()) {
        pastEnd()
        return turnUndone()
    }
    if (stuckInScroll(forward)) return
    // A turn locks the engine until it is done: a move of the host waits for it.
    return navigate(() => engineBusy(Promise.resolve().then(turn))).then(() => awaitTurn(HOST_ANSWER_WAIT_MS))
}

// Whether the page on screen is the last of the book, asked when a turn
// forward is attempted: from the section last reported, or about to be.
const atEndNow = () => {
    if (!state.book) return false
    const pending = state.pendingMessage
    const index = pending ? pending.message.sectionIndex : state.lastIndex
    return validIndex(index) && atEnd(index)
}

// A turn forward with nothing left to turn to. The end itself is reported
// first, as a position, when it has not been yet.
const pastEnd = () => {
    flushRelocation()
    post({ type: 'pastEnd' })
}

const onRelocate = detail => {
    if (state.opening) {
        state.pendingRelocation = detail
        return
    }
    const { view, settings } = state
    const index = detail.section && detail.section.current
    if (!validIndex(index)) return
    const paged = !view.isFixedLayout && settings.flow === 'paginated'
    if (paged) {
        // Left over from the section that was replaced: the new one is not placed yet.
        if (state.unplaced && state.lastReason === 'anchor') return
        // The blank page on either side of a section, crossed on the way to the next one.
        const { page, pages } = view.renderer
        if (page < 1 || page > pages - 2) return
    }
    const byUser = state.intent > 0 || Date.now() < state.intentUntil
        || USER_REASONS.has(state.lastReason)
    // A note found for a page that has moved on is not shown.
    if (byUser || hostMoving()) dropLoadingNote()
    selectionRelocated(hostMoving() ? 'host' : byUser ? 'user' : 'engine')
    const message = relocationMessage(detail, index)
    state.pendingMessage = {
        message,
        byUser: byUser || !!(state.pendingMessage && state.pendingMessage.byUser),
        visible: { index, range: detail.range || null },
        byNarration: narrationMoving(),
    }
    clearTimeout(state.relocationTimer)
    state.relocationTimer = setTimeout(flushRelocation, RELOCATION_QUIET_MS)
}

// The `relocated` message for a place the engine reported.
const relocationMessage = (detail, index) => {
    const { view } = state
    const paged = paginated()
    const end = atEnd(index)
    return {
        type: 'relocated',
        cfi: pointCfi(detail, index),
        // The engine counts from the top of what is shown, which in scrolled
        // layout never reaches 1: the end of the book is the whole of it.
        fraction: end ? 1 : clamp01(Number(detail.fraction) || 0),
        sectionIndex: index,
        sectionCount: state.book.sections.length,
        page: paged ? view.renderer.page : null,
        pages: paged ? view.renderer.pages - 2 : null,
        tocId: detail.tocItem && Number.isInteger(detail.tocItem.id) ? detail.tocItem.id : null,
        // An entry without a label names nothing, as a place before the first entry.
        tocLabel: (detail.tocItem && shortLabel(detail.tocItem.label)) || null,
        atEnd: end,
        moved: false,
        userMove: false,
        bookmarkIds: [],
    }
}

// ---------------------------------------------------------------- touch

// Coordinates of a touch in the page's viewport, whichever document it is in.
const pagePoint = (touch, doc) => {
    const frame = doc.defaultView && doc.defaultView.frameElement
    if (!frame) return { x: touch.clientX, y: touch.clientY }
    const rect = frame.getBoundingClientRect()
    const sx = frame.offsetWidth ? rect.width / frame.offsetWidth : 1
    const sy = frame.offsetHeight ? rect.height / frame.offsetHeight : 1
    return { x: rect.left + touch.clientX * sx, y: rect.top + touch.clientY * sy }
}

const inBrightnessStrip = x => {
    const edge = state.settings.brightnessEdge
    const fraction = x / window.innerWidth
    if (edge === 'left') return fraction <= EDGE_STRIP
    if (edge === 'right') return fraction >= 1 - EDGE_STRIP
    return false
}

const swallow = e => {
    e.stopPropagation()
    if (e.cancelable) e.preventDefault()
}

const paginated = () => !state.view.isFixedLayout && state.settings.flow === 'paginated'
const scrolled = () => !state.view.isFixedLayout && state.settings.flow === 'scrolled'

// ---------------------------------------------------------------- a selection keeps the page

// While a selection handle is dragged past the page, the browser scrolls the
// engine's scroller by itself, and no touch reaches the page to snap it back:
// the page would be left half-way between two. In pages, while a selection is
// live and nothing of ours moves the page (a turn, a drag, a move of the
// host), any scroll is undone at once, back to where the engine last placed
// the page. The engine reports every scroll of its own, which moves that place.
const pageScroller = () => {
    try {
        const content = state.view.renderer.getContents()[0]
        const frame = content && content.doc.defaultView && content.doc.defaultView.frameElement
        return frame ? frame.closest('#container') : null
    } catch (e) {
        return null
    }
}

const notePageOffset = () => {
    const scroller = paginated() ? pageScroller() : null
    state.pageOffset = scroller ? scroller[state.view.renderer.scrollProp] : null
}

const keepPage = () => {
    if (!state.book || state.pageOffset === null || !paginated()) return
    if (state.intent > 0 || hostMoving() || state.turn || Date.now() < state.freeUntil) return
    if (state.gesture && state.gesture.mode === 'drag') return
    if (!selectionLive()) return
    const scroller = pageScroller()
    const prop = state.view.renderer.scrollProp
    if (scroller && scroller[prop] !== state.pageOffset) scroller[prop] = state.pageOffset
}

// The paginator swallows the first scroll that follows a navigation when that
// navigation did not itself scroll (the top of a chapter): no relocation comes.
// When a scroll ends in silence, moving by one pixel makes it report.
const onEngineScroll = () => {
    keepPage()
    if (!state.book || !scrolled()) return
    const seen = state.relocations
    clearTimeout(state.scrollTimer)
    state.scrollTimer = setTimeout(() => {
        if (!state.book || !scrolled() || state.relocations !== seen) return
        // Not in the middle of a navigation, which reports for itself: looked at again once it is done.
        if (state.navigating || state.hostMoves > 0) return onEngineScroll()
        const { renderer } = state.view
        // The end of a move by the host stays the host's, the nudge's own relocation included.
        if (hostMoving()) state.hostMoveUntil = Date.now() + SCROLL_QUIET_MS + HOST_SCROLL_SLACK_MS
        else state.intentUntil = Date.now() + LINK_INTENT_MS
        if (narrationMoving()) state.narrationUntil = state.hostMoveUntil
        // Never from the very bottom, where a step forward would open the next chapter.
        const forward = renderer.viewSize - renderer.end > SCROLL_END_PX + 1
        // A chapter that shows whole has nowhere to move by a pixel: a step back would open the chapter before.
        if (!forward && renderer.start <= 1) return
        if (stuckInScroll(forward)) return
        engineBusy(Promise.resolve(forward ? renderer.next(1) : renderer.prev(1))).catch(e => console.warn(e))
    }, SCROLL_QUIET_MS)
}

// In scrolled layout a chapter ends where its text ends: pulling further, at
// either end, goes to the chapter after or before.
const chapterEdge = () => {
    if (!scrolled()) return null
    const { renderer } = state.view
    return {
        top: renderer.start <= SCROLL_END_PX,
        bottom: renderer.viewSize - renderer.end <= SCROLL_END_PX,
    }
}

const turnByPull = g => {
    const dx = g.x - g.x0
    const dy = g.y - g.y0
    if (Math.abs(dy) < SWIPE_MIN_PX || Math.abs(dy) <= Math.abs(dx)) return
    if (dy < 0 && g.edge.bottom) receive({ type: 'next' })
    else if (dy > 0 && g.edge.top) receive({ type: 'prev' })
}

const onTouchStart = (e, doc) => {
    settleHostAnswers()
    // While the note panel is open, the page is behind it: touches are the panel's.
    if (!noteOpen()) dropLoadingNote()
    if (!state.book || e.touches.length !== 1 || noteOpen()) {
        state.gesture = null
        return
    }
    // A finger on the page ends the host's claim on what follows its own move: what moves now is the user's.
    state.hostMoveUntil = 0
    state.narrationUntil = 0
    const touch = e.changedTouches[0]
    const point = pagePoint(touch, doc)
    const target = e.target && e.target.closest ? e.target : e.target && e.target.parentElement
    const { renderer } = state.view
    const paged = paginated() && !!renderer.size
    state.gesture = {
        // For the paginator's own rule at the end of a swipe: where the page and the finger were, and how fast the
        // finger goes, as the paginator measures them.
        bounds: paged ? swipeBounds() : null, page0: paged ? renderer.page : null,
        sx: touch.screenX, sy: touch.screenY, st: e.timeStamp, vx: 0, vy: 0,
        x0: point.x, y0: point.y, x: point.x, y: point.y,
        // Where the touch began in the coordinates of its own document, for highlights.
        docX: touch.clientX, docY: touch.clientY,
        time: performance.now(),
        // The last news of the finger, for how long it holds the page.
        heard: performance.now(),
        mode: 'pending',
        strip: inBrightnessStrip(point.x),
        onLink: !!(target && target.closest('a[href]')),
        // A touch that starts with a selection on the page only clears it, unless it drags a new one.
        hadSelection: selectionTouchStarted(),
        edge: chapterEdge(),
        atEnd: atEndNow(),
    }
}

const onTouchMove = (e, doc) => {
    const g = state.gesture
    if (!g) return
    g.heard = performance.now()
    if (g.mode === 'select') {
        // The browser extends the selection: the paginator must not follow the finger.
        e.stopPropagation()
        return
    }
    const point = pagePoint(e.changedTouches[0], doc)
    const dx = point.x - g.x0
    const dy = point.y - g.y0
    if (g.mode === 'pending') {
        if (Math.hypot(dx, dy) < TAP_MAX_PX) {
            // Not a drag yet: keep it from the paginator so a tap never nudges the page.
            e.stopPropagation()
            if (e.cancelable && (g.strip || paginated())) e.preventDefault()
            return
        }
        if (g.strip && Math.abs(dy) > Math.abs(dx)) {
            g.mode = 'brightness'
            g.y = g.y0
            post({ type: 'brightness', phase: 'start', delta: 0 })
        } else g.mode = 'drag'
    }
    if (g.mode === 'brightness') {
        swallow(e)
        const delta = (g.y - point.y) / window.innerHeight
        g.y = point.y
        if (delta !== 0) post({ type: 'brightness', phase: 'move', delta })
        return
    }
    g.x = point.x
    g.y = point.y
    // The paginator sees this move: its speed counts at the end of the swipe.
    const touch = e.changedTouches[0]
    const dt = e.timeStamp - g.st
    g.vx = (g.sx - touch.screenX) / dt
    g.vy = (g.sy - touch.screenY) / dt
    g.sx = touch.screenX
    g.sy = touch.screenY
    g.st = e.timeStamp
}

// 'next' or 'prev' for a sideways swipe, null for anything else.
const swipeTurn = g => {
    const dx = g.x - g.x0
    const dy = g.y - g.y0
    if (Math.abs(dx) < SWIPE_MIN_PX || Math.abs(dx) <= Math.abs(dy)) return null
    const forward = (dx < 0) !== (state.book.dir === 'rtl')
    return forward ? 'next' : 'prev'
}

const turnBySwipe = g => {
    const type = swipeTurn(g)
    if (type) receive({ type })
}

// Whether the paginator will turn the page at the end of a swipe, and which
// way: 'next', 'prev' or null. Its own rule (`snap` in foliate/paginator.js):
// the middle of what the drag left on screen, pushed by the finger's last
// speed, kept within a page of where the drag began, and not before the first
// page nor after the last one of the book.
const swipeDecision = g => {
    const { renderer } = state.view
    const size = renderer.size
    if (!g.bounds || !(size > 0)) return null
    const { offset, before, after, rtl, vertical } = g.bounds
    const velocity = vertical ? g.vy : g.vx
    const d = velocity * (rtl ? -size : size)
    const middle = (renderer.start + renderer.end) / 2 + (isNaN(d) ? 0 : d)
    const page = Math.floor(Math.max(offset - before, Math.min(offset + after, middle)) / size)
    return page > g.page0 ? 'next' : page < g.page0 ? 'prev' : null
}

// Where the paginator holds the page when a touch begins, for its rule at the end of a swipe.
const swipeBounds = () => {
    const { renderer } = state.view
    const scroller = pageScroller()
    const size = renderer.size
    const vertical = renderer.scrollProp === 'scrollTop'
    return {
        // As the paginator set it, a whole number of pages: the scroller itself rounds it.
        offset: renderer.page * size,
        before: renderer.atStart ? 0 : size,
        after: renderer.atEnd ? 0 : size,
        // The page never rests on the blank page before a section: an offset below 0 is right to left.
        rtl: !vertical && !!scroller && scroller.scrollLeft < 0,
        vertical,
    }
}

// A swipe in pages: a turn is announced before the paginator animates it. It
// has no page to follow the finger to after the last one: the swipe moves
// nothing, and is reported instead.
const swipeEnded = g => {
    if (swipeDecision(g)) {
        beginTurn()
        return awaitTurn(SWIPE_TURN_WAIT_MS)
    }
    if (g.atEnd && swipeTurn(g) === 'next' && atEndNow()) pastEnd()
}

const onTouchEnd = (e, doc, cancelled) => {
    const g = state.gesture
    if (!g) return
    // A cancel takes every finger away, whatever the event still lists.
    if (!cancelled && e.touches.length > 0) return
    state.gesture = null
    if (g.mode === 'select') {
        // The paginator saw none of the drag, so it has nothing to snap.
        e.stopPropagation()
        selectionTouchEnded()
        return
    }
    if (g.mode === 'brightness') {
        e.stopPropagation()
        post({ type: 'brightness', phase: 'end', delta: 0 })
        return
    }
    if (g.mode === 'pending') {
        // The paginator saw no movement, so it has nothing to snap.
        e.stopPropagation()
        if (cancelled || g.onLink) return
        if (g.hadSelection || selectionShown()) {
            clearSelection()
            return
        }
        if (performance.now() - g.time >= TAP_MAX_MS) return
        const hit = highlightAt(doc, g.docX, g.docY)
        if (hit) {
            post({ type: 'highlightTap', id: hit.id, bounds: hit.bounds })
            return
        }
        post({
            type: 'tap',
            x: clamp01(g.x0 / window.innerWidth),
            y: clamp01(g.y0 / window.innerHeight),
        })
        return
    }
    // The paginator follows the finger itself, and snaps the page after; fixed layout has no swipe of its own.
    state.freeUntil = Date.now() + SNAP_FREE_MS
    if (cancelled) return
    if (state.view.isFixedLayout) turnBySwipe(g)
    else if (g.edge) turnByPull(g)
    else swipeEnded(g)
}

const listenToTouches = doc => {
    const options = { capture: true, passive: false }
    doc.addEventListener('touchstart', e => onTouchStart(e, doc), options)
    doc.addEventListener('touchmove', e => onTouchMove(e, doc), options)
    doc.addEventListener('touchend', e => onTouchEnd(e, doc, false), options)
    doc.addEventListener('touchcancel', e => onTouchEnd(e, doc, true), options)
    doc.addEventListener('contextmenu', e => e.preventDefault())
}

// The paginator lays its section out again on each change of its size, also when the document on screen has no
// body: one whose book was closed while it loaded (a note given up for a new book), or one being replaced. It fails
// there; such a document has nothing to lay out, and one that loads is laid out by its load.
const guardRender = () => {
    const Paginator = customElements.get('foliate-paginator')
    const render = Paginator.prototype.render
    if (render.lirelleGuarded) return
    const guarded = function () {
        const [content] = this.getContents()
        if (content && !(content.doc && content.doc.body)) return
        return render.call(this)
    }
    guarded.lirelleGuarded = true
    Paginator.prototype.render = guarded
}

// The touch ends without a word of the finger: the page was hidden, or lost the focus. What it was doing is ended
// as a cancel would; nothing is done of it. (Not at `pointercancel`: the engine has it at each pan it takes over,
// while the touch goes on.)
const releaseTouch = () => {
    const g = state.gesture
    if (!g) return
    state.gesture = null
    if (g.mode === 'select') selectionTouchEnded()
    else if (g.mode === 'brightness') post({ type: 'brightness', phase: 'end', delta: 0 })
}

// Whether a finger holds the page from the narration's moves: one on it, heard of lately.
const touchHolds = () => !!state.gesture && performance.now() - state.gesture.heard < TOUCH_HOLD_MS

// ---------------------------------------------------------------- navigation

const timeout = ms => new Promise(resolve => setTimeout(() => resolve('timeout'), ms))

// A book that did not open in time: the device's slowness, not the book's fault.
class TookTooLong extends Error {}

const hostMoving = () => state.hostMoves > 0 || Date.now() < state.hostMoveUntil

const narrationMoving = () => state.narrationMoves > 0 || Date.now() < state.narrationUntil

// Runs one navigation and reports a section that fails or never comes. Its
// origin says who asked for it: the user (a selection is cleared), the host
// on its own account, as for a search result (the selection is kept), or the
// page itself following the narration (kept too, and the relocation is marked
// `byNarration` instead of answering a move of the host). `next` and `prev`
// are the user's by contract: volume keys and the page's edges; read-along
// must never use them.
const navigate = (action, { origin = 'user' } = {}) => {
    if (origin === 'host') return moveForHost(action)
    if (origin === 'narration') return moveForNarration(action)
    // A move by the user ends the host's and the narration's claim on what follows their own move.
    state.hostMoveUntil = 0
    state.narrationUntil = 0
    return runNavigation(action)
}

// Notes a turn of the engine under way ([state.navigating]) until it settles; returns it as it is.
const engineBusy = run => {
    const settled = run.then(() => {}, () => {})
    state.navigating = settled
    settled.then(() => {
        if (state.navigating === settled) state.navigating = null
    })
    return run
}

const moveForHost = async action => {
    // A turn under way would have the engine drop the move: it is made once the turn is done.
    if (state.navigating) await Promise.race([state.navigating, timeout(NAVIGATION_TIMEOUT_MS)])
    // A relocation still to be sent is of what came before: it must not be taken for this move's answer.
    if (state.pendingMessage) flushRelocation()
    state.hostMoves++
    try {
        await holdSelection(() => runNavigation(action))
    } finally {
        state.hostMoves--
        // In scrolled layout the engine reports the end of a scroll late, and the page may nudge it
        // (`onEngineScroll`): those relocations are still the host's. In pages nothing trails.
        if (scrolled()) state.hostMoveUntil = Date.now() + SCROLL_QUIET_MS + HOST_SCROLL_SLACK_MS
        if (state.book) hostMoveDone()
    }
}

// A move for the narration is the host's in all but its answer: the selection is kept, and the relocations it
// causes, the trailing ones included, are marked as the narration's.
const moveForNarration = async action => {
    state.hostMoves++
    state.narrationMoves++
    try {
        await holdSelection(() => runNavigation(action))
    } finally {
        state.hostMoves--
        state.narrationMoves--
        const trailing = scrolled() ? SCROLL_QUIET_MS + HOST_SCROLL_SLACK_MS : HOST_ANSWER_WAIT_MS
        if (scrolled()) state.hostMoveUntil = Date.now() + trailing
        state.narrationUntil = Date.now() + trailing
    }
}

const runNavigation = async action => {
    state.sectionFailure = null
    state.intent++
    state.navigations++
    const run = Promise.resolve().then(action)
    try {
        const outcome = await Promise.race([run.then(() => 'done'), timeout(NAVIGATION_TIMEOUT_MS)])
        // A section that failed outright has been reported where it failed.
        if (outcome === 'timeout' && !state.sectionFailure)
            postError('section_failed', 'The section did not load in time')
    } catch (e) {
        if (!state.sectionFailure) postError('section_failed', e)
    } finally {
        state.intent--
    }
}

// An internal link of the book, followed in the main view and reported to the host.
// `flash`: a note, whose target is shown once the page is there.
const followLink = async (href, { flash = false } = {}) => {
    const { book } = state
    state.intentUntil = Date.now() + LINK_INTENT_MS
    post({ type: 'link', href, external: false })
    await navigate(() => state.view.goTo(href))
    if (flash && state.book === book) flashTarget(href)
}

const goTo = message => {
    const { view } = state
    if (typeof message.cfi === 'string') return view.goTo(message.cfi)
    if (typeof message.href === 'string') return view.goTo(message.href)
    if (typeof message.fraction === 'number')
        return view.goTo({ fraction: clamp01(message.fraction) })
    console.warn('goTo without a target')
}

// ---------------------------------------------------------------- opening

const wireView = view => {
    view.addEventListener('relocate', e => onRelocate(e.detail))
    view.addEventListener('load', e => {
        const { doc } = e.detail
        // Before the engine lays the document out: it counts the pages right after this.
        if (!view.isFixedLayout) {
            keepInsideColumn(doc)
            markCover(doc)
            markOwnAlignment(doc)
        }
        listenToTouches(doc)
        watchSelection(doc, view.isFixedLayout)
    })
    view.addEventListener('link', e => {
        // A note reference comes first: the note panel takes it, or declines and the link is followed.
        if (noteLink(e)) return
        // With the panel off, a note is gone to and shown where it lands.
        if (!state.settings.footnotePanel && !view.isFixedLayout && isNoteReference(e.detail.a)) {
            e.preventDefault()
            followLink(String(e.detail.href ?? ''), { flash: true })
            return
        }
        state.intentUntil = Date.now() + LINK_INTENT_MS
        post({ type: 'link', href: String(e.detail.href ?? ''), external: false })
    })
    view.addEventListener('external-link', e => {
        e.preventDefault()
        post({ type: 'link', href: String(e.detail.href_ ?? e.detail.href ?? ''), external: true })
    })
}

// An EPUB is read entry by entry, as the engine asks for them.
const openEpub = async base => {
    const loader = await makeLoader(base)
    const { EPUB } = await import('./foliate/epub.js')
    const book = await new EPUB(loader).init()
    // Second layer under the content security policy: script resources
    // of the book are never turned into URLs.
    book.transformTarget.addEventListener('load', e => {
        if (e.detail.isScript) e.detail.allow = false
    })
    book.transformTarget.addEventListener('data', dropResourceHints)
    return book
}

// Why a book did not open: `cannot_open` when the file itself is at fault, which only a book given whole can
// be (the host has checked an EPUB before it serves it); `open_failed` for everything else.
const openFailure = (e, format) => {
    const fileAtFault = e instanceof UnreadableBook
        || (format !== 'epub' && !!state.book && !(e instanceof TookTooLong))
    return fileAtFault ? 'cannot_open' : 'open_failed'
}

const open = async message => {
    const { view } = state
    const format = oneOf(message.format, BOOK_FORMATS, 'epub')
    // A search still matching a section of the old book must not draw into the new one.
    await stopSearch()
    closeBook()
    state.opening = true
    try {
        if (typeof message.base !== 'string' || !message.base.startsWith('/'))
            throw new Error('No book to open')
        const base = message.base.endsWith('/') ? message.base : message.base + '/'
        const settings = cleanSettings(message.settings)
        const book = format === 'epub' ? await openEpub(base) : await openWholeBook(base, format)
        if (!book.sections.length) throw new Error('The book has no readable section')
        watchSections(book)
        if (settings.maxColumns === 1 && book.rendition) book.rendition.spread = 'none'
        state.book = book
        await view.open(book)
        // In capture, so that both are known before the view reports the relocation.
        view.renderer.addEventListener('load', () => { state.unplaced = true }, true)
        view.renderer.addEventListener('scroll', onEngineScroll)
        view.renderer.addEventListener('relocate', e => {
            state.relocations++
            state.lastReason = e.detail.reason
            if (e.detail.reason !== 'anchor') state.unplaced = false
            notePageOffset()
        }, true)
        if (!view.isFixedLayout) view.renderer.setAttribute('animated', '')
        applySettings(settings)
        await Promise.race([
            restorePosition(message),
            timeout(NAVIGATION_TIMEOUT_MS * 2).then(() => {
                throw new TookTooLong('The book did not open in time')
            }),
        ])
        // A section whose entry has no label has none either, though the entry is not listed.
        const entries = contentsEntries(book.toc)
        state.sectionLabels = await sectionLabels(book, entries)
        post({
            type: 'opened',
            toc: entries.filter(entry => entry.label),
            dir: book.dir === 'rtl' ? 'rtl' : 'ltr',
            fixedLayout: !!view.isFixedLayout,
            title: bookTitle(book),
            sectionLabels: state.sectionLabels,
        })
        state.opening = false
        const pending = state.pendingRelocation
        state.pendingRelocation = null
        if (pending) onRelocate(pending)
    } catch (e) {
        console.error(e)
        const code = openFailure(e, format)
        closeBook()
        postError(code, e)
    } finally {
        state.opening = false
    }
}

// ---------------------------------------------------------------- host messages

const handle = async message => {
    if (state.unsupported) {
        if (message.type === 'open') postError('unsupported_engine', state.unsupported)
        return
    }
    if (message.type === 'open') return open(message)
    if (message.type === 'settings') return applySettings(message.settings)
    // No book, no move to answer: the host forgets the moves it awaited when the page says `opened`.
    if (!state.book) return
    const { view } = state
    switch (message.type) {
        case 'goTo': return navigate(() => goTo(message), { origin: 'host' })
        case 'next':
            settleHostAnswers()
            return turnPage(true, () => view.next())
        case 'prev':
            settleHostAnswers()
            return turnPage(false, () => view.prev())
        case RECOVER: return recover()
        case 'highlights': return setHighlights(message.items)
        case 'putHighlight': return putHighlight(message.item)
        case 'removeHighlight': return removeHighlight(message.id)
        case 'bookmarks': return bookmarksChanged(message.items)
        case 'clearSelection': return clearSelection()
        case 'search': return startSearch(message)
        case 'cancelSearch': return cancelSearch()
        case 'clearSearch': return clearSearch()
        // A move by the host: a live selection is kept, and it is not a turn by the user.
        case 'showSearchResult': return showSearchResult(message.cfi, action => navigate(action, { origin: 'host' }))
        case 'closeNote': return closeNote()
        case 'narrationMark': return narrationMark(message)
        case 'narrationClear': return narrationClear()
        case 'narrationFind': return narrationFind(message)
        case 'narrationCfi': return narrationCfi(message)
        default: console.warn('Unknown message type', message.type)
    }
}

// Messages are handled one after the other, in the order they arrive.
let queue = Promise.resolve()
const receive = message => {
    queue = queue.then(() => handle(message)).catch(e => console.error(e))
    return queue
}

const receiveJson = json => {
    let message
    try {
        message = typeof json === 'string' ? JSON.parse(json) : json
    } catch (e) {
        console.warn('Dropped a message that is not JSON')
        return
    }
    if (!message || typeof message !== 'object' || typeof message.type !== 'string') {
        console.warn('Dropped a message without a type')
        return
    }
    receive(message)
}

const boot = async () => {
    // A book document must never be able to start a second reader.
    if (window.top !== window) return
    window.lirelle = { receive: receiveJson }
    const missing = missingFeature()
    if (missing) {
        state.unsupported = `Missing ${missing}`
        postError('unsupported_engine', state.unsupported)
        return
    }
    try {
        await import('./foliate/view.js')
        customElements.whenDefined('foliate-paginator').then(guardRender)
        await attachMarks(document.getElementById('view'))
        await attachFind(document.getElementById('view'), {
            post,
            labelOf: index => state.sectionLabels[index] ?? null,
        })
    } catch (e) {
        state.unsupported = shortMessage(e)
        postError('unsupported_engine', e)
        return
    }
    state.view = document.getElementById('view')
    attachSelection(state.view, {
        post,
        touching: () => !!state.gesture,
        dragging: () => !!state.gesture && state.gesture.mode === 'select',
        // Only this page changes the mode of a touch.
        onSelectionDuringTouch: () => {
            if (state.gesture && state.gesture.mode === 'pending') state.gesture.mode = 'select'
        },
    })
    attachNotes(state.view, {
        post,
        book: () => state.book,
        follow: followLink,
        css: buildCss,
        settings: state.settings,
    })
    attachArrival(state.view)
    attachNarration(state.view, {
        post,
        move: action => navigate(action, { origin: 'narration' }),
        touching: touchHolds,
    })
    wireView(state.view)
    listenToTouches(document)
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') releaseTouch()
    })
    window.addEventListener('blur', releaseTouch)
    post({ type: 'ready' })
}

boot()
