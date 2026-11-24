// The labels the page hands to the host: the entries of the contents, the
// chapter on screen, the chapters of what a search finds.
//
// A book names its chapters as it likes, and one without titles is named by
// the engine after the first paragraph of each section. So a label is made
// short before it leaves the page, and an entry without one is left out.

const LABEL_MAX = 80

// A cut between words keeps at least this much of the label; before that, a
// word as long as a label is cut where the room ends.
const WORD_CUT_MIN = LABEL_MAX / 2

const ELLIPSIS = '…'

// A label as the host shows it: its white space made single, 80 characters at
// most, cut between words where there are some and ending with an ellipsis
// when cut. Empty when there is nothing to show.
export const shortLabel = value => {
    const text = String(value ?? '').replace(/\s+/g, ' ').trim()
    const chars = Array.from(text)
    if (chars.length <= LABEL_MAX) return text
    const room = LABEL_MAX - 1
    let end = room
    if (chars[room] !== ' ') {
        const space = chars.lastIndexOf(' ', room - 1)
        if (space >= WORD_CUT_MIN) end = space
    }
    return chars.slice(0, end).join('').trimEnd() + ELLIPSIS
}

// The contents as one list, each entry with its depth and its short label. An
// entry without a label stays in the list, for the section it names is still
// one of the book, but it is no level of the contents: the entries under it
// take its depth. The host is given the entries that have a label.
export const contentsEntries = (items, depth = 0, out = []) => {
    for (const item of items || []) {
        const label = shortLabel(item.label)
        out.push({ id: item.id, label, href: String(item.href ?? ''), depth })
        contentsEntries(item.subitems, label ? depth + 1 : depth, out)
    }
    return out
}
