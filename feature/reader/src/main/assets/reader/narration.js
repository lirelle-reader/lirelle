// Read-along: the sentence the narration is reading, on the page.
//
// The host knows which sentence is heard (an element of a section, by its id)
// and sends it as it changes; the page underlines it, in the theme's accent on
// a light tint of it while playing, dotted and with no tint while paused: an
// underline stays apart from the fills of the user's highlights. While the
// host follows the narration, the page goes to a sentence that is not on the
// page shown (both pages of a spread count), into another section too, and
// that move is reported as the narration's; never while a finger is on the
// page, which the host may not know of yet. After every relocation the page
// says whether the sentence is on the page shown, when that changed. The host
// also asks which narrated sentence to start from (on the page shown, or at a
// selection) and the position of a sentence, with how far into the book it is.
//
// The underline is a class on the sentence's element and a style sheet of our
// own in each section document as it loads: neither changes a position in the
// book. Nothing else of a book document is touched.

import { onPage, visibleBox } from './geometry.js'
import { isColour } from './values.js'

const CLASS = 'lirelle-narration'
const PAUSED = 'lirelle-narration-paused'
const STYLE_MARK = 'data-lirelle-narration'
const ID_MAX = 1024
// The narrated ids of one section the host may send: a sane bound for a chapter.
const IDS_MAX = 5000
const TINT_ALPHA = 0.07
const UNDERLINE_PX = 2
const OFFSET_PX = 5
// Less than this much of a sentence inside the screen does not put it on the page.
const SLIVER_PX = 1
const HEX = 16
const DEFAULT_ACCENT = '#0000ee'
// How long after a move for the narration its relocation is awaited before the visibility is reported anyway.
const FOLLOW_ANSWER_WAIT_MS = 300

const narration = {
    view: null,
    post: () => {},
    // Runs a move of the page made for the narration.
    move: action => action(),
    // Whether a finger is on the page.
    touching: () => false,
    accent: DEFAULT_ACCENT,
    // The sentence heard: { index, id, paused }, or null.
    mark: null,
    // The element carrying the class, in the section document on screen.
    element: null,
    // The visibility last reported, null when none was since the narration was cleared or the book opened.
    visible: null,
    // The sentence the visibility was last reported for, as `index:id`.
    visibleKey: null,
    // After a move for the narration, the timer that reports the visibility when no relocation does.
    followTimer: 0,
    // The mark already reported missing, as `index:id`.
    missing: null,
}

const warn = e => console.warn(e)

const isId = value => typeof value === 'string' && value.length > 0 && value.length <= ID_MAX

const isRequest = value => Number.isInteger(value) && value >= 0

const sectionCount = () => {
    const book = narration.view && narration.view.book
    return book ? book.sections.length : 0
}

const isSection = value => Number.isInteger(value) && value >= 0 && value < sectionCount()

// ---------------------------------------------------------------- the style

const rgbOf = hex => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), HEX))

const css = () => {
    const { accent } = narration
    const tint = `rgba(${rgbOf(accent).join(', ')}, ${TINT_ALPHA})`
    // `:root` makes the rules win over the reader's own `body *` rules of a coloured theme.
    return `:root .${CLASS} {
    text-decoration-line: underline !important;
    text-decoration-style: solid !important;
    text-decoration-color: ${accent} !important;
    text-decoration-thickness: ${UNDERLINE_PX}px !important;
    text-underline-offset: ${OFFSET_PX}px !important;
    background-color: ${tint} !important;
    -webkit-box-decoration-break: clone;
    box-decoration-break: clone;
}
:root .${CLASS}.${PAUSED} {
    text-decoration-style: dotted !important;
    background-color: transparent !important;
}`
}

const injectStyle = doc => {
    try {
        let style = doc.querySelector(`style[${STYLE_MARK}]`)
        if (!style) {
            style = doc.createElementNS('http://www.w3.org/1999/xhtml', 'style')
            style.setAttribute(STYLE_MARK, '')
            ;(doc.head || doc.documentElement).append(style)
        }
        const text = css()
        if (style.textContent !== text) style.textContent = text
    } catch (e) {
        warn(e)
    }
}

// ---------------------------------------------------------------- the documents on screen

const contents = () => {
    const { view } = narration
    try {
        return view && view.renderer ? view.renderer.getContents() : []
    } catch (e) {
        return []
    }
}

const docOf = index => {
    const content = contents().find(c => c.index === index)
    return content ? content.doc : null
}

const onScreenDoc = doc => contents().some(c => c.doc === doc)

// Whether part of an element is on the page shown: the screen, both pages of a spread included.
const shown = el => {
    try {
        const doc = el.ownerDocument
        if (!el.isConnected || !onScreenDoc(doc)) return false
        const rects = [...el.getClientRects()].filter(r => r.width > 0 && r.height > 0)
        // Inside what shows of the page (not behind the margins of a spread), by more than a sliver: the edge of
        // a column next to the page, a fraction of a pixel in, is not on it.
        const box = visibleBox(doc)
        return onPage(doc, rects).some(r => r.right > box.left + SLIVER_PX && r.left < box.right - SLIVER_PX
            && r.bottom > box.top + SLIVER_PX && r.top < box.bottom - SLIVER_PX)
    } catch (e) {
        return false
    }
}

