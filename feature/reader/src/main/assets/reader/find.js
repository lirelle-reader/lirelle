// Searching the book, and outlining the results on the page.
//
// The host asks for a search with an id of its own; the page answers with the
// results, a sparse progress and an end. Case and accents are ignored. A book
// can have hundreds of short sections and the host keeps only the last 256
// messages, so results are not sent one message per section: they are
// gathered, grouped by section, and sent 100 at a time, or whatever is
// waiting once 250 ms have passed since the last message of the search. The
// clock is checked at each step of the engine (one per section); progress
// goes out on a tick that has no results to send; results carry it too.
//
// One search runs at a time: a new search, a cancel, a clear or a new book
// stop the one running. The engine matches a whole section in one go, so a
// search stops at the end of the section it is matching, and what comes next
// waits for that: nothing of an old search is posted or drawn once a new one
// has begun. A cancel sends the results still waiting; the others drop them.
//
// The engine registers all the results of a section before handing them
// over. When the cap is reached in the middle of a section, or just before a
// section with results, or a search stops while a section is being matched,
// that section may be outlined with results the host was never sent.
//
// The engine outlines every result it found, in the section on screen and in
// each section as it loads; the current result, the one the host last showed,
// has a drawing of its own, filled as well as outlined. Both take the search
// colour of the settings through a CSS variable set on the view, so a theme
// switch recolours them without drawing again. Fixed-layout books have no overlayer: results are
// found and shown, nothing is drawn. Nothing is ever inserted into a book
// document.

import { clamp01, isCfi } from './values.js'

const CURRENT_KEY = 'lirelle:search-current'
const QUERY_MIN = 2
const QUERY_MAX = 200
const BATCH = 100
const CONTEXT_MAX = 200
const MATCH_MAX = 400
const CAP = 1000
const TICK_MS = 250
const ID_MAX = 2147483647
// How long a new book waits for the search of the old one to stop.
const STOP_WAIT_MS = 8000
const OUTLINE_WIDTH = 2
const OUTLINE_RADIUS = 3
const CURRENT_OPACITY = 0.5
const COLOUR = 'var(--lirelle-search-color)'
const SVG = 'http://www.w3.org/2000/svg'

const find = {
    view: null,
    Overlayer: null,
    post: () => {},
    // The label of a section, as the page reported it when the book opened.
    labelOf: () => null,
    // Bumped by every search, cancel, clear and book: a search that sees it change stops.
    token: 0,
    // The search running, and what waits for it to stop.
    tail: Promise.resolve(),
    // The results of the search running that wait to be sent: { searchId, groups, count, at }.
    batch: null,
    // The result the host last showed: { cfi, index }.
    current: null,
}

const warn = e => console.warn(e)

// ---------------------------------------------------------------- values

const codePoints = text => [...text].length

const isSearchId = value => Number.isInteger(value) && value >= 0 && value <= ID_MAX

// The query of a search, or null when it is not one: 2 to 200 characters,
// white space at either end aside.
const cleanQuery = raw => {
    if (typeof raw !== 'string') return null
    const query = raw.trim()
    const length = codePoints(query)
    return length >= QUERY_MIN && length <= QUERY_MAX ? query : null
}

// Never half of a character written with two code units.
const isHigh = code => code >= 0xd800 && code <= 0xdbff
const isLow = code => code >= 0xdc00 && code <= 0xdfff

const head = (text, max) => {
    if (text.length <= max) return text
    return text.slice(0, isHigh(text.charCodeAt(max - 1)) ? max - 1 : max)
}

const tail = (text, max) => {
    if (text.length <= max) return text
    const start = text.length - max
    return text.slice(isLow(text.charCodeAt(start)) ? start + 1 : start)
}

const text = value => typeof value === 'string' ? value : ''

const cleanResult = ({ cfi, excerpt }) => ({
    cfi,
    pre: tail(text(excerpt && excerpt.pre), CONTEXT_MAX),
    match: head(text(excerpt && excerpt.match), MATCH_MAX),
    post: head(text(excerpt && excerpt.post), CONTEXT_MAX),
})

// The section a CFI points into, or null when it points nowhere in this book.
const sectionOf = cfi => {
    try {
        const { index } = find.view.resolveCFI(cfi)
        return Number.isInteger(index) && index >= 0 && index < find.view.book.sections.length ? index : null
    } catch (e) {
        return null
    }
}

