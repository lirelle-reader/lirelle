package app.tfdn.lirelle.feature.settings.about

/** A name made like an artifact's coordinates, with no space to break at. */
const val CoordinatesName = "androidx.datastore:datastore-preferences-external-protobuf"

/** Names with a word longer than a line of a phone at the largest text size: nothing can keep it whole. */
val NamesWithAVeryLongWord = setOf("Guava InternalFutureFailureAccess and InternalFutures", "AnimatedVectorDrawable")

private const val ApacheExcerpt = "Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/\n\n" +
    "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n\n1. Definitions.\n\n" +
    "\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined by " +
    "Sections 1 through 9 of this document."

private const val FontNotice = "Copyright 2020 Braille Institute of America, Inc.\nNo Reserved Font Name.\n\n" +
    "-----------------------------------------------------------\n" +
    "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007\n" +
    "-----------------------------------------------------------\n\nPREAMBLE\n" +
    "The goals of the Open Font License (OFL) are to stimulate worldwide\n" +
    "development of collaborative font projects, to support the font creation\n" +
    "efforts of academic and linguistic communities."

private val Apache = Licence("Apache-2.0", "Apache License 2.0", ApacheExcerpt)
private val Bsd = Licence("BSD-3-Clause", "BSD 3-Clause \"New\" or \"Revised\" License", "Redistribution and use.")
private val Unnamed = Licence("Example Public Licence Terms", "Example Public Licence Terms", null)

private fun library(name: String, author: String?, vararg licences: Licence) =
    Component(id = "library:$name", name = name, author = author, bundled = false, licences = licences.toList())

/**
 * Components with the longest names, words, authors and chips of the real list: what the rows and the text
 * must hold without cutting anything.
 */
fun longNamedLicences(): Licences = Licences(
    bundled = listOf(
        Component(
            id = "bundled:atkinson-hyperlegible",
            name = "Atkinson Hyperlegible",
            author = "Braille Institute of America, Inc.",
            bundled = true,
            licences = listOf(Licence("OFL-1.1", "SIL Open Font License 1.1", FontNotice)),
        ),
        Component(
            id = "bundled:foliate-js",
            name = "foliate-js",
            author = "John Factotum",
            bundled = true,
            licences = listOf(Licence("MIT", "MIT License", "MIT License\n\nCopyright (c) 2022 John Factotum")),
        ),
    ),
    libraries = listOf(
        library(CoordinatesName, "The Android Open Source Project", Bsd, Apache),
        library("androidx.customview:poolingcontainer", "The Android Open Source Project", Apache),
        library("AnimatedVectorDrawable", "The Android Open Source Project", Apache),
        library("Guava InternalFutureFailureAccess and InternalFutures", "Kevin Bourrillion", Apache),
        library("javax.inject", null, Apache),
        library("kotlinx-serialization-json", "JetBrains Team", Apache),
        library("Lifecycle ViewModel Kotlin Extensions", "The Android Open Source Project", Apache, Bsd),
        library("Material Adaptive Navigation Suite", "Compose Multiplatform Team", Unnamed),
    ),
)

/** The id of the component whose name is [CoordinatesName]. */
const val CoordinatesId = "library:$CoordinatesName"