// ---------------------------------------------------------------- the underline

const unmark = () => {
    const { element } = narration
    narration.element = null
    if (element) element.classList.remove(CLASS, PAUSED)
}

const reportMissing = mark => {
    const key = `${mark.index}:${mark.id}`
    if (narration.missing === key) return
    narration.missing = key
    narration.post({ type: 'narrationMissing', sectionIndex: mark.index, id: mark.id })
}

// Puts the class on the sentence when its section is loaded. Returns 'marked', 'missing' or 'unloaded'.
const apply = () => {
    const { mark } = narration
    const doc = mark ? docOf(mark.index) : null
    if (!doc) {
        unmark()
        return 'unloaded'
    }
    const el = doc.getElementById(mark.id)
    if (!el) {
        unmark()
        reportMissing(mark)
        return 'missing'
    }
    if (el !== narration.element) unmark()
    el.classList.add(CLASS)
    el.classList.toggle(PAUSED, mark.paused)
    narration.element = el
    return 'marked'
}

// Told when it changes, and for each new sentence heard on the page: one the user turned onto the rest of stays
// visible, and the host follows again once the next sentence shows. A relayout alone tells nothing new: telling
// would bring following back, the strip would go, and the page would be laid out again, on and on.
const reportVisibility = () => {
    if (!narration.mark) return
    const visible = !!narration.element && shown(narration.element)
    const key = `${narration.mark.index}:${narration.mark.id}`
    if (visible === narration.visible && !(visible && key !== narration.visibleKey)) return
    narration.visible = visible
    narration.visibleKey = key
    narration.post({ type: 'narrationVisible', visible })
}

// ---------------------------------------------------------------- finding

// The narrated elements of a document, by id: an id without an element is left out (a book's map may list a
// sentence its text does not have), and so is one not laid out on a page drawn ([drawn]): a note hidden away.
const elementsOf = (doc, ids, drawn) => ids
    .map(id => ({ id, el: doc.getElementById(id) }))
    .filter(({ el }) => el && (!drawn || laidOut(el)))

const laidOut = el => {
    try {
        return el.getClientRects().length > 0
    } catch (e) {
        return false
    }
}

// The first of some elements in the text, whatever the order of the ids: a note listed between two sentences may
// sit at the end of the section.
const firstInText = found => found.reduce((first, one) =>
    !first || first.el.compareDocumentPosition(one.el) & first.el.DOCUMENT_POSITION_PRECEDING ? one : first, null)

// Those of some elements that start at or after a point (a collapsed range, or the start of a range).
const startingAfter = (found, range) => found.filter(({ el }) => {
    try {
        return range.comparePoint(el, 0) >= 0
    } catch (e) {
        warn(e)
        return false
    }
})

// The sentence the narration was last heard at ([prefer]) when it shows on the page; else the first narrated
// sentence in the text on the page shown, else the first one after it.
const firstOnPage = (doc, ids, prefer) => {
    const kept = prefer && ids.includes(prefer) ? doc.getElementById(prefer) : null
    if (kept && laidOut(kept) && shown(kept)) return prefer
    const location = narration.view.lastLocation
    const range = location && location.range
    const visible = range && range.startContainer.ownerDocument === doc ? range : null
    const found = elementsOf(doc, ids, true)
    const first = firstInText(found.filter(({ el }) => shown(el)))
        || (visible ? firstInText(startingAfter(found, visible)) : null)
    return first ? first.id : null
}

// The narrated sentence that holds the start of the selection, else the first one after it in the text.
const firstAtSelection = (doc, ids) => {
    const selection = doc.getSelection()
    if (!selection || selection.type !== 'Range' || !selection.rangeCount) return null
    const point = selection.getRangeAt(0).cloneRange()
    point.collapse(true)
    const found = elementsOf(doc, ids, true)
    const first = firstInText(found.filter(({ el }) => el.contains(point.startContainer)))
        || firstInText(startingAfter(found, point))
    return first ? first.id : null
}

const findIn = async (index, ids, what, prefer) => {
    const doc = docOf(index)
    if (doc) return what === 'selection' ? firstAtSelection(doc, ids) : firstOnPage(doc, ids, prefer)
    if (what === 'selection') return null
    // A section after the one shown has all of its sentences after the page: its first one the text has.
    const screen = contents()[0]
    if (!screen || index <= screen.index || !ids.length) return null
    const later = await narration.view.book.sections[index].createDocument()
    const first = later ? firstInText(elementsOf(later, ids, false)) : null
    return first ? first.id : null
}

// ---------------------------------------------------------------- positions

