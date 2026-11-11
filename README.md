# image-download

A small desktop window that downloads a numbered series of images, such as `image_0000.jpg` up to
`image_0999.jpg`, into `downloads/`. You start it with one command, type four values and click
**Download**. While it runs, it shows each result and the latest image.

It needs Java 17 or newer. It has no runtime dependencies: the window is Swing and HTTP uses the JDK's built-in client.
The full list of requirements, including what the privacy measures do and don't cover, is in
[REQUIREMENTS.md](REQUIREMENTS.md).

## Quick start

1. Install a JDK, version 17 or newer (`java -version`).
2. From this folder, run `./mvnw -q compile exec:java` (`mvnw.cmd` on Windows). The window opens.

From an IDE, run `org.ogm.imagedownload.DownloaderApp` as a Java application.

## The window

```
┌ Inputs ──────────────────────────────────────────────────────────────────────┐
│ Initial i         [0      ]  first number of the loop                        │
│ Last i            [999    ]  last number, inclusive                          │
│ File name prefix  [image  ]  saved as <prefix>_0000.jpg in downloads/        │
│ URL to download   [https://…/1000/image_ ]  0000.jpg, 0001.jpg … is appended │
│ First: GET https://…/image_0000.jpg → downloads/image_0000.jpg (1000 images) │
│ [Download] [Stop] [Reset inputs] [Open folder]                               │
├ Requests ─────────────────────────────┬ Latest image ────────────────────────┤
│ OK       image_0000.jpg  image/jpeg…  │                                      │
│ MISSING  image_0001.jpg  HTTP 404     │            (image preview)           │
│ OK       image_0002.jpg  image/jpeg…  │  image_0002.jpg · 1000×1500 · 182 KB │
├───────────────────────────────────────┴──────────────────────────────────────┤
│ OK #0002 image_0002.jpg (image/jpeg, 182.4 KB) | next request in 2.3 s  3/1000│
└──────────────────────────────────────────────────────────────────────────────┘
```

| Input | Meaning |
|---|---|
| **Initial i** | First number of the loop. |
| **Last i** | Last number, **inclusive**. With 999 the last file is `_0999`. |
| **File name prefix** | The part before `_<i>.jpg`. With `image` you get `downloads/image_0007.jpg`. |
| **URL to download** | Incomplete URL. The loop appends `<i padded to 4 digits>.jpg`. With `https://…/image_` you get `https://…/image_0007.jpg`. |

- **Download** (or Enter) checks the inputs and starts. The line under the inputs previews the first URL and
  file name while you type, and shows any problem in red.
- **Stop** ends the run right away. **Reset inputs** restores the configured defaults. **Open folder** opens `downloads/`.
- The **status bar** shows the latest result, the running totals, the number being requested and the countdown to
  the next request. The **viewer** shows the image that was just saved.
- The window remembers what you last typed, in `ui.local.properties`, which is git-ignored.

## Request log

Every request is appended to **`download-requests.log`** in the repo root. The file is git-ignored.

```
=== 2026-11-11 17:30:02 run started: 0000..0999 (1000 numbers), url https://…/image_{n}.jpg, file image_{n}.jpg, folder downloads, delay 1500+1500 ms
2026-11-11 17:30:03  #0  DOWNLOADED 200  GET https://…/image_0000.jpg -> downloads/image_0000.jpg  image/jpeg, 182.4 KB
2026-11-11 17:30:05  #1  NOT_FOUND  404  GET https://…/image_0001.jpg -> downloads/image_0001.jpg  HTTP 404
=== 2026-11-11 18:12:40 run finished: 812 downloaded, 0 skipped, 188 not found, 0 failed
```

## Pace and privacy

- **Delay:** 1.5 s plus a random 0 to 1.5 s between requests, so 1000 images take about 40 minutes. To go slower,
  set `delay.ms` / `delay.jitter.ms`.
- **Headers:** requests send a common browser User-Agent, `Accept` and `Accept-Language`, instead of
  `Java-HttpClient/<version>`. They send no Referer and no cookies unless you configure them.
- **Your IP is still visible** to the site unless you use a VPN, or set `proxy=host:port` to send requests through
  an HTTP proxy (for example a VPN app's or Tor's local proxy). Java's TLS fingerprint also differs from a real
  browser's. See [REQUIREMENTS.md](REQUIREMENTS.md#privacy-what-is-hidden-and-what-isnt) for the details.

