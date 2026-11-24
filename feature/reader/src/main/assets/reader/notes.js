// The note panel.
//
// A link the book marks as a note reference, or a link set in superscript,
// opens its note in a panel at the bottom of the screen instead of moving the
// page. foliate's FootnoteHandler finds the note: it opens the book a second
// time in a view of its own, goes to the note's section there and cuts that
// view's copy of the document down to the note. That view is shown in the
// panel, a part of our page, in the reader's style sheet and in scrolled flow,
// as tall as the note, never less than a fifth of the screen nor more than
// 45 % of it. The panel is a surface of its own: a shade off the page,
// lighter on a dark theme and darker on a light one, note included. A link of
// the note back to its reference is hidden there: one the book marks as a
// back-link, or one that leads to the reference itself.
//
// When the link turns out to lead to no note (its target is missing, what
// would be shown is a whole chapter, or nothing at all: an empty anchor), the
// link is followed as any other; an empty one is shown where it lands. A
// link inside the note, or "Go to the note", closes the panel and follows the
// link in the main view. A tap outside the panel, a swipe down on it, the
// host's `closeNote` and Escape close it; a touch on the page or a move of the
// page gives up a note still loading. The host is told when the panel opens
// and closes. The main view's documents are never changed. Fixed-layout books
// have no panel: their links are followed as they are.

import { FootnoteHandler } from './foliate/footnotes.js'
import { inViewport, onPage } from './geometry.js'

// Above this many characters, what the reference leads to is no note but a chapter.
const NOTE_TEXT_MAX = 4000
const LABEL_MAX = 200
// The share of the screen the panel takes at least, and at most.
const MIN_SHARE = 0.2
const MAX_SHARE = 0.45
const NOTE_MARGIN_PX = 8
// How far the panel's surface moves from the page's background towards its text.
const SURFACE_SHIFT_DARK = 0.1
const SURFACE_SHIFT_LIGHT = 0.08
// How far a finger must pull the panel down to close it.
const SWIPE_CLOSE_PX = 60
// A note that is not shown by then is given up, and its link followed.
const LOAD_TIMEOUT_MS = 8000
const TOP_SLACK_PX = 1
// The header's text size against the reader's.
const HEAD_SCALE = 16 / 18

const notes = {
    post: () => {},
    book: () => null,
    // Follows an internal link in the main view and tells the host, as for any link;
    // with `{ flash: true }`, its target is shown where it lands.
    follow: () => {},
    css: () => '',
    view: null,
    settings: null,
    // The note being found or shown: { a, href, index, label, view, rendered, laidOut, timer, done }.
    attempt: null,
    open: false,
    // A finger pulling on the panel: { x0, y0, y, fromTop }.
    swipe: null,
    el: {},
}

const warn = e => console.warn(e)

// Note views that have loaded their document, and those that have also laid it out.
const loadedViews = new WeakSet()
const placedViews = new WeakSet()
// How long a view that loaded its document is given to lay it out before it is disposed anyway.
const PLACE_WAIT_MS = 1000
// How long a view let go of is kept at most, whatever the engine is still waiting for.
const RETIRE_MAX_MS = 10000

// ---------------------------------------------------------------- the panel

const titleOf = label => {
    const { title } = notes.settings.noteLabels
    return label ? `${title} ${label}` : title
}

const labelOf = a => {
    const text = String((a && a.textContent) || '').replace(/\s+/g, ' ').trim()
    if (!text) return null
    const chars = [...text]
    return chars.length > LABEL_MAX ? chars.slice(0, LABEL_MAX).join('') : text
}

// A colour `t` of the way from one `#rrggbb` to another.
const mix = (from, to, t) => '#' + [1, 3, 5].map(i => {
    const a = parseInt(from.slice(i, i + 2), 16)
    const b = parseInt(to.slice(i, i + 2), 16)
    return Math.round(a + (b - a) * t).toString(16).padStart(2, '0')
}).join('')

// The panel's surface: the page's background moved a little towards its text.
const surfaceOf = s => mix(s.background, s.foreground, s.dark ? SURFACE_SHIFT_DARK : SURFACE_SHIFT_LIGHT)

// The reader's style sheet on the panel's surface, without selection: the panel is for reading only.
// Links of the note back to its reference are hidden.
const noteCss = attempt => {
    const hidden = (attempt && attempt.backlinks) || []
    return `${notes.css(notes.settings)}
${hidden.length ? `${hidden.join(',\n')} {
    display: none !important;
}` : ''}
html, body {
    background: ${surfaceOf(notes.settings)} !important;
}
/* The panel's own room is around the note: none of the book's on top of it. */
body > :first-child { margin-top: 0 !important; }
body > :last-child { margin-bottom: 0 !important; }
html {
    -webkit-user-select: none !important;
    user-select: none !important;
}`
}

