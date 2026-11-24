// Where things of a section's document are on the page, shared by the page's modules.

import { clamp01 } from './values.js'

// Rects of a section's document moved to the page's viewport.
export const onPage = (doc, rects) => {
    const frame = doc.defaultView && doc.defaultView.frameElement
    const box = frame ? frame.getBoundingClientRect() : { left: 0, top: 0, width: 0, height: 0 }
    const sx = frame && frame.offsetWidth ? box.width / frame.offsetWidth : 1
    const sy = frame && frame.offsetHeight ? box.height / frame.offsetHeight : 1
    return rects.map(r => ({
        left: box.left + r.left * sx, right: box.left + r.right * sx,
        top: box.top + r.top * sy, bottom: box.top + r.bottom * sy,
    }))
}

// The parent of an element, out of a shadow root to its host; null at the top.
const parentAcross = el => {
    if (el.parentElement) return el.parentElement
    const root = el.getRootNode()
    return root && root.host ? root.host : null
}

// The part of the window where a document's content shows: the window, cut by every box between the
// document's frame and the window that hides what overflows it. In a two-page spread on a wide window the
// paginator's container is narrower than the window, and its margins hide the neighbouring pages.
export const visibleBox = doc => {
    const box = { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight }
    const frame = doc.defaultView && doc.defaultView.frameElement
    for (let el = frame ? parentAcross(frame) : null; el; el = parentAcross(el)) {
        const style = getComputedStyle(el)
        if (style.overflowX === 'visible' && style.overflowY === 'visible') continue
        const r = el.getBoundingClientRect()
        box.left = Math.max(box.left, r.left)
        box.top = Math.max(box.top, r.top)
        box.right = Math.min(box.right, r.right)
        box.bottom = Math.min(box.bottom, r.bottom)
    }
    return box
}

// Whether a rect on the page is at least partly inside the viewport.
export const inViewport = r => r.right > 0 && r.left < window.innerWidth && r.bottom > 0 && r.top < window.innerHeight

// The box around rects on the page, in fractions of the page.
export const boxOf = placed => {
    const width = window.innerWidth
    const height = window.innerHeight
    return {
        left: clamp01(Math.min(...placed.map(r => r.left)) / width),
        top: clamp01(Math.min(...placed.map(r => r.top)) / height),
        right: clamp01(Math.max(...placed.map(r => r.right)) / width),
        bottom: clamp01(Math.max(...placed.map(r => r.bottom)) / height),
    }
}
