package app.tfdn.lirelle.feature.reader

import androidx.annotation.StringRes
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningSlot

/* The names of the choices offered in the reading settings. */

@get:StringRes
internal val ReaderTheme.label: Int
    get() = when (this) {
        ReaderTheme.System -> R.string.reader_theme_system
        ReaderTheme.Light -> R.string.reader_theme_light
        ReaderTheme.Sepia -> R.string.reader_theme_sepia
        ReaderTheme.Dark -> R.string.reader_theme_dark
        ReaderTheme.Black -> R.string.reader_theme_black
    }

@get:StringRes
internal val ReaderFont.label: Int
    get() = when (this) {
        ReaderFont.Book -> R.string.reader_font_book
        ReaderFont.Serif -> R.string.reader_font_serif
        ReaderFont.Sans -> R.string.reader_font_sans
        ReaderFont.Dyslexic -> R.string.reader_font_dyslexic
    }

@get:StringRes
internal val MarginSize.label: Int
    get() = when (this) {
        MarginSize.Narrow -> R.string.reader_margins_narrow
        MarginSize.Medium -> R.string.reader_margins_medium
        MarginSize.Wide -> R.string.reader_margins_wide
    }

@get:StringRes
internal val ReaderAlign.label: Int
    get() = when (this) {
        ReaderAlign.Book -> R.string.reader_align_book
        ReaderAlign.Justify -> R.string.reader_align_justify
        ReaderAlign.Start -> R.string.reader_align_start
    }

@get:StringRes
internal val ReaderFlow.label: Int
    get() = when (this) {
        ReaderFlow.Paginated -> R.string.reader_flow_paginated
        ReaderFlow.Scrolled -> R.string.reader_flow_scrolled
    }

@get:StringRes
internal val BrightnessEdge.label: Int
    get() = when (this) {
        BrightnessEdge.Left -> R.string.reader_edge_left
        BrightnessEdge.Right -> R.string.reader_edge_right
        BrightnessEdge.Off -> R.string.reader_edge_off
    }

@get:StringRes
internal val RunningSlot.label: Int
    get() = when (this) {
        RunningSlot.Nothing -> R.string.reader_slot_nothing
        RunningSlot.BookTitle -> R.string.reader_slot_book_title
        RunningSlot.ChapterTitle -> R.string.reader_slot_chapter_title
        RunningSlot.BookPercent -> R.string.reader_slot_book_percent
        RunningSlot.ChapterPercent -> R.string.reader_slot_chapter_percent
        RunningSlot.PagesLeftInChapter -> R.string.reader_slot_pages_left
        RunningSlot.Time -> R.string.reader_slot_time
        RunningSlot.Battery -> R.string.reader_slot_battery
    }