const styleView = (view, attempt) => {
    const { renderer } = view
    if (!renderer) return
    renderer.setAttribute('margin', `${NOTE_MARGIN_PX}px`)
    // The room on either side is the panel's own, the same whatever the reader's margins.
    renderer.setAttribute('gap', '0%')
    renderer.setAttribute('flow', 'scrolled')
    renderer.setStyles(noteCss(attempt))
}

const paint = () => {
    const { panel, go, title } = notes.el
    const s = notes.settings
    if (!panel || !s) return
    panel.style.fontSize = `${Math.round(s.fontSizePx * HEAD_SCALE)}px`
    panel.style.background = surfaceOf(s)
    panel.style.color = s.foreground
    go.style.color = s.link
    go.textContent = s.noteLabels.goTo
    if (notes.attempt) title.textContent = titleOf(notes.attempt.label)
}

// The panel as tall as the note, within its shares of the screen; a longer note scrolls inside it.
const size = () => {
    const { attempt } = notes
    const renderer = attempt && attempt.view && attempt.view.renderer
    if (!renderer) return
    const { panel, body } = notes.el
    const chrome = panel.offsetHeight - body.offsetHeight
    const style = getComputedStyle(body)
    const padding = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
    const room = Math.max(0, Math.floor(window.innerHeight * MAX_SHARE) - chrome)
    const least = Math.min(room, Math.ceil(window.innerHeight * MIN_SHARE) - chrome)
    body.style.height = `${Math.max(least, Math.min(Math.ceil(renderer.viewSize) + padding, room))}px`
}

const dispose = view => {
    try {
        view.close()
    } catch (e) {
        // A view that never showed its document has nothing to close.
        warn(e)
    }
    view.remove()
}

// Lets a note view go. The engine cannot close a view in the middle of its
// work: one still loading would keep its section loaded for nobody, and one
// still laying out (fonts on their way, a style for the next frame) would fail
// later on a view that is gone. It stays in the page, unseen, until the engine
// is done with it: its document loaded and laid out, with its fonts, or its
// note given up before anything was loaded, or ten seconds at most.
const retire = (view, done) => {
    if (!view) return
    view.setAttribute('data-lirelle-retired', '')
    if (!view.isConnected) notes.el.body.append(view)
    let gone = false
    const finish = () => {
        if (gone) return
        gone = true
        // The engine restyles its view on the next frame after each change of style.
        requestAnimationFrame(() => setTimeout(() => dispose(view)))
    }
    const afterFonts = () => {
        let doc = null
        try {
            doc = view.renderer.getContents()[0].doc
        } catch (e) {
            // Nothing laid out: nothing to wait for.
        }
        Promise.resolve(doc && doc.fonts && doc.fonts.ready).then(finish, finish)
    }
    let loaded = false
    const afterLoad = () => {
        if (loaded) return
        loaded = true
        if (placedViews.has(view)) return afterFonts()
        view.addEventListener('relocate', afterFonts, { once: true })
        setTimeout(afterFonts, PLACE_WAIT_MS)
    }
    // A section that never comes leaves it waiting: it goes after a while anyway.
    setTimeout(finish, RETIRE_MAX_MS)
    if (loadedViews.has(view)) return afterLoad()
    view.addEventListener('load', afterLoad, { once: true })
    // Let go of while its document was loading (that event is past), or given up before anything was loaded.
    done.then(() => loadedViews.has(view) ? afterLoad() : finish())
}

// Takes the panel down and forgets the note, without a word to the host.
const teardown = () => {
    const { attempt } = notes
    notes.attempt = null
    notes.open = false
    notes.swipe = null
    if (attempt) {
        clearTimeout(attempt.timer)
        retire(attempt.view, attempt.done)
    }
    const { layer, panel, body } = notes.el
    layer.hidden = true
    layer.dataset.state = 'closed'
    panel.style.transform = ''
    body.style.height = ''
    notes.view.removeAttribute('aria-hidden')
}

// The reference gets the focus back when it is still on the page.
const focusReference = a => {
    try {
        const doc = a.ownerDocument
        if (!a.isConnected || !doc.defaultView || !doc.defaultView.frameElement) return
        const rects = [...a.getClientRects()].filter(r => r.width > 0 && r.height > 0)
        if (!onPage(doc, rects).some(inViewport)) return
        a.focus({ preventScroll: true })
    } catch (e) {
        warn(e)
    }
}

// ---------------------------------------------------------------- finding the note

