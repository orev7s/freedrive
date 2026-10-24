# FreeDrive

**One virtual drive made of every free storage provider that hands out an API key.**

FreeDrive pools the free tiers of several cloud providers into a single drive inside one Android app.
You paste an API key from each provider, and the app spreads your files across all of them. Ten
gigabytes here, twenty there, and the number at the top of the screen is the sum.

It is RAID-0 over clouds. Capacity adds up. Redundancy does not exist.

---

## Why

Most "free storage" accounts sit empty because one provider is never enough and five providers are
five apps, five logins and five sets of folders. The free tiers are not the problem — the seams
between them are. FreeDrive deletes the seams: one file list, one folder tree, one upload button,
one number telling you how much room is left.

The same idea as pooling free LLM APIs behind one endpoint, applied to bytes instead of tokens.

## What it does today

- **Keys only.** No OAuth, no browser redirect dance. Paste an app password, a bucket key or a
  token, and the app checks it before saving.
- **Many accounts per provider.** `Koofr 1`, `Koofr 2`, `pCloud 1` — numbered automatically, each
  one independent, so a dead key only costs you that account.
- **Folders, uploads, previews.** Create folders, pick images from the system picker, and every file
  shows a thumbnail that opens into a full-screen preview with its provider, size, pixel dimensions
  and date.
- **A real upload queue.** Uploads live in SQLite, drained by an application-scoped worker. Ten
  files, app in the background, screen off — they keep going, one at a time, and progress is real
  bytes on the socket rather than a timer that pretends. Kill the process and the queue resumes.
- **Pick where it goes.** A provider selector above the grid, default `Any available` (largest free
  space wins, with a per-provider safety reserve). Choose one provider and it either takes the file
  or tells you exactly why it can't. "Remember" is a checkbox, off by default.
- **Sharing.** A link a friend can open in any browser, with an expiry you choose, plus an
  optional password on pCloud and Koofr links.
- **Search, sort, list or grid.** Search spans every folder and every provider; the sort order
  (newest, name, largest) and the layout are view state, so switching is instant.
- **Multi-select.** Long-press any file to start selecting; delete a batch in one go.
- **Save to device.** Any image can be written into `Pictures/FreeDrive` through MediaStore.
- **Move between providers.** Re-upload a file to another account, delete the old copy only after
  the new one is confirmed, and repoint the index.
- **Duplicate detection.** Identical bytes are recognised by SHA-256 and skipped instead of
  uploaded twice.
- **A link log.** Every URL handed out is recorded, so it can be found and forgotten later.
- **Per-provider usage bars** in Settings, plus Material You wallpaper colours on Android 12+.
- **Light, dark, or follow the system.** Material 3 throughout.

## Providers

| Provider | Shape | Free tier | Notes |
|---|---|---|---|
| Koofr | WebDAV | 10 GB | app password; most reliable of the lot |
| Backblaze B2 | S3 | 10 GB | key id + application key |
| IDrive e2 | S3 | 10 GB | S3 endpoint from the console |
| Cloudflare R2 | S3 | 10 GB | card required on the account |
| Oracle Cloud | S3 | 20 GB | access key + secret, region matters |
| pCloud | token REST | 10 GB | `auth` token, not the account password |
| Any WebDAV | WebDAV | your server | Nextcloud, Fastmail, a NAS |
| Telegram | Bot API | no published quota | chat storage; 20 MB per file |

Catalog total as shipped: **~80 GB**, counting Telegram's share as an app-side reservation rather
than a number Telegram enforces.

## How it works

Three adapter shapes cover the entire keys-only market, which is why seven providers fit in one
build without seven sets of quirks:

```
WebDAV  → PROPFIND / MKCOL / PUT / GET / DELETE   (Koofr, Nextcloud, anything RFC 4918)
S3      → SigV4-signed requests, path style        (B2, e2, R2, Oracle)
REST    → login token + JSON                       (pCloud, Telegram)
```

Every adapter implements the same four methods — `verify`, `put`, `get`, `delete` — so the queue,
the index and the UI never learn which provider they are talking to. `put` returns a locator that
gets persisted; the app addresses files by locator, never by provider-specific paths.

