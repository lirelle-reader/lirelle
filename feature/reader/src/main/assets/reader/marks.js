// Highlights and bookmarks on the page.
//
// Kotlin owns both lists and sends them whole or one item at a time; the page
// never makes a mark by itself. Highlights are drawn by the engine's overlayer
// (nothing is ever inserted into a book document), one section at a time, when
// that section's overlayer is created. Taps are hit-tested against our own list
// of live ranges, so no other drawing in the overlayer can take a tap from a
// highlight. Fixed-layout books have no overlayer: nothing is drawn there, and
// a bookmark belongs to its page.

import { boxOf, inViewport, onPage } from './geometry.js'
import { isCfi, isColour } from './values.js'

const STYLES = ['highlight', 'underline', 'strikethrough', 'squiggly']
const ID_MAX = 64
const BOOKMARK_IDS_MAX = 64
const LINE_WIDTH = 2
const NOTE_RADIUS = 4
const SVG = 'http://www.w3.org/2000/svg'

const marks = {
    view: null,
    Overlayer: null,
    // id -> { id, cfi, color, style, note, index }, in the order they were added: the last is on top.
    highlights: new Map(),
    // id -> { id, cfi, index }
    bookmarks: new Map(),
    // id -> the Range of a highlight drawn in the document on screen.
    live: new Map(),
}

const warn = e => console.warn(e)

// ---------------------------------------------------------------- validation

const isId = value => typeof value === 'string' && value.length > 0 && value.length <= ID_MAX

// The section a CFI points into, or null when it points nowhere in this book.
const sectionOf = cfi => {
    try {
        const { index } = marks.view.resolveCFI(cfi)
        return Number.isInteger(index) && index >= 0 && index < marks.view.book.sections.length ? index : null
    } catch (e) {
        return null
    }
}

const cleanHighlight = raw => {
    if (!raw || typeof raw !== 'object' || !isId(raw.id) || !isCfi(raw.cfi) || !isColour(raw.color)) return null
    const index = sectionOf(raw.cfi)
    if (index === null) return null
    return {
        id: raw.id,
        cfi: raw.cfi,
        color: raw.color.toLowerCase(),
        style: STYLES.includes(raw.style) ? raw.style : 'highlight',
        note: raw.note === true,
        index,
    }
}

const cleanBookmark = raw => {
    if (!raw || typeof raw !== 'object' || !isId(raw.id) || !isCfi(raw.cfi)) return null
    const index = sectionOf(raw.cfi)
    return index === null ? null : { id: raw.id, cfi: raw.cfi, index }
}

// ---------------------------------------------------------------- drawing

const svg = tag => document.createElementNS(SVG, tag)

// The shape of one style, its colour set as a style so that nothing but the
// colour comes from the book's side of the bridge.
const shape = (rects, { color, style, writingMode }) => {
    const { Overlayer } = marks
    if (style === 'squiggly') {
        const g = Overlayer.squiggly(rects, { width: LINE_WIDTH, writingMode })
        g.removeAttribute('stroke')
        g.style.stroke = color
        return g
    }
    // `highlight` reads its opacity and blending from the CSS variables set on the view.
    const g = style === 'highlight' ? Overlayer.highlight(rects)
        : Overlayer[style](rects, { width: LINE_WIDTH, writingMode })
    g.removeAttribute('fill')
    g.style.fill = color
    return g
}

// A dot at the end of the highlight's last line, for a highlight with a note.
const noteMark = (rects, { color, writingMode, rtl }) => {
    const last = [...rects].filter(r => r.width > 0 && r.height > 0).at(-1)
    if (!last) return null
    const vertical = typeof writingMode === 'string' && writingMode.startsWith('vertical')
    const dot = svg('circle')
    dot.setAttribute('cx', vertical ? (last.left + last.right) / 2 : rtl ? last.left : last.right)
    dot.setAttribute('cy', vertical ? last.bottom : last.top)
    dot.setAttribute('r', NOTE_RADIUS)
    dot.style.fill = color
    return dot
}

// Called by the overlayer at each draw and redraw, with fresh rects.
const drawHighlight = (rects, options) => {
    const g = svg('g')
    g.append(shape(rects, options))
    const dot = options.note ? noteMark(rects, options) : null
    if (dot) g.append(dot)
    return g
}

const textDirection = (doc, range) => {
    try {
        const node = range.commonAncestorContainer
        const el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement
        const style = doc.defaultView.getComputedStyle(el)
        return { writingMode: style.writingMode, rtl: style.direction === 'rtl' }
    } catch (e) {
        return { writingMode: 'horizontal-tb', rtl: false }
    }
}

const onDrawAnnotation = e => {
    const { draw, annotation, doc, range } = e.detail
    const item = annotation && annotation.lirelle
    if (!item || marks.highlights.get(item.id) !== item) return
    marks.live.set(item.id, range)
    draw(drawHighlight, { color: item.color, style: item.style, note: item.note, ...textDirection(doc, range) })
}

// The section whose overlayer is on screen, or null (none yet, or fixed layout).
const sectionOnScreen = () => {
    const { view } = marks
    if (!view || !view.renderer || view.isFixedLayout) return null
    const content = view.renderer.getContents()[0]
    return content && content.overlayer ? content.index : null
}

const show = item => marks.view.addAnnotation({ value: item.cfi, lirelle: item }).catch(warn)

