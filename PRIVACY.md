# Privacy policy

*Last updated: 24 November 2026.* [Version française](PRIVACY.fr.md)

Lirelle is an Android app that reads, shows and plays the books, comics and audiobooks of a BookOrbit or Komga server
that you add to it. This policy says what the app does with your data.

## The developer collects nothing

Lirelle has no server of its own. It contains no analytics, no advertising, no crash reporting and no trackers. Nothing
about you or your use of the app is sent to the developer or to anyone else.

## What the app sends to your servers

Lirelle talks only to the servers you add, and to each of them it sends only what using it needs:

- **to sign in:** your user name and password, or an API key, or the server's own sign-in page (see below); and the
  name of your device (the name given to it in Android's settings, else its model), so that the server can label the
  session or the API key it creates for it;
- **while you read and listen:** your reading and listening progress, your reading and listening sessions (when they
  started and ended, and how far they went), your bookmarks, highlights and notes, your audio bookmarks, and the rating
  you choose to give a book you finished.

What a server does with these data is up to whoever runs it, under their own privacy policy. If you run your own
server, they stay with you.

**Signing in to Komga through its identity provider (OIDC):** the sign-in happens in a web view inside the app, on the
server's own sign-in page and the identity provider it uses. The app keeps only what the server gives it back to stay
signed in.

## What stays on your device

- the addresses of your servers and your profiles;
- your sessions, encrypted with a key kept in your device's keystore;
- your settings;
- the books, comics and audiobooks you download, and the covers kept in a cache;
- your reading history, and the progress and changes waiting to be sent when the network is back.

None of this goes to a cloud backup or to another device: the app opts out of Android's backup and device transfer.
Uninstalling the app deletes all of it.

## Permissions

- **Network access and network state:** to reach your servers, and to wait for a network (or for Wi-Fi, if you ask
  for it) before downloading.
- **Notifications:** to show the progress of downloads, and, rarely, to ask you to tap to resume playback.
- **Foreground service for media playback, and keeping the device awake:** so that an audiobook goes on playing with
  the screen off or while you use other apps.
- **User-initiated jobs, and starting at boot:** to run the downloads you start, and to pick them up again after the
  device restarts.
- **Vibration:** the short buzz when you shake the phone to extend the sleep timer. The motion sensor is read on the
  device, only while the sleep timer runs, and nothing about it leaves the device.

## Unencrypted connections

The app accepts servers reached over unencrypted HTTP, for servers on a local network. Over the internet, use a server
reached over HTTPS: otherwise what the app sends to it, your password included, can be read on the way.

## Children

The app itself suits readers of every age, and it collects nothing from anyone, children included. Lirelle has no
content of its own: it shows what the server holds for the account signed in, and the content is the responsibility
of whoever runs the server and the user. A server's owner can give a child an account that sees only the libraries
meant for them.

## Changes and contact

Changes to this policy are dated here, and their history is in the app's
[public repository](https://github.com/lirelle-reader/lirelle). For any question, write to
[conduct@tfdn.app](mailto:conduct@tfdn.app).