## What happens for each number

For each number `i` from initial to last, inclusive:

1. **Pad** it to 4 digits, so `7` becomes `0007`.
2. **Build** the URL (`<url>0007.jpg`) and the file name (`<prefix>_0007.jpg`).
3. **Normalize the URL**: decode `%xx` escapes, keeping `+` as a plus sign, then re-encode the URL so it's valid.
4. **HTTP GET** it. Redirects are followed.
   - **200**: the body is streamed to a temporary `.part` file and then moved into `downloads/`, so an
     interrupted download never leaves half an image. The viewer shows the image.
   - **404 or another 4xx**: that number doesn't exist. No file is created, and the result is `MISSING`.
   - **429, 5xx or a network error**: the request is retried up to 2 more times, waiting at least 1 s and doubling
     each time. If every attempt fails, the result is `FAILED` and the run **moves on**.
5. **Log** the result and show it in the window.
6. **Wait** for the randomized delay before the next request.

## Configuration

The window takes the four inputs. Everything else comes from properties files. Each layer overrides the one before it:

1. `src/main/resources/download.properties`: the committed defaults.
2. `download.local.properties` in the project root: **your own values**. It's git-ignored. Copy
   `download.local.properties.example` to start.
3. `config=path/to/other.properties`, JVM properties such as `-Ddownload.delay.ms=3000`, and `key=value` arguments.

| Key | Default | Meaning |
|---|---|---|
| `url.template` | `https://test.com/media/b/r/name/1000/image_{n}.jpg` | Remote URL. Must contain `{n}`. The window's URL field starts with this value, minus `{n}.jpg`. |
| `file.template` | `name_{n}.jpg` | Local file name. Must contain `{n}`. The window's prefix field starts with this value, minus `_{n}.jpg`. |
| `start` / `end` | `0` / `999` | Range of numbers, inclusive. These are the window's starting values. |
| `padding` | `4` | Number of zero-padded digits (`0` means no padding). |
| `output.dir` | `downloads` | Destination folder, relative to the working directory. |
| `delay.ms` | `1500` | Minimum pause between requests. |
| `delay.jitter.ms` | `1500` | Random extra pause, from 0 up to this value, added to each request. |
| `overwrite` | `true` | `false` skips files that already exist, which is useful to resume a run. |
| `retries` | `2` | Extra attempts on network errors, 429 and 5xx responses. |
| `timeout.seconds` | `30` | Connect and request timeout. |
| `stop.after.misses` | `0` | Stop after this many 404s in a row (`0` means never stop). |
| `log.file` | `download-requests.log` | Request log, relative to the working directory. `none` turns it off. |
| `proxy` | — | HTTP proxy as `host:port`, for example `127.0.0.1:8118`. |
| `header.<Name>` | browser `User-Agent`, `Accept`, `Accept-Language` | Adds a request header, or replaces a default one. For example `header.Referer=…`. |
| `cookie.<name>` | — | Cookie, for example `cookie.session_id=…`. |

## Without the window

The console version, `Main`, uses the same engine and the same log, with values taken only from the properties:

```bash
./mvnw test                                                # unit tests only (no network)
./mvnw -q compile exec:java                                # opens the window
./mvnw -q compile exec:java -Dexec.mainClass=org.ogm.imagedownload.Main -Dexec.args="start=0 end=9"
./mvnw test -Pdownload                                     # console download via RunDownloadTest
./mvnw -q package -DskipTests && java -jar target/image-download-1.0.0-SNAPSHOT.jar   # window from the jar
```

`./mvnw` downloads Maven the first time it runs, so you don't need to install Maven yourself.

## Project layout

```
src/main/java/org/ogm/imagedownload/
  DownloaderApp.java     the window: inputs, buttons, status bar, request list, background worker
  ImageViewer.java       the "latest image" panel
  ImageDownloader.java   the download loop (pause, retries, atomic writes, progress listener)
  RequestLog.java        download-requests.log
  DownloadConfig.java    settings, their defaults and how they're loaded
  DownloadResult.java    outcome for each number
  Main.java              console version
src/main/resources/download.properties   default settings
src/test/java/org/ogm/imagedownload/
  ImageDownloaderTest.java   unit tests (local HTTP server)
  RunDownloadTest.java       console download as a test you can run from an IDE
REQUIREMENTS.md          what the app must do, including privacy limits
```
