// Text selections on the page.
//
// A selection is made by the user: a long press, then the device's handles
// (a double click in a desktop browser). Once it holds still it is reported to
// the host with a range CFI checked by a round trip, its text, where its
// visible part lies on the page and the highlights it touches. A selection
// lives within one page: the engine's own habit of turning the page when a
// selection reaches past it is switched off, in pages a selection the user
// takes past the page is cut at its edge (where it started is kept), and a
// relocation by the user clears it. One asked for by the host (`goTo`) keeps it: while it is off the
// page the host is told it is gone, and it is reported again once it is back
// on the page. Fixed-layout books have no selection. Nothing is ever inserted
// into a book document.

import { boxOf, inViewport, onPage } from './geometry.js'
import { highlightsIn } from './marks.js'

// How long a selection changed without a touch (a handle dragged) must hold
// still before it is reported.
const QUIET_MS = 150
const TEXT_MAX = 10000
const HIGHLIGHT_IDS_MAX = 32

const selection = {
    view: null,
    post: () => {},
    // Whether a finger is on the page, and whether it is dragging a selection: the page tracks touches.
    touching: () => false,
    dragging: () => false,
    // Tells the page that a new selection appeared under the finger.
    onSelectionDuringTouch: () => {},
    // A copy of the selection as it was when the finger went down.
    atTouchStart: null,
    timer: 0,
    // The last `selection` message sent, as JSON; null when the host knows of none.
    reported: null,
    // Host moves in progress, and the selection to put back after them.
    holding: 0,
    held: null,
    // The selection last put back after a move by the host: never cut to the page it was moved away from.
    restored: null,
    // Whether the next report follows a change of the selection by the user, and may cut it to the page.
    trim: false,
}

const warn = e => console.warn(e)

const hasText = text => /\S/.test(text)

// The selected range of a document when it is a selection that counts: a
// range with text in it.
const rangeIn = doc => {
    try {
        const sel = doc.getSelection()
        if (!sel || sel.type !== 'Range' || !sel.rangeCount) return null
        const range = sel.getRangeAt(0)
        return hasText(range.toString()) ? range : null
    } catch (e) {
        return null
    }
}

const reflowable = () => {
    const { view } = selection
    return !!(view && view.renderer && !view.isFixedLayout)
}

// The section on screen holding a selection that counts, or null.
const found = () => {
    if (!reflowable()) return null
    for (const { doc, index } of selection.view.renderer.getContents()) {
        const range = rangeIn(doc)
        if (range) return { doc, index, range }
    }
    return null
}

// ---------------------------------------------------------------- normalisation

// A boundary on an element is moved to the nearest text inside the selection:
// a CFI counts characters, and one made from an element boundary resolves to
// nothing. Text nodes holding only white space between blocks are passed over.
const normalise = range => {
    const startsOnText = range.startContainer.nodeType === Node.TEXT_NODE
    const endsOnText = range.endContainer.nodeType === Node.TEXT_NODE
    if (startsOnText && endsOnText) return range
    // Both ends are in the same text node only when both are on text: the common ancestor is an element.
    const root = range.commonAncestorContainer
    const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    let first = null
    let last = null
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        if (!range.intersectsNode(node) || !hasText(node.data)) continue
        if (!first) first = node
        last = node
    }
    if (!first) return null
    const out = range.cloneRange()
    if (!startsOnText) out.setStart(first, 0)
    if (!endsOnText) out.setEnd(last, last.data.length)
    return out.collapsed ? null : out
}

// The range CFI of a range, when it resolves back to the same text.
const cfiOf = (doc, index, range) => {
    try {
        const { view } = selection
        const cfi = view.getCFI(index, range)
        const back = view.resolveCFI(cfi)
        if (back.index !== index) return null
        const again = back.anchor(doc)
        return again && again.toString() === range.toString() ? cfi : null
    } catch (e) {
        warn(e)
        return null
    }
}

// ---------------------------------------------------------------- the message

const cut = text => {
    if (text.length <= TEXT_MAX) return { text, truncated: false }
    let end = TEXT_MAX
    // Never half of a character written with two code units.
    const code = text.charCodeAt(end - 1)
    if (code >= 0xd800 && code <= 0xdbff) end--
    return { text: text.slice(0, end), truncated: true }
}

// The visible part of a range, in fractions of the page; null when none of it
// is on screen.
const visibleBounds = (doc, range) => {
    const rects = [...range.getClientRects()].filter(r => r.width > 0 && r.height > 0)
    const visible = onPage(doc, rects).filter(inViewport)
    return visible.length ? boxOf(visible) : null
}

// a.start < b.end and a.end > b.start
const overlaps = (a, b) =>
    a.compareBoundaryPoints(Range.END_TO_START, b) < 0 && a.compareBoundaryPoints(Range.START_TO_END, b) > 0

const same = (a, b) =>
    a.compareBoundaryPoints(Range.START_TO_START, b) === 0 && a.compareBoundaryPoints(Range.END_TO_END, b) === 0

