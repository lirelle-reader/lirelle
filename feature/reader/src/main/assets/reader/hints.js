// A resource hint (`<link rel="preconnect">` and the like) makes the browser
// look a host up, connect to it or fetch from it by itself, for a host of the
// book's choosing. No directive of the content security policy covers the
// look-up and the connection, so the hints are taken out of each document of
// the book before it is shown; so is the switch that turns the browser's own
// look-ups for the links of a page back on. Positions in a book count the
// elements of its body, so a hint found there is made inert instead: it loses
// what makes it a hint and keeps its place.
const RESOURCE_HINTS = new Set(
    ['dns-prefetch', 'preconnect', 'prefetch', 'prerender', 'preload', 'modulepreload'])
const HINTED_TYPES = new Set(['application/xhtml+xml', 'text/html'])

const isResourceHint = link =>
    (link.getAttribute('rel') ?? '').toLowerCase().split(/\s+/).some(rel => RESOURCE_HINTS.has(rel))

// Takes the hints out of a document, in place. True when it held any.
export const dropHintsOf = doc => {
    const hints = [...doc.querySelectorAll('link')].filter(isResourceHint)
    for (const meta of doc.querySelectorAll('meta[http-equiv]'))
        if (meta.getAttribute('http-equiv').toLowerCase() === 'x-dns-prefetch-control') hints.push(meta)
    for (const hint of hints) {
        if (hint.closest('head')) hint.remove()
        else if (hint.localName === 'link') {
            hint.removeAttribute('rel')
            hint.removeAttribute('href')
        } else hint.removeAttribute('http-equiv')
    }
    return hints.length > 0
}

const withoutResourceHints = (text, type) => {
    if (typeof text !== 'string' || !/<(link|meta)\b/i.test(text)) return text
    const doc = new DOMParser().parseFromString(text, type)
    return dropHintsOf(doc) ? new XMLSerializer().serializeToString(doc) : text
}

// For the engine's `data` event, sent with each document as it is about to be
// given an address. A document that could not be read stays a failure.
export const dropResourceHints = e => {
    const { data, type } = e.detail
    if (!HINTED_TYPES.has(type)) return
    e.detail.data = Promise.resolve(data).then(text => withoutResourceHints(text, type))
}
