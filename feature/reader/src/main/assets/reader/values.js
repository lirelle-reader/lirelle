// Small checks shared by the page's modules.

export const clamp01 = value => Math.min(1, Math.max(0, value))

// A colour as the host sends it: `#rrggbb`, in either case.
export const isColour = value => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value)

const CFI_MAX = 2048

// A CFI as the host sends it, before it is resolved against the book.
export const isCfi = value => typeof value === 'string' && value.length <= CFI_MAX && /^epubcfi\(.+\)$/.test(value)