const highlightsOver = (doc, range, cfi) => {
    const ids = []
    let exactId = null
    for (const highlight of highlightsIn(doc)) {
        try {
            if (!exactId && (highlight.cfi === cfi || same(highlight.range, range))) exactId = highlight.id
            if (ids.length < HIGHLIGHT_IDS_MAX && overlaps(highlight.range, range)) ids.push(highlight.id)
        } catch (e) {
            warn(e)
        }
    }
    return { highlightIds: ids, exactId }
}

// ---------------------------------------------------------------- keeping to the page

// The visible range of the page in pages, when it lies in a document; null in scrolled layout.
const visibleRange = doc => {
    const { view } = selection
    if (!reflowable() || view.renderer.scrolled) return null
    const range = view.lastLocation && view.lastLocation.range
    return range && range.startContainer.ownerDocument === doc ? range : null
}

const rangeBetween = (doc, [startNode, startOffset], [endNode, endOffset]) => {
    const range = doc.createRange()
    range.setStart(startNode, startOffset)
    range.setEnd(endNode, endOffset)
    return range
}

// The point `count` characters into the text of a range, from its start or, going back, from its end.
const pointAlong = (range, count, fromEnd) => {
    const root = range.commonAncestorContainer
    const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    const pieces = []
    if (root.nodeType === Node.TEXT_NODE) pieces.push(root)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) if (range.intersectsNode(node)) pieces.push(node)
    if (fromEnd) pieces.reverse()
    let left = count
    for (const node of pieces) {
        const from = node === range.startContainer ? range.startOffset : 0
        const to = node === range.endContainer ? range.endOffset : node.data.length
        if (left <= to - from) return fromEnd ? [node, to - left] : [node, from + left]
        left -= to - from
    }
    return fromEnd ? [range.startContainer, range.startOffset] : [range.endContainer, range.endOffset]
}

const SPACE = /\s/

// A point of the selection outside the page, brought to its edge. A word the
// edge cuts in two (one hyphenated over the page break) is kept whole, never
// past where the selection itself reached.
const clampPoint = (visible, node, offset) => {
    const side = visible.comparePoint(node, offset)
    if (side === 0) return [node, offset]
    const doc = visible.startContainer.ownerDocument
    const after = side > 0
    const edge = after ? [visible.endContainer, visible.endOffset] : [visible.startContainer, visible.startOffset]
    // The character on the page at the edge, and the text from the edge to the point.
    const inside = visible.toString()
    const onPage = after ? inside.slice(-1) : inside.charAt(0)
    const beyond = after ? rangeBetween(doc, edge, [node, offset]) : rangeBetween(doc, [node, offset], edge)
    const text = beyond.toString()
    if (!onPage || SPACE.test(onPage) || !text || SPACE.test(after ? text.charAt(0) : text.slice(-1))) return edge
    // How many characters of the word beyond the edge are kept: up to the first white space from the edge.
    let keep = 0
    while (keep < text.length && !SPACE.test(text.charAt(after ? keep : text.length - 1 - keep))) keep++
    return pointAlong(beyond, keep, !after)
}

// A selection the user took past the page is cut at its edge, keeping where it
// started. One wholly off the page is left alone. Returns whether it was cut.
const trimToPage = live => {
    const visible = visibleRange(live.doc)
    if (!visible || sameRange(live.range, selection.restored)) return false
    const { range } = live
    const inside = visible.comparePoint(range.startContainer, range.startOffset) === 0
        && visible.comparePoint(range.endContainer, range.endOffset) === 0
    if (inside || !overlaps(range, visible)) return false
    try {
        const sel = live.doc.getSelection()
        const [anchor, anchorOffset] = clampPoint(visible, sel.anchorNode, sel.anchorOffset)
        const [focus, focusOffset] = clampPoint(visible, sel.focusNode, sel.focusOffset)
        sel.setBaseAndExtent(anchor, anchorOffset, focus, focusOffset)
        return true
    } catch (e) {
        warn(e)
        return false
    }
}

// The host is told the selection it knew of is gone.
const forget = () => {
    if (selection.reported === null) return
    selection.reported = null
    selection.post({ type: 'selectionCleared' })
}

const report = () => {
    clearTimeout(selection.timer)
    selection.timer = 0
    const { trim } = selection
    selection.trim = false
    let live = found()
    if (live && trim && trimToPage(live)) live = found()
    const range = live && normalise(live.range)
    const cfi = range && cfiOf(live.doc, live.index, range)
    if (!cfi) return forget()
    const bounds = visibleBounds(live.doc, range)
    // Off the page after a move asked for by the host: the browser keeps it, the host has nothing to show.
    if (!bounds) return forget()
    const message = {
        type: 'selection',
        cfi,
        ...cut(range.toString()),
        sectionIndex: live.index,
        bounds,
        ...highlightsOver(live.doc, range, cfi),
    }
    const json = JSON.stringify(message)
    if (json === selection.reported) return
    selection.reported = json
    selection.post(message)
}

