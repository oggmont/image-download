# image-download

Downloads a numbered series of images, such as `brewi_0000.jpg` up to `brewi_0999.jpg`, into a local folder.
You can run it with one click in VS Code, or from the command line.

The project needs Java 17 or newer and has no runtime dependencies. It uses the JDK's built-in HTTP client.

## Quick start (VS Code)

1. Install a JDK, version 17 or newer (`java -version`), and the **Extension Pack for Java**
   (`vscjava.vscode-java-pack`). VS Code suggests the extension pack when you open the folder.
2. Open this folder in VS Code and wait for "Java: Ready" in the status bar.
3. Copy `download.local.properties.example` to `download.local.properties` and set your `url.template`.
4. Run it in any of these ways:

| How | Where |
|---|---|
| **▶ Run** link above `main` | `src/main/java/org/ogm/imagedownload/Main.java` |
| **▶** next to the test, like a JUnit test | `src/test/java/org/ogm/imagedownload/RunDownloadTest.java`, or the Testing panel (beaker icon) |
| **Run and Debug** panel (⇧⌘D), then pick a configuration and press F5 | `.vscode/launch.json`: *Download images*, *resume*, *first 10*, *ask for range* |
| **Terminal → Run Task…** | `.vscode/tasks.json`: *Download images (Maven)*, *Unit tests*, *Build jar* |

The images are saved to `downloads/` in the project root. That folder is git-ignored.

## Command line

```bash
./mvnw test                                                # unit tests only (no network)
./mvnw test -Pdownload                                     # the real download, same as the IDE test button
./mvnw -q compile exec:java                                # run Main
./mvnw -q compile exec:java -Dexec.args="start=0 end=9"    # with overrides

./mvnw -q package -DskipTests
java -jar target/image-download-1.0.0-SNAPSHOT.jar end=9 overwrite=false
```

`./mvnw` downloads Maven the first time it runs, so you don't need to install Maven yourself.

## What it does, step by step

For each number `n` from `start` to `end`, inclusive:

1. **Pad** the number to `padding` digits. With `padding=4`, `7` becomes `0007`.
2. **Build the file name** from `file.template`, replacing `{n}`. For example, `name_{n}.jpg` becomes `name_0007.jpg`.
3. **Build the URL** from `url.template`, replacing `{n}`. For example, `…/brewi_{n}.jpg` becomes `…/brewi_0007.jpg`.
4. **Normalize the URL**: decode `%xx` escapes, keeping `+` as a plus sign, then re-encode the URL so it's valid.
5. **Skip** the number if the file already exists and `overwrite=false`.
6. **HTTP GET** the URL, sending any configured headers and cookies. Redirects are followed.
   - **200**: the body is streamed to `<file>.part` and then moved to `downloads/<file>`, so an interrupted
     download never leaves a half-written image. Output: `OK name_0007.jpg image/jpeg, 182.4 KB`.
   - **404 or another 4xx**: that number doesn't exist. No file is created. Output: `MISSING`.
   - **429, 5xx or a network error**: the request is retried up to `retries` times, with the wait doubling each
     time (at least 1 s). If every attempt fails, the number is recorded as `FAILED` and the run **moves on**.
7. **Wait** `delay.ms` (300 ms by default) before the next request.
8. **Stop early** (optional) once `stop.after.misses` numbers in a row are missing.

At the end it prints a summary of how many files were downloaded, skipped, missing and failed, followed by
the URLs that failed. The exit code is `1` if any download failed, so the test button shows red in that case.

## Configuration

Each layer overrides the one before it:

1. `src/main/resources/download.properties`: the committed defaults.
2. `download.local.properties` in the project root: **your own values**. It's git-ignored.
3. `config=path/to/other.properties`: an extra file, for example one per series.
4. JVM properties with the `download.` prefix, for example `-Ddownload.end=9`.
5. Arguments in the form `key=value`, for example `start=100 end=199`. Use them in `launch.json` `args` or on the command line.

| Key | Default | Meaning |
|---|---|---|
| `url.template` | `https://test.com/media/b/r/name/1000/brewi_{n}.jpg` | Remote URL. Must contain `{n}`. |
| `file.template` | `name_{n}.jpg` | Local file name. Must contain `{n}`. |
| `start` / `end` | `0` / `999` | Range of numbers, inclusive. |
| `padding` | `4` | Number of zero-padded digits (`0` means no padding). |
| `output.dir` | `downloads` | Destination folder, relative to the working directory. |
| `delay.ms` | `300` | Pause between requests. |
| `overwrite` | `true` | `false` skips files that already exist, which is useful to resume a run. |
| `retries` | `2` | Extra attempts on network errors, 429 and 5xx responses. |
| `timeout.seconds` | `30` | Connect and request timeout. |
| `stop.after.misses` | `0` | Stop after this many 404s in a row (`0` means never stop). |
| `header.<Name>` | — | Request header, for example `header.User-Agent=Mozilla/5.0` or `header.Referer=…`. |
| `cookie.<name>` | — | Cookie, for example `cookie.session_id=…`. |

## Differences from the original `Testt#test`

The defaults reproduce the original behavior: the same range, padding, file names, folder and delay, and
existing files are overwritten. These are the improvements:

- **Dependencies:** RestAssured and JUnit 4 are replaced by `java.net.http.HttpClient`, with no runtime dependencies.
- **Configuration:** URLs, the range and other settings are configurable, so you no longer edit code and comment lines in and out.
- **Error handling:** one failed download no longer stops the whole loop, and temporary errors are retried.
- **Downloads:** files are streamed and written atomically, not loaded fully into memory. A 404 no longer deletes an existing file.
- **Output:** you get progress and a summary instead of `status is: true/false`.
- **New options:** you can resume a run (`overwrite=false`) and stop early on a run of missing numbers.
- **Testing:** real unit tests run against a local HTTP server. The download test that hits the network is
  kept out of `mvn test`.

## Project layout

```
src/main/java/org/ogm/imagedownload/
  Main.java              entry point and console output
  DownloadConfig.java    settings and how they're loaded
  ImageDownloader.java   the download loop
  DownloadResult.java    outcome for each number
src/main/resources/download.properties   default settings
src/test/java/org/ogm/imagedownload/
  ImageDownloaderTest.java   unit tests (local HTTP server)
  RunDownloadTest.java       "run button" test that does the real download
.vscode/                 launch configurations, tasks and settings
```
