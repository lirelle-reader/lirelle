<p align="center"><img src="art/lirelle.svg" width="128" alt="Lirelle"></p>

# Lirelle

Books, comics and audiobooks from your own [BookOrbit](https://github.com/bookorbit/bookorbit) or
[Komga](https://github.com/gotson/komga) server, on Android.

Lirelle is a client: it signs in to a server you or a friend runs, and reads, shows and plays what that server holds.

## What it does

- **Two kinds of servers**: BookOrbit, for everything below, and Komga, for comics. Each server and account is a
  profile of its own, and their data are kept apart; you switch from one to the other in two taps, from the profile
  button of the home screen.
- **Books**: EPUB, MOBI, AZW/AZW3 and FB2, paged or scrolled, with bookmarks, highlights, notes and search.
- **A reader to your liking**: themes (light, sepia, dark, black), fonts (serif, sans-serif, one made for dyslexic
  readers, or the book's own), text size, line spacing, margins, alignment and hyphenation, a header and a footer of
  your choice, brightness and page-turn gestures, volume keys.
- **Comics and manga** (CBZ, CBR, CB7) **and page-based documents** (PDF, DjVu): fit to the page or the width, two
  pages side by side, left-to-right or right-to-left reading set per series.
- **Comics panel by panel**: the panels are found on the device, with no server work and no editing by hand, and are
  shown one after the other; a page that does not cut well is shown whole.
- **Audiobooks**, with read-along for the books that carry it, audio bookmarks, playback speed and a sleep timer.
- **Offline**: download books, comics and audiobooks to read and listen without a network; your progress is sent to
  the server when the network is back.
- **Reading history**, and the progress made on your other devices.
- **English and French**, and layouts made for tablets as well as phones.

## What it does not do

- It is not a server: you need a BookOrbit or Komga server.
- It does not read files outside a server, and has no store, no purchases and no DRM.
- It does not manage the library (metadata, imports): that is the server's job.
- It does not mix several servers in one library: each profile shows its own.
- With Komga, it reads comics only: Komga's EPUBs and PDFs are not opened yet.
- Not yet: a webtoon mode for comics (continuous vertical scroll) and Android Auto.
- It runs on Android 14 or later only.

## Built with AI

Lirelle is developed with AI: most of its code is written by AI tools under the maintainer's direction, and every
change is reviewed and tested on real devices by a human before it ships. Contributions follow the same idea: see the
[AI policy](CONTRIBUTING.md#ai-policy).

## Requirements and install

- Android 14 or later.
- A BookOrbit or Komga server (tested with Komga 1.30).

Lirelle is not published yet. Its releases will be on GitHub, IzzyOnDroid, Google Play and F-Droid. Meanwhile, every
change to `main` is built as a development pre-release on the
[releases page](https://github.com/lirelle-reader/lirelle/releases): signed, but not a stable release.

## Building

It needs a JDK 25 that has `jlink` (the one shipped with Android Studio does) and the Android SDK.

```
export JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android/sdk
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" assembleDebug
```

The checks are `detekt`, `lintDebug` and `testDebugUnitTest`, with the same command.

## Contributing

Issues and pull requests are welcome: read [CONTRIBUTING.md](CONTRIBUTING.md) first. To report a vulnerability, see
[SECURITY.md](SECURITY.md). Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md). What the app does
with your data is in the [privacy policy](PRIVACY.md).

## Licence

Lirelle is free software, under the [GNU General Public License](LICENSE), version 3 or (at your option) any later
version. Copyright © 2026 François-Xavier Payet and contributors.

Some files come from other projects under their own licences (the reading engine under MIT, the reading fonts under
the SIL Open Font License, the Gradle wrapper under the Apache License 2.0): [`REUSE.toml`](REUSE.toml) says which,
and their texts are in [`LICENSES/`](LICENSES/).

Lirelle is not affiliated with BookOrbit or Komga.