// `trim`: the selection was changed by the user, and may be cut to the page.
const schedule = (ms, trim = false) => {
    if (trim) selection.trim = true
    clearTimeout(selection.timer)
    selection.timer = setTimeout(report, ms)
}

// ---------------------------------------------------------------- events

const onSelectionChange = doc => {
    // What the engine does to the selection during a move asked for by the host is undone after it.
    if (selection.holding || !reflowable()) return
    if (selection.touching()) {
        // A new selection under the finger: the finger now drags it. The one that was there when the finger went
        // down, put back after a move by the host, is not new.
        const range = rangeIn(doc)
        if (range && !sameRange(range, selection.atTouchStart)) selection.onSelectionDuringTouch()
        // Reported when the finger lifts.
        return
    }
    if (rangeIn(doc)) return schedule(QUIET_MS, true)
    if (found()) return
    clearTimeout(selection.timer)
    selection.timer = 0
    forget()
}

// Called for each section document as it loads.
export const watchSelection = (doc, fixedLayout) => {
    if (fixedLayout) {
        // An attribute of the root, not a node: highlights cannot be drawn on a fixed page.
        const style = doc.documentElement.style
        style.setProperty('-webkit-user-select', 'none', 'important')
        style.setProperty('user-select', 'none', 'important')
        return
    }
    // The engine turns the page by itself 700 ms after a selection made with a pointer reaches past it; it never
    // learns that a pointer went down.
    doc.addEventListener('pointerdown', e => e.stopPropagation(), true)
    doc.addEventListener('selectionchange', () => onSelectionChange(doc))
}

// ---------------------------------------------------------------- the page's calls

const sameRange = (a, b) => {
    try {
        return !!b && a.startContainer.ownerDocument === b.startContainer.ownerDocument && same(a, b)
    } catch (e) {
        return false
    }
}

// Whether the host is showing a selection: on the page, and reported.
export const selectionShown = () => selection.reported !== null

// A finger went down. Returns whether the host is showing a selection.
export const selectionTouchStarted = () => {
    const live = found()
    selection.atTouchStart = live ? live.range.cloneRange() : null
    return selectionShown()
}

// A touch that dragged a selection has ended.
export const selectionTouchEnded = () => schedule(0, true)

// Whether a selection that counts is live on the page.
export const selectionLive = () => !!found()

// Clears the selection: a tap, a relocation by the user, the host's `clearSelection`.
export const clearSelection = () => {
    clearTimeout(selection.timer)
    selection.timer = 0
    selection.held = null
    try {
        if (selection.view && selection.view.renderer) selection.view.deselect()
    } catch (e) {
        warn(e)
    }
    forget()
}

// The page moved, by the user, by the host, or by the engine alone (a new
// layout). A move by the user clears the selection, except while a finger is
// dragging it; any other keeps it and reports it again where it now is.
export const selectionRelocated = origin => {
    if (selection.holding || selection.dragging()) return
    if (origin === 'user') clearSelection()
    else if (selection.timer || found()) schedule(QUIET_MS)
}

// Runs a move asked for by the host. The engine collapses the selection when
// it navigates; it is put back after, when its document is still on screen.
// A move still running when the page gives up on it (the navigation timeout)
// collapses the selection after it was put back, and the host is told it is
// gone: rare, and the host is sent `section_failed` anyway.
export const holdSelection = async action => {
    if (selection.holding++ === 0) {
        const live = found()
        selection.held = live ? { doc: live.doc, range: live.range.cloneRange() } : null
    }
    try {
        return await action()
    } finally {
        if (--selection.holding === 0) restore()
    }
}

const restore = () => {
    const { held } = selection
    selection.held = null
    if (!held) return
    const onScreen = reflowable() && selection.view.renderer.getContents().some(({ doc }) => doc === held.doc)
    if (!onScreen || !held.range.startContainer.isConnected) {
        clearTimeout(selection.timer)
        selection.timer = 0
        return forget()
    }
    try {
        selection.restored = held.range
        if (!rangeIn(held.doc)) {
            const sel = held.doc.getSelection()
            sel.removeAllRanges()
            sel.addRange(held.range)
        }
    } catch (e) {
        warn(e)
    }
    schedule(QUIET_MS)
}

// A book is closed or another one opened: the host starts again from nothing.
export const resetSelection = () => {
    clearTimeout(selection.timer)
    selection.timer = 0
    selection.reported = null
    selection.held = null
    selection.restored = null
    selection.trim = false
    selection.atTouchStart = null
}

export const attachSelection = (view, { post, touching, dragging, onSelectionDuringTouch }) => {
    selection.view = view
    selection.post = post
    selection.touching = touching
    selection.dragging = dragging
    selection.onSelectionDuringTouch = onSelectionDuringTouch
}
