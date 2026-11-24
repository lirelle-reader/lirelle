// Where a note leads: once the page has gone there, the note's target flashes,
// so that the eye finds it (for an empty anchor, the paragraph around it). Two
// soft pulses of the search colour around the target, then a fade, about a
// second and a half in all; with reduced motion, a steady tint that fades. It is drawn in the overlayer under a key of its
// own, so nothing is inserted into the book's document, highlights are still
// hit-tested against their own list and the search drawings keep theirs.
// Fixed-layout books have no overlayer: nothing is drawn there.

const KEY = 'lirelle:note-target'
const COLOUR = 'var(--lirelle-search-color)'
const BLEND = 'var(--overlayer-highlight-blend-mode, normal)'
const SVG = 'http://www.w3.org/2000/svg'
const DURATION_MS = 1500
// Around the target's boxes, so that the tint does not stop at the edge of the letters.
const PAD_PX = 4
const RADIUS_PX = 6
const PULSES = [
    { opacity: 0 },
    { opacity: 0.45, offset: 0.15 },
    { opacity: 0.12, offset: 0.35 },
    { opacity: 0.45, offset: 0.55 },
    { opacity: 0.12, offset: 0.8 },
    { opacity: 0 },
]
const STEADY = [
    { opacity: 0.4 },
    { opacity: 0.4, offset: 0.6 },
    { opacity: 0 },
]

// Where an empty target (an anchor with nothing in it) is shown instead.
const AROUND = 'p, li, aside, dd, div, section'

// Whether something has a box to draw around.
const drawable = target => [...target.getClientRects()].some(r => r.width > 0 && r.height > 0)

const arrival = {
    view: null,
    // The drawing on screen: { overlayer, timer }.
    shown: null,
}

const reducedMotion = () => {
    try {
        return matchMedia('(prefers-reduced-motion: reduce)').matches
    } catch (e) {
        return false
    }
}

// Called by the overlayer at each draw and redraw, with fresh rects: a redraw
// goes on with the animation where it was.
const drawer = (started, frames) => rects => {
    const g = document.createElementNS(SVG, 'g')
    g.setAttribute('data-lirelle', 'note-target')
    g.style.fill = COLOUR
    g.style.mixBlendMode = BLEND
    g.style.opacity = '0'
    for (const { left, top, width, height } of rects) {
        if (!(width > 0 && height > 0)) continue
        const rect = document.createElementNS(SVG, 'rect')
        rect.setAttribute('x', left - PAD_PX)
        rect.setAttribute('y', top - PAD_PX)
        rect.setAttribute('width', width + PAD_PX * 2)
        rect.setAttribute('height', height + PAD_PX * 2)
        rect.setAttribute('rx', RADIUS_PX)
        g.append(rect)
    }
    try {
        g.animate(frames, {
            duration: DURATION_MS, delay: started - performance.now(), fill: 'forwards', easing: 'ease-in-out',
        })
    } catch (e) {
        console.warn(e)
    }
    return g
}

const stop = () => {
    const { shown } = arrival
    arrival.shown = null
    if (!shown) return
    clearTimeout(shown.timer)
    shown.overlayer.remove(KEY)
}

// Flashes what an internal link leads to, if the page shows it. Called once
// the page has gone there.
export const flashTarget = href => {
    stop()
    const { view } = arrival
    if (!view || !view.renderer || view.isFixedLayout) return
    let target = null
    let content = null
    try {
        const resolved = view.resolveNavigation(href)
        content = resolved && view.renderer.getContents().find(c => c.index === resolved.index && c.overlayer)
        target = content && typeof resolved.anchor === 'function' ? resolved.anchor(content.doc) : null
    } catch (e) {
        console.warn(e)
        return
    }
    // A link to a whole section has no target to show.
    if (!target || typeof target.getClientRects !== 'function') return
    if (!drawable(target)) {
        const node = target.nodeType === Node.ELEMENT_NODE ? target : target.startContainer
        const element = node && (node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement)
        target = element && element.closest ? element.closest(AROUND) : null
        if (!target || !drawable(target)) return
    }
    const { overlayer } = content
    overlayer.add(KEY, target, drawer(performance.now(), reducedMotion() ? STEADY : PULSES))
    arrival.shown = { overlayer, timer: setTimeout(stop, DURATION_MS) }
}

// A new book: nothing of the old one stays.
export const dropFlash = stop

export const attachArrival = view => {
    arrival.view = view
}