// The engine keys a drawing by its CFI: of several highlights on exactly the
// same range, the one added last is drawn.
const redrawCfi = cfi => {
    const top = [...marks.highlights.values()].filter(item => item.cfi === cfi).at(-1)
    if (top) return show(top)
    return marks.view.deleteAnnotation({ value: cfi }).catch(warn)
}

const onCreateOverlay = e => {
    const { index } = e.detail
    marks.live.clear()
    for (const item of marks.highlights.values()) if (item.index === index) show(item)
}

// ---------------------------------------------------------------- the host's messages

export const setHighlights = items => {
    const screen = sectionOnScreen()
    const shown = new Set()
    for (const item of marks.highlights.values()) if (item.index === screen) shown.add(item.cfi)
    for (const cfi of shown) marks.view.deleteAnnotation({ value: cfi }).catch(warn)
    marks.highlights.clear()
    marks.live.clear()
    for (const raw of Array.isArray(items) ? items : []) {
        const item = cleanHighlight(raw)
        if (!item) continue
        marks.highlights.delete(item.id)
        marks.highlights.set(item.id, item)
    }
    for (const item of marks.highlights.values()) if (item.index === screen) show(item)
}

export const putHighlight = raw => {
    const item = cleanHighlight(raw)
    if (!item) return
    const old = marks.highlights.get(item.id)
    marks.highlights.delete(item.id)
    marks.highlights.set(item.id, item)
    marks.live.delete(item.id)
    const screen = sectionOnScreen()
    if (old && old.cfi !== item.cfi && old.index === screen) redrawCfi(old.cfi)
    if (item.index === screen) show(item)
}

export const removeHighlight = id => {
    const old = marks.highlights.get(id)
    if (!old) return
    marks.highlights.delete(id)
    marks.live.delete(id)
    if (old.index === sectionOnScreen()) redrawCfi(old.cfi)
}

export const setBookmarks = items => {
    marks.bookmarks.clear()
    for (const raw of Array.isArray(items) ? items : []) {
        const item = cleanBookmark(raw)
        if (item) marks.bookmarks.set(item.id, item)
    }
}

export const clearMarks = () => {
    marks.highlights.clear()
    marks.bookmarks.clear()
    marks.live.clear()
}

// Blending and opacity of highlights, set on the view and inherited by the
// overlayer inside it: a theme switch recolours without drawing again.
export const applyMarkSettings = settings => {
    const { view } = marks
    if (!view) return
    view.style.setProperty('--overlayer-highlight-opacity', String(settings.highlightOpacity))
    view.style.setProperty('--overlayer-highlight-blend-mode', settings.highlightBlend)
}

// ---------------------------------------------------------------- taps

// The highlights drawn in a section's document, the one added last first, with
// their live ranges.
export const highlightsIn = doc => [...marks.highlights.values()].reverse()
    .filter(item => marks.live.has(item.id))
    .map(item => ({ id: item.id, cfi: item.cfi, range: marks.live.get(item.id) }))
    .filter(({ range }) => range.startContainer.ownerDocument === doc)

// The highlight under a point given in the coordinates of a section's own
// document, the one added last first, with its visible part in fractions of
// the page; null when there is none.
export const highlightAt = (doc, x, y) => {
    for (const { id, range } of highlightsIn(doc)) {
        const rects = [...range.getClientRects()].filter(r => r.width > 0 && r.height > 0)
        if (rects.some(r => r.left <= x && x < r.right && r.top <= y && y < r.bottom))
            return { id, bounds: boundsOnPage(doc, rects) }
    }
    return null
}

const boundsOnPage = (doc, rects) => {
    const placed = onPage(doc, rects)
    const visible = placed.filter(inViewport)
    return boxOf(visible.length ? visible : placed)
}

// ---------------------------------------------------------------- bookmarks on the page

// Whether a bookmark's position lies in the visible range: at or after its
// start, before its end.
const inside = (bookmark, visible) => {
    try {
        const doc = visible.startContainer.ownerDocument
        const point = marks.view.resolveCFI(bookmark.cfi).anchor(doc)
        if (!point || typeof point.startContainer === 'undefined') return false
        const { startContainer: node, startOffset: offset } = point
        if (visible.comparePoint(node, offset) !== 0) return false
        return !(node === visible.endContainer && offset === visible.endOffset)
    } catch (e) {
        return false
    }
}

const sameSpread = (a, b) => {
    if (a === b) return true
    const { renderer, book } = marks.view
    if (!renderer || typeof renderer.getSpreadOf !== 'function') return false
    const one = renderer.getSpreadOf(book.sections[a])
    const two = renderer.getSpreadOf(book.sections[b])
    return !!one && !!two && one.index === two.index
}

// The ids of the bookmarks inside the visible page: `index` is the section on
// screen and `range` its visible range (null in fixed layout).
export const bookmarksOn = ({ index, range }) => {
    const { view } = marks
    if (!view || !Number.isInteger(index)) return []
    const out = []
    for (const bookmark of marks.bookmarks.values()) {
        const here = view.isFixedLayout ? sameSpread(bookmark.index, index)
            : bookmark.index === index && !!range && inside(bookmark, range)
        if (here) out.push(bookmark.id)
        if (out.length >= BOOKMARK_IDS_MAX) break
    }
    return out
}

// ---------------------------------------------------------------- set-up

export const attachMarks = async view => {
    const { Overlayer } = await import('./foliate/overlayer.js')
    marks.view = view
    marks.Overlayer = Overlayer
    view.addEventListener('create-overlay', onCreateOverlay)
    view.addEventListener('draw-annotation', onDrawAnnotation)
}
