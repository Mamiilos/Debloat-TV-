# Playback history and Trakt (Android TV)

This experimental, opt-in feature complements the home screen's existing Continue Watching row. It is off by default and does not require a Trakt account for local history.

## On the TV

1. Keep **Home Settings → Top of the screen → Picks** for recommendations. With history enabled, a second **Continue Watching** row appears below them, using the home screen's own cards, progress bars, app names and remote navigation. The three sections have generous vertical spacing; Down/Up scrolls smoothly between recommendations, Continue Watching and apps. Home returns to the recommendations. Choosing **Continue Watching** as the top content instead keeps the existing larger row. Apps control which titles, pictures and resume links appear.
2. Open **Home Settings → Playback history** and turn **Playback history** on. Allow TV listings when Android asks. In Free My TV mode, the app also requests notification-listener access to read media sessions. Notification contents are never read.
3. Open a title in a streaming app, then return Home. The local history keeps up to 80 entries from Watch Next and media-session titles. Selecting a card follows the app's resume link when available; otherwise it opens the app. The streaming app decides where playback resumes.
4. To stop collection, turn Playback history off. **Clear history** removes Free My TV's saved entries and pending Trakt writes. It does not erase other apps' Watch Next rows, which can reappear on refresh. **Original** and the watchdog revoke media-session and TV-listings access.

The home row's app/content filters affect that row. Playback history has its own opt-in and records available media across apps. By default, artwork loads only from `content://` or `android.resource://`; unavailable pictures fall back to the app icon. The separate **TMDB images** toggle is off by default. Turning it on sends titles and, when available, season/episode numbers to TMDB using the existing TMDB key. Exact title/alias matches select an episode still; movies use their backdrop, and a missing episode still can use the series backdrop. These are catalog images, not live screenshots of the player. Ambiguous titles keep the app banner. Arbitrary network image URLs published by apps are never downloaded. Only the existing TMDB API and `image.tmdb.org` are used. A bounded private image cache reduces repeated requests; turning the option off stops new lookups, and Android can evict the cache. Builds can set the explicit opt-in `watch_images_tmdb=true`; generic builds default to false. Generic recommendations and next-episode suggestions are not recorded as playback.

## Connect Trakt

Open **Home Settings → Playback history → Trakt → Connect Trakt**. Scan the QR code on your phone, or visit [Trakt activation](https://auth.trakt.tv/activate), enter the TV's code and approve access on Trakt. Free My TV never asks for your Trakt password. Disconnect revokes the token when the service is reachable and clears it locally.

This draft does not ship a registered Trakt application ID. Before first login, register an application at [Trakt applications](https://trakt.tv/oauth/applications), using `https://freemytv.io` as the redirect URI, and enter its **Client ID** under **Trakt Client ID**. The setup screen also has a one-time QR page to paste the ID from a phone on the same network. It expires after five minutes. Do not enter a Client Secret: the current device flow does not need one. A maintainer can supply the public application ID through the optional `trakt_client_id` build setting; end users could then go straight to account authorization.

Login enables **Record completed watches**, which can be turned off independently. Playback history must also be enabled. Tokens stay in the app's private preferences, excluded from Android backup. No credentials or personal viewing history belong in Git.

## What gets sent

Automatic registration uses delayed `/sync/history` writes, not live scrobbling. A new Watch Next update must match a recently paused or ended media session from the same app, including its ending position. At least 80% of a title's duration must have been observed advancing, and the final position must also reach 80%. Seeks, pauses, stale samples and process suspension do not count as watched time. Starting collection late, restarting the app or changing an unidentified episode can lose evidence; the feature prefers missing a registration over inventing one.

Only a movie or a numbered TV episode can be registered. Exact titles/aliases must resolve unambiguously in Trakt; episode runtime is checked when supplied. Uncertain matches stay pending. Long-press a card and choose **Identify on Trakt** to select its catalog match. This resolves identity only; it does not bypass the playback threshold. The queue is bounded and scoped to the current login; existing Watch Next entries are never bulk-imported into Trakt history. Before a write or retry, the app checks Trakt history around the recorded timestamp to avoid duplicating a successful write whose response was lost.

After connection, title searches, catalog identifiers and completed-watch timestamps go to `api.trakt.tv`. There are no Trakt calls while disconnected, except an explicit login attempt or best-effort token revocation. Turning history or syncing off stops new automatic history writes; an HTTP request already sent can finish.

## Limits and validation

Apps can omit titles, durations, progress or links. Netflix has been observed to publish episode identity to Watch Next only after returning Home; its live media session may omit identity. No promise is made of real-time Netflix identification, universal app support or an exact resume position. A fully completed episode that disappears from Watch Next before it can be matched cannot be registered safely. Multiple profiles on the same TV can share provider data; only connect the Trakt account intended for that TV's viewing.

`bash tools/test-playback.sh` checks coverage, seeks, pauses, stale positions and OAuth polling/cancellation policy without credentials. `tools/check.sh` also builds the APK and validates translations and the web packages. Real device OAuth, refresh, disconnect, network retry and a complete watched-title round trip need a registered Client ID and user authorization; unit tests do not establish those integration results.

API references: [Trakt OAuth](https://developer.trakt.tv/docs/authentication-oauth), [current API schema](https://developer.trakt.tv/openapi.json), [Android Watch Next guidelines](https://developer.android.com/training/tv/discovery/guidelines-app-developers).