Data lives in Room: `providers` (one row per account, credentials, quota, last check), `entries`
(the virtual folder tree plus where each object actually is), `uploads` (the durable queue),
`shares` (links handed out).

## Sharing

Three routes, and the app picks the best one the file's provider allows:

- **Native link** — pCloud and Koofr issue the link themselves (`expire`, `maxdownloads`,
  `linkpassword`, short URLs). The friend downloads straight from the provider's CDN, and the link
  keeps working with your phone off. Koofr is configured as WebDAV for storage, but its links API
  is used for sharing — which also makes it a valid mirror target.
- **Signed link** — B2, e2, R2 and Oracle accept a presigned `GET`. The signature is scoped to one
  object for one window, and `7 days` is the hard ceiling because that is what SigV4 allows.
- **Wi-Fi handoff** — optional, off by default. The phone serves the file to another device on the
  same network and stops when the sheet closes. Useful for a big file when neither provider nor a
  server should be involved.

Two providers cannot mint links at all — Telegram (bot files are not addressable) and plain WebDAV
(no share API). For those the sheet offers two fallbacks instead of a dead end:

- **Mirror** — copy the file to one of your other providers that can link (pCloud, Koofr, or any
  S3 bucket), link from there, and give the copy the link's deadline so it is swept away with it.
- **Temporary host** — hand the bytes to a free anonymous upload site and use its URL:

  | Host | Keeps the file | Max size |
  |---|---|---|
  | Litterbox | 3 days | 1 GB |
  | tmpfiles.org | 1 hour | 100 MB |
  | uguu.se | 3 hours | 128 MB |

  These are other people's servers running on donations. The retention shown is what the site
  publishes, and the app says plainly that it cannot extend, protect or recover anything sent there.

The expiry you pick in the share sheet applies to the link *and* to the stored file, so there is no
state where a live URL points at something already deleted.

## Limits, stated plainly

- **No redundancy.** This is RAID-0. One provider losing one object loses that file. Nothing is
  replicated, and there is no parity. If a file matters, keep a copy somewhere that matters.
- **Telegram is chat storage.** Every image becomes an attachment in a chat. Clearing that chat
  deletes your files. Bot uploads are capped at 20 MB here because a bot can only *download* 20 MB
  — writing something that can never be read back would be a silent failure, not a feature.
- **Keys are stored on the device in plain text** in the app's private database (Room), which is
  protected by Android's app sandbox but not encrypted at rest beyond that. Use scoped app
  passwords and bucket keys that can be revoked, never your account password.
- **Free tiers are other people's money.** Read each provider's terms. Bulk-sharing a free bucket
  can cost someone egress.
- **Images only in this build.** The picker takes `image/*`.

## Build

```bash
# JDK 17, Android SDK platform 35 + build-tools 35.0.0
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. `minSdk` is 26, `targetSdk` is 35.
No Firebase, no analytics, no network calls except to the providers you configure.

## Layout

```
app/src/main/java/app/freedrive/
  data/
    Model.kt              provider catalog: fields, taglines, cautions, limits
    FreeDriveRepo.kt      index, placement, queue drain, sharing
    Share.kt              lifetimes, share kinds, link records
    adapters/             WebDavAdapter, S3Adapter, PCloudAdapter, TelegramAdapter
    db/Db.kt              Room entities, DAOs, migrations
    net/                  OkHttp wrapper, SigV4 signer, progress body
  ui/
    Root.kt, HomeScreen.kt, ProvidersScreen.kt, SettingsScreen.kt
    PreviewOverlay.kt, ShareSheet.kt, QueueSheet.kt, NewFolderDialog.kt
    TelegramTutorial.kt, AppState.kt
    theme/Theme.kt
```

## Roadmap

- A tiny broker (one Worker, no file bytes) so Telegram files can be shared as `t.me` deep links.
- Optional pin to R2 for files that get downloaded a lot.
- Video and arbitrary files, with chunking for anything above a provider's per-file cap.
- Folder links — one URL for a whole directory.

## License

MIT. See [LICENSE](LICENSE).