// The link leads to no note after all: it is followed as any other.
// Only a note still being found is given up: the panel is not open yet.
// `flash`: what the link leads to is shown where it lands.
const giveUp = (attempt, { flash = false } = {}) => {
    if (notes.attempt !== attempt) return
    teardown()
    notes.follow(attempt.href, { flash })
}

const hostView = (attempt, view) => {
    view.addEventListener('load', () => loadedViews.add(view), { once: true })
    view.addEventListener('relocate', () => placedViews.add(view), { once: true })
    if (notes.attempt !== attempt) {
        retire(view, attempt.done)
        return
    }
    attempt.view = view
    view.addEventListener('relocate', () => laidOut(attempt))
    view.addEventListener('load', e => watchNote(e.detail.doc))
    view.addEventListener('link', e => {
        e.preventDefault()
        if (notes.attempt === attempt && notes.open) leaveFor(String(e.detail.href ?? ''))
    })
    view.addEventListener('external-link', e => {
        e.preventDefault()
        notes.post({ type: 'link', href: String(e.detail.href_ ?? e.detail.href ?? ''), external: true })
    })
    const { layer, body } = notes.el
    // Laid out unseen: the view measures its note against the panel's width.
    layer.dataset.state = 'loading'
    layer.hidden = false
    body.style.height = `${Math.floor(window.innerHeight * MAX_SHARE)}px`
    body.append(view)
    styleView(view, attempt)
}

// Pictures count as something to show in a note.
const PICTURES = 'img, svg, image, video, canvas, object'

const isEmpty = doc => textLength(doc) === 0 && !(doc.body && doc.body.querySelector(PICTURES))

const textLength = doc => (doc.body ? doc.body.textContent : '').replace(/\s+/g, ' ').trim().length

// Where a link of a section leads, as the book names places: an EPUB resolves it against the section's
// file; the other books name a place the same way everywhere.
const hrefFrom = (section, href) => typeof section.resolveHref === 'function' ? section.resolveHref(href) : href

// Whether the note holds the reference itself: the reference, or a link to where it leads.
const holdsReference = (doc, attempt) => {
    const { id } = attempt.a
    if (id && doc.getElementById(id)) return true
    const section = notes.book().sections[attempt.index]
    for (const link of doc.querySelectorAll('a[href]')) {
        try {
            if (hrefFrom(section, link.getAttribute('href')) === attempt.href) return true
        } catch (e) {
            warn(e)
        }
    }
    return false
}

// A link back is a mark: a number, "[10]", an arrow, a word. One around the note's text is kept.
const BACKLINK_TEXT_MAX = 8

const textOf = el => (el.textContent || '').replace(/\s+/g, ' ').trim()

// Whether an element of the reference's document is the reference, or a
// `sup` or `span` around it that holds nothing else.
const isReference = (el, a) => {
    for (let node = a; node; node = node.parentElement) {
        if (node === el) return true
        const parent = node.parentElement
        if (!parent || !parent.matches('sup, span') || textOf(parent) !== textOf(a)) return false
    }
    return false
}

const isMarkedBacklink = link => {
    const types = tokens(`${link.getAttributeNS(OPS, 'type') ?? ''} ${link.getAttribute('epub:type') ?? ''}`)
    return types.has('backlink') || tokens(link.getAttribute('role')).has('doc-backlink')
}

// Whether a link of the note leads to the reference itself.
const leadsToReference = (link, attempt) => {
    const book = notes.book()
    const { a, refIndex } = attempt
    if (!Number.isInteger(refIndex)) return false
    try {
        const target = book.resolveHref(hrefFrom(book.sections[attempt.index], link.getAttribute('href')))
        return !!target && target.index === refIndex && isReference(target.anchor(a.ownerDocument), a)
    } catch (e) {
        // A link that leads nowhere leads nowhere back.
        return false
    }
}

// A selector of exactly one element, from the root of its document.
const pathOf = el => {
    const steps = []
    for (let node = el; node && node !== node.ownerDocument.documentElement; node = node.parentElement)
        steps.unshift(`*:nth-child(${[...node.parentElement.children].indexOf(node) + 1})`)
    return [':root', ...steps].join(' > ')
}

// Selectors of the links of a note back to its reference: a short mark, that
// the book marks as a link back or that leads to the reference itself (a
// number pointing back, as a word processor exports it).
const backlinks = (doc, attempt) => [...doc.querySelectorAll('a[href]')]
    .filter(link => [...textOf(link)].length <= BACKLINK_TEXT_MAX
        && (isMarkedBacklink(link) || leadsToReference(link, attempt)))
    .map(pathOf)