// ---------------------------------------------------------------- drawing

// Called by the overlayer at each draw and redraw, with fresh rects.
const drawResult = rects => {
    const g = find.Overlayer.outline(rects, { width: OUTLINE_WIDTH, radius: OUTLINE_RADIUS })
    g.removeAttribute('stroke')
    g.style.stroke = COLOUR
    g.setAttribute('data-lirelle', 'search')
    return g
}

// The current result: the search colour at about half strength over the text,
// blended as highlights are (multiply on a light theme, normal on a dark one),
// with an outline like the other results around it.
const drawCurrent = rects => {
    const g = document.createElementNS(SVG, 'g')
    g.setAttribute('data-lirelle', 'search-current')
    g.style.fill = COLOUR
    g.style.stroke = COLOUR
    const fill = find.Overlayer.highlight(rects)
    fill.removeAttribute('fill')
    fill.style.opacity = String(CURRENT_OPACITY)
    fill.setAttribute('data-lirelle', 'search-current-fill')
    const ring = find.Overlayer.outline(rects, { width: OUTLINE_WIDTH, radius: OUTLINE_RADIUS })
    ring.removeAttribute('stroke')
    ring.setAttribute('data-lirelle', 'search-current-ring')
    g.append(fill, ring)
    return g
}

const contents = () => {
    const { view } = find
    if (!view || !view.renderer || view.isFixedLayout) return []
    return view.renderer.getContents().filter(content => content.overlayer)
}

// Draws the current result where its section is on screen, and nowhere else.
const showCurrent = () => {
    const { current, view } = find
    for (const { doc, index, overlayer } of contents()) {
        if (!current || index !== current.index) {
            overlayer.remove(CURRENT_KEY)
            continue
        }
        try {
            const range = view.resolveCFI(current.cfi).anchor(doc)
            if (range) overlayer.add(CURRENT_KEY, range, drawCurrent)
        } catch (e) {
            warn(e)
        }
    }
}

const dropCurrent = () => {
    find.current = null
    showCurrent()
}

// The engine announces a section's overlayer before attaching it.
const onCreateOverlay = e => {
    const { current } = find
    if (current && current.index === e.detail.index) queueMicrotask(showCurrent)
}

// ---------------------------------------------------------------- batches

// `fraction` is how far the engine has got, sent with the results so that the
// progress moves while results flow.
const newBatch = searchId => ({ searchId, groups: [], count: 0, fraction: 0, at: performance.now() })

// The results of one section join the batch; returns how many did.
const gather = (batch, subitems) => {
    const results = subitems.filter(item => item && isCfi(item.cfi))
    if (!results.length) return 0
    const sectionIndex = sectionOf(results[0].cfi)
    if (sectionIndex === null) return 0
    const label = find.labelOf(sectionIndex)
    batch.groups.push({
        sectionIndex,
        label: typeof label === 'string' ? label : null,
        results: results.map(cleanResult),
    })
    batch.count += results.length
    return results.length
}

// Sends the first `limit` results waiting, or all of them; the rest of a
// section cut in two opens the next message.
const send = (batch, limit = batch.count) => {
    const groups = []
    let room = Math.min(limit, batch.count)
    while (room > 0) {
        const group = batch.groups[0]
        if (group.results.length <= room) {
            batch.groups.shift()
            groups.push(group)
            room -= group.results.length
            batch.count -= group.results.length
        } else {
            groups.push({ ...group, results: group.results.slice(0, room) })
            group.results = group.results.slice(room)
            batch.count -= room
            room = 0
        }
    }
    if (!groups.length) return
    batch.at = performance.now()
    find.post({ type: 'searchResults', searchId: batch.searchId, fraction: batch.fraction, groups })
}

const sendAll = batch => {
    while (batch.count > 0) send(batch, BATCH)
}

// The results waiting are forgotten: a new search, a clear, a new book.
const dropBatch = () => {
    const { batch } = find
    if (batch) Object.assign(batch, { groups: [], count: 0 })
    find.batch = null
}

// ---------------------------------------------------------------- the search

