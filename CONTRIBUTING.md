# Contributing to Lirelle

Thank you for wanting to help. This page says how contributions are made, what is expected of them, and under which
terms they are accepted.

## Before you start

- Everything on GitHub happens in English: issues, pull requests, reviews and comments.
- Every pull request needs an issue first, trivial or not. Open the issue, say what you want to change and why, and
  wait until it is agreed before you start: it saves work that would not be merged.
- Bug reports and feature requests use the issue forms. Fill in every field: a report that says what you did, what you
  saw and what you expected, with the app's version, the server and its version, and the device, can be acted on.

## AI policy

Lirelle itself is developed with AI, and contributions may be too, with one exception.

- **Code and pull requests may be made with AI tools.** Whoever submits a pull request answers for it: they
  understand every change in it, have run the checks and the tests, and can explain and discuss it in review.
- **The text of an issue must be written by a human**, in their own words: a bug report says what *you* did, saw and
  expected; a feature request says what *you* need and why. Translation and spelling tools are fine; having a tool
  write the content is not.
- An issue that looks generated may be closed without discussion.

## Setting up

You need a JDK 25 that has `jlink` (the one shipped with Android Studio does) and the Android SDK. Build and check with:

```
export JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android/sdk
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" assembleDebug
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" detekt lintDebug testDebugUnitTest
scripts/check-hardcoded-text.sh
uvx reuse lint
```

A pull request is reviewed once all of them pass.

## Code rules

- Every text the app shows comes from string resources, never from a literal in the code
  (`scripts/check-hardcoded-text.sh` checks it). English is the source language; no other language is required from
  you.
- No text is cut or overflows, at any width or font size, on phones and on tablets: try the narrowest phone, a tablet
  in landscape, and the largest font size.
- A change of behaviour comes with its tests.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`…), in
  English.
- A pull request's title follows the same rules: it becomes the commit's subject when the pull request is squashed into
  `main`, and the release notes are made from those subjects.
- Code and comments follow the style of the code around them; `detekt` holds the rules it can check.

## Localisation

English is the source language, and the maintainer keeps the French translation. Other languages are welcome:

- open an issue first, as for any pull request;
- add a `values-<language>/strings.xml` beside each module's `values/strings.xml`, with every string translated;
- the language then shows by itself in Android's per-app language settings;
- translated texts must fit as the English ones do, at the largest font size.

## Licence of contributions

Lirelle is under the [GNU General Public License, version 3 or later](LICENSE). A contribution comes under the same
licence (inbound = outbound); there is no contributor licence agreement to sign, and you keep the copyright of what you
wrote.

A new dependency must come under a licence compatible with the GPL: the build fails on a licence it does not know, and
every dependency shows in the app's Open-source licences screen.

## Security and conduct

Report a vulnerability as [SECURITY.md](SECURITY.md) says, never in a public issue. Everyone taking part follows the
[code of conduct](CODE_OF_CONDUCT.md).