const rendered = (attempt, { target }) => {
    if (notes.attempt !== attempt) return
    const doc = target && (target.ownerDocument || (target.startContainer && target.startContainer.ownerDocument))
    // No target: the whole file would be shown. Too long: a whole chapter. Holding the reference: not a note.
    if (!doc || textLength(doc) > NOTE_TEXT_MAX || holdsReference(doc, attempt)) return giveUp(attempt)
    // Nothing to read: the target is an empty anchor, and the page goes there.
    if (isEmpty(doc)) return giveUp(attempt, { flash: true })
    attempt.backlinks = backlinks(doc, attempt)
    if (attempt.backlinks.length) styleView(attempt.view, attempt)
    attempt.rendered = true
    if (attempt.laidOut) reveal(attempt)
}

// The note view knows its size only once it has laid the note out.
const laidOut = attempt => {
    if (notes.attempt !== attempt) return
    attempt.laidOut = true
    if (!attempt.rendered) return
    if (notes.open) size()
    else reveal(attempt)
}

const reveal = attempt => {
    clearTimeout(attempt.timer)
    notes.open = true
    notes.el.title.textContent = titleOf(attempt.label)
    size()
    notes.el.layer.dataset.state = 'open'
    notes.view.setAttribute('aria-hidden', 'true')
    notes.el.panel.focus({ preventScroll: true })
    notes.post({ type: 'noteOpened', href: attempt.href, label: attempt.label })
}

// ---------------------------------------------------------------- leaving

// Closes the panel and follows a link in the main view: the host hears `noteClosed`, then `link`.
// `flash`: the note itself, to be shown where it lands.
const leaveFor = (href, { flash = false } = {}) => {
    if (!notes.attempt) return
    teardown()
    notes.post({ type: 'noteClosed' })
    notes.follow(href, { flash })
}

// ---------------------------------------------------------------- swipe down

const atTop = () => {
    const renderer = notes.attempt && notes.attempt.view && notes.attempt.view.renderer
    return !renderer || renderer.start <= TOP_SLACK_PX
}

// A point of a touch in our page's viewport, whichever document it is in.
const outerY = (touch, doc) => {
    const frame = doc && doc.defaultView && doc.defaultView.frameElement
    return { x: touch.clientX + (frame ? frame.getBoundingClientRect().left : 0),
        y: touch.clientY + (frame ? frame.getBoundingClientRect().top : 0) }
}

const swipeStart = (e, doc, fromTop) => {
    if (!notes.open || e.touches.length !== 1) {
        notes.swipe = null
        return
    }
    const { x, y } = outerY(e.changedTouches[0], doc)
    notes.swipe = { x0: x, y0: y, x, y, fromTop }
}

const swipeMove = (e, doc) => {
    const g = notes.swipe
    if (!g) return
    const { x, y } = outerY(e.changedTouches[0], doc)
    g.x = x
    g.y = y
    const dy = y - g.y0
    // The panel follows the finger down, never up.
    if (g.fromTop && dy > 0 && dy > Math.abs(x - g.x0)) notes.el.panel.style.transform = `translateY(${dy}px)`
}

const swipeEnd = () => {
    const g = notes.swipe
    notes.swipe = null
    if (!g) return
    notes.el.panel.style.transform = ''
    const dy = g.y - g.y0
    if (g.fromTop && dy >= SWIPE_CLOSE_PX && dy > Math.abs(g.x - g.x0)) closeNote()
}

const listenForSwipes = (target, doc, fromTop) => {
    target.addEventListener('touchstart', e => swipeStart(e, doc, fromTop(e)), { passive: true })
    target.addEventListener('touchmove', e => swipeMove(e, doc), { passive: true })
    target.addEventListener('touchend', swipeEnd)
    target.addEventListener('touchcancel', () => {
        notes.swipe = null
        notes.el.panel.style.transform = ''
    })
}

// Each document shown in the panel: a pull down from the top of the note closes it.
const watchNote = doc => {
    doc.addEventListener('contextmenu', e => e.preventDefault())
    listenForSwipes(doc, doc, atTop)
}

// ---------------------------------------------------------------- the page's calls

// Whether the panel is on screen: the page then leaves touches to it.
export const noteOpen = () => notes.open

// A touch on the page, or a move of it: a note still loading is given up without a word.
export const dropLoadingNote = () => {
    if (notes.attempt && !notes.open) teardown()
}

// Whether a link is a note reference, as the engine's note finder tells one:
// marked as such, or set in superscript and not marked as a link back.
const OPS = 'http://www.idpf.org/2007/ops'
const REF_TYPES = ['biblioref', 'glossref', 'noteref']
const REF_ROLES = ['doc-biblioref', 'doc-glossref', 'doc-noteref']

