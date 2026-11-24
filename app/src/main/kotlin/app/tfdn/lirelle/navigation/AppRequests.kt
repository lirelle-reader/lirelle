package app.tfdn.lirelle.navigation

/**
 * What is asked for from outside the screens, by the notifications.
 *
 * @property reader the readers asked for: a narration's notification.
 * @property downloads the Downloads destination asked for: the notification of a download that failed.
 */
class AppRequests(val reader: ReaderRequests, val downloads: DownloadsRequests)