// One step of the engine: the results of a section, or how far it has got.
// Returns true when the cap is reached.
const step = (batch, item, state) => {
    if (typeof item.progress === 'number') batch.fraction = clamp01(item.progress)
    if (Array.isArray(item.subitems)) {
        const room = CAP - state.total
        const results = item.subitems.filter(result => result && isCfi(result.cfi))
        state.truncated = results.length > room
        state.total += gather(batch, state.truncated ? results.slice(0, room) : results)
        while (batch.count >= BATCH) send(batch, BATCH)
        if (state.truncated) return true
    }
    if (performance.now() - batch.at < TICK_MS) return false
    if (batch.count) send(batch)
    else if (typeof item.progress === 'number') {
        batch.at = performance.now()
        find.post({ type: 'searchProgress', searchId: batch.searchId, fraction: batch.fraction })
    }
    return false
}

const run = async (token, searchId, query) => {
    const live = () => token === find.token
    if (!live() || !find.view.book) return
    const batch = newBatch(searchId)
    find.batch = batch
    const state = { total: 0, truncated: false }
    try {
        for await (const item of find.view.search({ query, draw: drawResult })) {
            // Leaving the loop stops the engine's search.
            if (!live()) return
            if (item === 'done' || !item) break
            if (step(batch, item, state)) break
        }
    } catch (e) {
        warn(e)
    }
    if (!live()) return
    sendAll(batch)
    find.batch = null
    find.post({ type: 'searchDone', searchId, total: state.total, truncated: state.truncated })
}

// A query that is not one ends the search running and finds nothing.
const refuse = (token, searchId) => {
    if (token !== find.token) return
    if (find.view && find.view.book) find.view.clearSearch()
    find.post({ type: 'searchDone', searchId, total: 0, truncated: false })
}

// ---------------------------------------------------------------- the host's messages

// Starts a search; returns at once, the search runs on its own. An id that is
// not one drops the message; a query that is not one still stops the search
// running, and is answered with no result once it has stopped.
export const startSearch = message => {
    const { searchId } = message
    if (!isSearchId(searchId)) return
    const query = cleanQuery(message.query)
    const token = ++find.token
    dropBatch()
    dropCurrent()
    const next = query === null ? () => refuse(token, searchId) : () => run(token, searchId, query)
    find.tail = find.tail.then(next).catch(warn)
}

// Stops the search running, after sending what it found; it stays outlined.
export const cancelSearch = () => {
    find.token++
    if (find.batch) sendAll(find.batch)
    find.batch = null
}

// Stops the search running and removes everything it drew.
export const clearSearch = () => {
    find.token++
    dropBatch()
    dropCurrent()
    find.tail = find.tail.then(() => {
        if (find.view && find.view.book) find.view.clearSearch()
    }).catch(warn)
}

// Goes to a result, through `move` (a move by the host), and marks it as the
// current one. A CFI that points nowhere is ignored.
// The host counts its moves and awaits an answer to each: one that leads
// nowhere is answered too, with a move that goes nowhere.
export const showSearchResult = async (cfi, move) => {
    const index = isCfi(cfi) ? sectionOf(cfi) : null
    if (index === null) return move(() => {})
    find.current = { cfi, index }
    showCurrent()
    await move(() => find.view.goTo(cfi))
    // Same section: no new overlayer, so it is drawn here, where the page now is.
    if (find.current && find.current.cfi === cfi) showCurrent()
}

// Before a book is closed: stops the search, and resolves once it has stopped,
// or after a while. A search that never stops is let go: nothing waits on it
// again.
export const stopSearch = async () => {
    find.token++
    dropBatch()
    find.current = null
    let timer = 0
    const stopped = await Promise.race([
        find.tail.then(() => true),
        new Promise(resolve => { timer = setTimeout(() => resolve(false), STOP_WAIT_MS) }),
    ])
    clearTimeout(timer)
    if (!stopped) find.tail = Promise.resolve()
}

// The search colour of the settings, on the view, inherited by the overlayer.
export const applyFindSettings = settings => {
    if (find.view) find.view.style.setProperty('--lirelle-search-color', settings.searchColor)
}

// ---------------------------------------------------------------- set-up

export const attachFind = async (view, { post, labelOf }) => {
    const { Overlayer } = await import('./foliate/overlayer.js')
    find.view = view
    find.Overlayer = Overlayer
    find.post = post
    find.labelOf = labelOf
    view.addEventListener('create-overlay', onCreateOverlay)
}