const tokens = value => new Set(String(value ?? '').split(/\s+/).filter(Boolean))

const isSuper = el => {
    if (!el || !el.matches) return false
    if (el.matches('sup')) return true
    const align = el.ownerDocument.defaultView.getComputedStyle(el).verticalAlign
    return align === 'super' || align === 'top' || align === 'text-top' || /^\d/.test(align)
}

export const isNoteReference = a => {
    if (!a || !a.getAttributeNS) return false
    const types = tokens(a.getAttributeNS(OPS, 'type'))
    const roles = tokens(a.getAttribute('role'))
    if (REF_TYPES.some(t => types.has(t)) || REF_ROLES.some(r => roles.has(r))) return true
    if (types.has('backlink') || roles.has('doc-backlink')) return false
    try {
        return isSuper(a) || (a.children.length === 1 && isSuper(a.children[0])) || isSuper(a.parentElement)
    } catch (e) {
        return false
    }
}

// Where an internal link leads in the reading order, or null when it leads outside.
const sectionOf = (book, href) => {
    try {
        const resolved = book.resolveHref(href)
        return resolved && Number.isInteger(resolved.index) && resolved.index >= 0 ? resolved.index : null
    } catch (e) {
        return null
    }
}

// The section of the main view a document belongs to, or null.
const sectionShowing = doc => {
    const content = notes.view.renderer.getContents().find(c => c.doc === doc)
    return content && Number.isInteger(content.index) ? content.index : null
}

// Called by the page's `link` listener of the main view. Returns true when the
// link is a note reference that the panel takes over; the page then neither
// reports nor follows it.
export const noteLink = e => {
    const book = notes.book()
    if (!notes.settings.footnotePanel || !book || notes.view.isFixedLayout) return false
    const href = String(e.detail.href ?? '')
    // Outside the reading order nothing can be shown: the link is followed at once.
    const index = sectionOf(book, href)
    if (index === null) return false
    const handler = new FootnoteHandler()
    let finding
    try {
        finding = handler.handle(book, e)
    } catch (err) {
        warn(err)
        if (!e.defaultPrevented) return false
        finding = Promise.reject(err)
    }
    if (!finding) return false
    closeNote({ focus: false })
    const attempt = {
        a: e.detail.a, href, index, label: labelOf(e.detail.a), refIndex: sectionShowing(e.detail.a.ownerDocument),
        backlinks: [],
        view: null, rendered: false, laidOut: false, timer: 0,
        // Settled once the engine is done with the note, found or not.
        done: finding.then(() => {}, err => {
            warn(err)
            giveUp(attempt)
        }),
    }
    notes.attempt = attempt
    handler.addEventListener('before-render', ev => hostView(attempt, ev.detail.view))
    handler.addEventListener('render', ev => rendered(attempt, ev.detail))
    attempt.timer = setTimeout(() => giveUp(attempt), LOAD_TIMEOUT_MS)
    return true
}

// Closes the panel, or gives up a note still loading. The host is told only of
// a panel it was told about; the reference gets the focus back.
export const closeNote = ({ focus = true } = {}) => {
    const { attempt, open } = notes
    if (!attempt) return
    teardown()
    if (!open) return
    notes.post({ type: 'noteClosed' })
    if (focus) focusReference(attempt.a)
}

export const applyNoteSettings = settings => {
    notes.settings = settings
    paint()
    const view = notes.attempt && notes.attempt.view
    // The note view lays itself out again and reports it: the panel is sized then.
    if (view) styleView(view, notes.attempt)
}

export const attachNotes = (view, { post, book, follow, css, settings }) => {
    notes.view = view
    notes.post = post
    notes.book = book
    notes.follow = follow
    notes.css = css
    notes.el = {
        layer: document.getElementById('note-layer'),
        scrim: document.getElementById('note-scrim'),
        panel: document.getElementById('note-panel'),
        title: document.getElementById('note-title'),
        go: document.getElementById('note-go'),
        body: document.getElementById('note-body'),
    }
    const { scrim, panel, go } = notes.el
    scrim.addEventListener('click', () => closeNote())
    go.addEventListener('click', () => {
        if (notes.open && notes.attempt) leaveFor(notes.attempt.href, { flash: true })
    })
    // On the panel's own part of the page: its top, its button, the margins of the note.
    listenForSwipes(panel, null, e => !(e.target && e.target.closest && e.target.closest('foliate-view')) || atTop())
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && notes.open) closeNote()
    })
    applyNoteSettings(settings)
}