// A collapsed range at the first character drawn in an element, or at the element itself when it has no text.
const startOf = el => {
    const doc = el.ownerDocument
    const range = doc.createRange()
    const walker = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const offset = node.data.search(/\S/)
        if (offset < 0) continue
        range.setStart(node, offset)
        range.collapse(true)
        return range
    }
    range.setStart(el, 0)
    range.collapse(true)
    return range
}

// How far into the book a point of a section is, from 0 to 1, as relocations count it: the section's place
// (by size, the engine's own section fractions), plus the share of the section's text before the point. Null
// when the engine knows no place for the section.
const fractionAt = (index, doc, point) => {
    const fractions = narration.view.getSectionFractions()
    const start = fractions[index]
    const end = fractions[index + 1]
    if (typeof start !== 'number' || typeof end !== 'number') return null
    const body = doc.body || doc.documentElement
    const total = body ? body.textContent.length : 0
    let share = 0
    if (total > 0) {
        const before = doc.createRange()
        before.setStart(body, 0)
        before.setEnd(point.startContainer, point.startOffset)
        share = Math.min(before.toString().length / total, 1)
    }
    return Math.min(Math.max(start + share * (end - start), 0), 1)
}

// The position of the start of a sentence, and how far into the book it is.
const positionIn = async (index, id) => {
    const { view } = narration
    const doc = docOf(index) || await view.book.sections[index].createDocument()
    const el = doc && doc.getElementById(id)
    if (!el) return { cfi: null, fraction: null }
    const point = startOf(el)
    return { cfi: view.getCFI(index, point), fraction: fractionAt(index, doc, point) }
}

// ---------------------------------------------------------------- the host's messages

export const narrationMark = async message => {
    const { sectionIndex: index, id } = message
    if (!isSection(index) || !isId(id)) return
    const old = narration.mark
    if (!old || old.index !== index || old.id !== id) narration.missing = null
    narration.mark = { index, id, paused: message.paused === true }
    const outcome = apply()
    const wanted = message.follow === true && outcome !== 'missing' && !narration.touching()
    if (wanted && !(narration.element && shown(narration.element))) {
        await narration.move(() => narration.view.renderer.goTo({ index, anchor: doc => doc.getElementById(id) }))
        if (narration.mark && narration.mark.index === index && narration.mark.id === id) apply()
        // Reported after the relocation of the move, which the page sends a little later.
        return awaitRelocation()
    }
    reportVisibility()
}

const awaitRelocation = () => {
    clearTimeout(narration.followTimer)
    narration.followTimer = setTimeout(narrationRelocated, FOLLOW_ANSWER_WAIT_MS)
}

export const narrationClear = () => {
    clearTimeout(narration.followTimer)
    narration.followTimer = 0
    unmark()
    narration.mark = null
    narration.visible = null
    narration.missing = null
}

export const narrationFind = async message => {
    const { requestId, sectionIndex: index, what } = message
    if (!isRequest(requestId)) return
    let id = null
    if (isSection(index) && Array.isArray(message.ids) && (what === 'page' || what === 'selection')) {
        try {
            const prefer = isId(message.prefer) ? message.prefer : null
            id = await findIn(index, message.ids.slice(0, IDS_MAX).filter(isId), what, prefer)
        } catch (e) {
            warn(e)
        }
    }
    narration.post({ type: 'narrationFound', requestId, sectionIndex: index, id })
}

export const narrationCfi = async message => {
    const { requestId, sectionIndex: index, id } = message
    if (!isRequest(requestId)) return
    let position = { cfi: null, fraction: null }
    if (isSection(index) && isId(id)) {
        try {
            position = await positionIn(index, id)
        } catch (e) {
            warn(e)
        }
    }
    narration.post({ type: 'narrationCfiOf', requestId, ...position })
}

// The page moved, by the user, the host or the engine: the host hears when the sentence came on or went off it.
export const narrationRelocated = () => {
    clearTimeout(narration.followTimer)
    narration.followTimer = 0
    reportVisibility()
}

// The accent of the theme is the colour of its links.
export const applyNarrationSettings = settings => {
    narration.accent = isColour(settings.link) ? settings.link.toLowerCase() : DEFAULT_ACCENT
    for (const { doc } of contents()) if (doc) injectStyle(doc)
}

// A book is closed or another one opened.
export const resetNarration = () => {
    clearTimeout(narration.followTimer)
    narration.followTimer = 0
    narration.element = null
    narration.mark = null
    narration.visible = null
    narration.missing = null
}

const onLoad = e => {
    const { doc, index } = e.detail
    injectStyle(doc)
    if (narration.mark && narration.mark.index === index) apply()
    // The document of another section replaces the one that held the mark: it is not kept alive.
    else narration.element = null
}

export const attachNarration = (view, { post, move, touching }) => {
    narration.view = view
    narration.post = post
    narration.move = move
    narration.touching = touching
    view.addEventListener('load', onLoad)
}
