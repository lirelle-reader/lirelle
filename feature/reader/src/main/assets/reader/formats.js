// The books the host gives as one file: a Kindle book (MOBI, AZW, AZW3) and a
// FictionBook (FB2). The engine reads each from a `Blob` of the whole file,
// which the page fetches once from `<base>file`; whether a Kindle book is of
// the old kind or of the newer one (KF8) is the engine's to tell.

import { dropHintsOf, dropResourceHints } from './hints.js'

// In a Palm database, where the number of its records is and where their list
// starts, eight bytes a record, its offset first; in the header of the first
// record, where the book says how its text is encrypted (0: it is not).
const RECORD_COUNT = 76
const RECORD_LIST = 78
const RECORD_ENTRY = 8
const ENCRYPTION = 12
const FICTION_BOOK = 'FictionBook'

// The file is not a book the engine reads: protected, damaged, or something else under that name.
export class UnreadableBook extends Error {}

const fetchWhole = async base => {
    const response = await fetch(base + 'file')
    if (!response.ok) throw new Error(`Book file not available (${response.status})`)
    return response.blob()
}

const uintAt = async (file, offset, bytes) => {
    const buffer = await file.slice(offset, offset + bytes).arrayBuffer()
    if (buffer.byteLength < bytes) return null
    const view = new DataView(buffer)
    return bytes === 2 ? view.getUint16(0) : view.getUint32(0)
}

// The engine does not look at the field: it would decode the encrypted text as it is and show it.
const isEncrypted = async file => {
    const first = await uintAt(file, RECORD_LIST, 4)
    return first !== null && !!await uintAt(file, first + ENCRYPTION, 2)
}

// The engine reads past the end of a file cut short as if the records there were empty, and opens a book with
// nothing in it.
const isCutShort = async file => {
    const count = await uintAt(file, RECORD_COUNT, 2)
    if (!count) return true
    const last = await uintAt(file, RECORD_LIST + (count - 1) * RECORD_ENTRY, 4)
    return last === null || last >= file.size
}

// What the engine writes each document of a Kindle book with, on its way to
// an address: the resource hints go first (see hints.js).
const hintlessSerializer = () => {
    const serializer = new XMLSerializer()
    return {
        serializeToString: doc => {
            dropHintsOf(doc)
            return serializer.serializeToString(doc)
        },
    }
}

const openKindle = async file => {
    const { isMOBI, MOBI } = await import('./foliate/mobi.js')
    if (!await isMOBI(file)) throw new Error('The file is not a Kindle book')
    if (await isCutShort(file)) throw new Error('The file is cut short')
    if (await isEncrypted(file)) throw new Error('The book is protected')
    const { unzlibSync } = await import('./foliate/vendor/fflate.js')
    const book = await new MOBI({ unzlib: unzlibSync }).open(file)
    book.serializer = hintlessSerializer()
    // The newer kind sends the style sheets and pictures it gives an address to, as an EPUB does.
    if (book.transformTarget) book.transformTarget.addEventListener('data', dropResourceHints)
    return book
}

// The engine takes what the browser could parse of a file that is not well-formed, error message included, and
// makes a book of it: the file is parsed here first, as the engine will, and must be a whole FictionBook. A text
// in a one-byte encoding has the same tags read as UTF-8, and the engine then reads it in the encoding its
// declaration names; one in UTF-16 has not, and is refused here: the engine would make an empty book of it.
const checkFictionBook = async file => {
    const text = new TextDecoder('utf-8').decode(await file.arrayBuffer())
    const doc = new DOMParser().parseFromString(text, 'application/xml')
    if (doc.getElementsByTagName('parsererror').length) throw new Error('The file is not well-formed')
    if (doc.documentElement.localName !== FICTION_BOOK) throw new Error('The file is not a FictionBook')
}

const openFictionBook = async file => {
    await checkFictionBook(file)
    const { makeFB2 } = await import('./foliate/fb2.js')
    return makeFB2(file)
}

// A section the engine can show, with something in it.
const isReadable = section => typeof section.load === 'function' && section.size > 0

// The book of `<base>file`, in the format the host says it has. A file that
// cannot be fetched is a failure like any other; one the engine cannot read
// is an `UnreadableBook`.
export const openWholeBook = async (base, format) => {
    const file = await fetchWhole(base)
    let book
    try {
        book = format === 'fb2' ? await openFictionBook(file) : await openKindle(file)
    } catch (e) {
        throw new UnreadableBook(String((e && e.message) || e), { cause: e })
    }
    if (!book.sections.some(isReadable)) throw new UnreadableBook('The book has nothing to read')
    return book
}
