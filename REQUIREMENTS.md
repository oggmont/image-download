# Requirements

A one-window desktop app that downloads a numbered image series. One user, one machine, launched from VS Code.

## User flow

1. Open the repo in VS Code.
2. Press **F5** (or ▶ in Run and Debug, "Image Downloader (UI)"). The window opens.
3. Fill in the four inputs and click **Download**.
4. The loop runs. After each request the status bar shows the result, the request list grows, and the viewer
   shows the image that was just saved. New files appear in `downloads/` as they arrive.
5. **Stop** ends the run early. **Reset inputs** puts the configured defaults back.

## The loop

The window runs the original loop, with two bugs fixed (see below):

```java
for (int i = initialI; i <= lastI; i++) {
    String format = String.format("%04d", i);
    String downloadFileName = prefix + "_" + format + ".jpg";
    String url = urlToDownload + format + ".jpg";
    download(url, "downloads/" + downloadFileName);
    pause();   // 1.5 to 3.0 s, random
}
```

Fixed compared with the original snippet:
- The original did `urlToDownload += format + ".jpg"`, so each URL kept the previous ones
  (`…_0000.jpg0001.jpg`). Now every URL is built fresh from the base.
- The original stopped at `i < lastI`. In the window, **Last i is inclusive**: 999 means the last file is `_0999`.

## Functional requirements

| # | Requirement | Where |
|---|---|---|
| F1 | Launch the window with one click from VS Code. | `.vscode/launch.json` (first configuration), `DownloaderApp.main` |
| F2 | Input **Initial i**: first number of the loop (whole number ≥ 0). | form |
| F3 | Input **Last i**: last number, inclusive, ≥ initial i. | form |
| F4 | Input **File name prefix**: the part before `_<i>.jpg`. No `/`, `\`, or leading dot. | form |
| F5 | Input **URL to download**: incomplete URL; `<i padded to 4>.jpg` is appended. Must start with `http://` or `https://`. | form |
| F6 | Live preview of the first URL and file name, and of the image count, while typing. Invalid input is shown in red. | form |
| F7 | **Download** button (also Enter) validates the inputs, then starts the loop in the background. The window stays responsive. | `DownloadWorker` |
| F8 | **Reset inputs** button restores the defaults from `download.properties` / `download.local.properties`. | form |
| F9 | **Stop** button ends the run right away. A request in flight is abandoned and leaves no partial file. | `SwingWorker.cancel` |
| F10 | **Status bar** shows the latest result (OK / MISSING / FAILED, file, type and size), the running totals, which number is being requested and the countdown to the next request. A progress bar shows `done / total`. | status bar |
| F11 | **Viewer** shows the latest downloaded image, scaled to fit, with its name, pixel size and file size. If the server returned something that isn't an image, the viewer says so. | `ImageViewer` |
| F12 | Images are saved to `downloads/` as `<prefix>_<i>.jpg`. Each is written through a temp file, so an interrupted request never leaves half an image. | `ImageDownloader` |
| F13 | A request log, `download-requests.log` in the repo root, gets one line per request (time, number, result, HTTP status, URL, file, details) plus a start and end line per run. | `RequestLog` |
| F14 | A failure on one number never stops the run. 429/5xx/network errors are retried (2 extra attempts, backoff); a 404 is recorded as MISSING. | `ImageDownloader` |
| F15 | The last typed inputs are remembered between launches. | `ui.local.properties` |
| F16 | **Open folder** button opens `downloads/` in Finder. | form |

## Non-functional requirements

| # | Requirement |
|---|---|
| N1 | **Polite pace**: 1.5 s plus a random 0–1.5 s between requests (`delay.ms`, `delay.jitter.ms`). Retries wait at least 1 s and double each time. |
| N2 | **Privacy** (see below). |
| N3 | Java 17+, no runtime dependencies: Swing and `java.net.http.HttpClient` from the JDK. |
| N4 | Real URLs, cookies, typed inputs and the request log never get committed. They're all git-ignored. |
| N5 | Unit tests run offline against a local HTTP server (`./mvnw test`). |

## Privacy: what is hidden and what isn't

What the app does:
- **User-Agent**: sends a common Chrome-on-Windows User-Agent instead of the JDK's `Java-HttpClient/<version>`,
  so requests don't name the OS, the Java version, or that they come from a script. It also sends ordinary
  browser `Accept` and `Accept-Language` headers.
- **No Referer and no cookies** unless you configure them. No other headers are added.
- **Randomized timing** (N1), so requests don't arrive at an exact, script-like rhythm.
- **Optional proxy**: `proxy=host:port` in `download.local.properties` routes requests through an HTTP proxy,
  for example the local proxy of a VPN app or of Tor (with Privoxy), so the site sees the proxy's IP, not yours.
- **Nothing about your Mac in the files**: the log holds no user name, host name or machine details, and
  downloads are saved byte for byte, with no metadata added.

What the app can't hide, so don't rely on it alone:
- **Your IP address.** Without a proxy or VPN the site sees your public IP. That is the biggest identifier.
  Use a system-wide VPN, or set `proxy=`.
- **DNS lookups** go through your system resolver, unless a VPN handles DNS.
- **The TLS fingerprint** of Java's HTTP client is different from a real browser's. A site that checks for it
  can tell this isn't Chrome, whatever the User-Agent says.
- **The request pattern itself.** A long run of sequential numbers is recognizable however slow it goes.
  Raising `delay.ms` / `delay.jitter.ms` helps.

## Out of scope

- Downloading several numbers in parallel (it would defeat N1).
- Formats other than the `<prefix>_<i>.jpg` / `<url><i>.jpg` pattern from the window. The config file still
  accepts full `{n}` templates for other patterns.
