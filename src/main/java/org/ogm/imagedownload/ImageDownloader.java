package org.ogm.imagedownload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.ogm.imagedownload.DownloadResult.Status;

/**
 * Downloads a numbered series of files. For every number from {@code start} to {@code end}:
 * <ol>
 *   <li>pad the number (e.g. 7 → {@code 0007})</li>
 *   <li>build the local file name and the remote URL from their templates</li>
 *   <li>URL-decode the URL and re-encode it properly</li>
 *   <li>HTTP GET it; on 200 stream the body to the output folder, otherwise skip that number</li>
 *   <li>wait {@code delay} plus a random part of {@code delayJitter} before the next request</li>
 * </ol>
 * A failure on one number never stops the run; it is recorded in the {@link Summary}.
 */
public final class ImageDownloader {

    /** Sleeps between requests/retries. Replaceable in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** Progress callbacks. They run on the download thread. Only {@link #onResult} is required. */
    @FunctionalInterface
    public interface Listener {
        /** Called just before a number is requested (not called for skipped files). */
        default void beforeRequest(int number, String url, Path file) {
        }

        /** Called after every number with its outcome. */
        void onResult(DownloadResult result);

        /** Called before the pause between two requests. */
        default void beforeDelay(Duration delay) {
        }
    }

    private static final Duration MIN_RETRY_BACKOFF = Duration.ofSeconds(1);

    private final DownloadConfig config;
    private final HttpClient client;
    private final Sleeper sleeper;

    public ImageDownloader(DownloadConfig config) {
        this(config, defaultClient(config), d -> Thread.sleep(d.toMillis()));
    }

    ImageDownloader(DownloadConfig config, HttpClient client, Sleeper sleeper) {
        this.config = config;
        this.client = client;
        this.sleeper = sleeper;
    }

    private static HttpClient defaultClient(DownloadConfig config) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(config.timeout());
        if (!config.proxy().isEmpty()) {
            int colon = config.proxy().lastIndexOf(':');
            b.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(
                    config.proxy().substring(0, colon), Integer.parseInt(config.proxy().substring(colon + 1)))));
        }
        return b.build();
    }

    /** Runs the whole series, reporting progress to {@code listener}. Interrupting the thread stops it. */
    public Summary run(Listener listener) throws IOException {
        Files.createDirectories(config.outputDir());
        List<DownloadResult> results = new ArrayList<>(config.count());
        int consecutiveMisses = 0;
        boolean interrupted = false;

        for (int n = config.start(); n <= config.end(); n++) {
            DownloadResult result;
            try {
                result = downloadOne(n, listener);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                break;
            }
            results.add(result);
            listener.onResult(result);

            consecutiveMisses = result.status() == Status.NOT_FOUND ? consecutiveMisses + 1 : 0;
            if (config.stopAfterMisses() > 0 && consecutiveMisses >= config.stopAfterMisses()) {
                break;
            }

            // Pause only between real requests, as the original did after each download.
            Duration pause = nextDelay();
            if (n < config.end() && result.status() != Status.SKIPPED && !pause.isZero()) {
                listener.beforeDelay(pause);
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                }
            }
        }
        boolean stoppedEarly = config.stopAfterMisses() > 0 && consecutiveMisses >= config.stopAfterMisses();
        return new Summary(Collections.unmodifiableList(results), stoppedEarly, interrupted);
    }

    /** Downloads a single number. */
    public DownloadResult downloadOne(int number) throws InterruptedException {
        return downloadOne(number, r -> { });
    }

    private DownloadResult downloadOne(int number, Listener listener) throws InterruptedException {
        String rawUrl = config.urlFor(number);
        Path target = config.outputDir().resolve(config.fileNameFor(number));

        if (!config.overwrite() && Files.exists(target)) {
            return new DownloadResult(number, rawUrl, target, Status.SKIPPED, 0, "already exists");
        }

        URI uri;
        try {
            uri = normalize(rawUrl);
        } catch (IllegalArgumentException e) {
            return new DownloadResult(number, rawUrl, target, Status.FAILED, 0, "bad URL: " + e.getMessage());
        }

        listener.beforeRequest(number, uri.toString(), target);
        HttpRequest request = buildRequest(uri);
        String lastError = "";
        int lastStatus = 0;

        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            if (attempt > 0) {
                sleeper.sleep(backoff(attempt));
            }
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                lastStatus = response.statusCode();
                try (InputStream body = response.body()) {
                    if (lastStatus == 200) {
                        long bytes = writeAtomically(body, target);
                        String type = response.headers().firstValue("Content-Type").orElse("unknown type");
                        return new DownloadResult(number, uri.toString(), target, Status.DOWNLOADED, 200,
                                type + ", " + humanBytes(bytes));
                    }
                    // Drain the error body so the connection can be reused.
                    body.transferTo(OutputStream.nullOutputStream());
                }
                if (lastStatus != 429 && lastStatus < 500) {
                    // 404 and friends: this number simply does not exist; no point retrying.
                    return new DownloadResult(number, uri.toString(), target, Status.NOT_FOUND, lastStatus,
                            "HTTP " + lastStatus);
                }
                lastError = "HTTP " + lastStatus;
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
        }
        return new DownloadResult(number, uri.toString(), target, Status.FAILED, lastStatus,
                lastError + " (after " + (config.retries() + 1) + " attempt(s))");
    }

    private HttpRequest buildRequest(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().timeout(config.timeout());
        config.headers().forEach(b::header);
        if (!config.cookies().isEmpty()) {
            b.header("Cookie", config.cookies().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        return b.build();
    }

    /** {@code delay} plus a random amount in {@code [0, delayJitter]}, so requests don't arrive like a metronome. */
    Duration nextDelay() {
        long jitter = config.delayJitter().toMillis();
        return jitter == 0 ? config.delay()
                : config.delay().plusMillis(ThreadLocalRandom.current().nextLong(jitter + 1));
    }

    private Duration backoff(int attempt) {
        Duration base = config.delay().compareTo(MIN_RETRY_BACKOFF) > 0 ? config.delay() : MIN_RETRY_BACKOFF;
        return base.multipliedBy(1L << Math.min(attempt - 1, 5));
    }

    /** Writes to a temp file first, then moves it into place, so a broken download never leaves a half file. */
    private static long writeAtomically(InputStream body, Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName(), ".part");
        try {
            long bytes = Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return bytes;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Same idea as the original {@code UrlDecoder.urlDecode(url, charset, false)}: decode %xx escapes
     * (keeping '+' as a literal plus), then re-encode into a valid URI.
     */
    static URI normalize(String rawUrl) {
        String decoded = URLDecoder.decode(rawUrl.replace("+", "%2B"), StandardCharsets.UTF_8);
        try {
            @SuppressWarnings("deprecation") // new URL(String) is fine here; we only use it to split parts
            URL u = new URL(decoded);
            return new URI(u.getProtocol(), u.getUserInfo(), u.getHost(), u.getPort(), u.getPath(), u.getQuery(),
                    u.getRef());
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Result of a full run. */
    public record Summary(List<DownloadResult> results, boolean stoppedAfterMisses, boolean interrupted) {

        public long count(Status status) {
            return results.stream().filter(r -> r.status() == status).count();
        }

        public List<DownloadResult> failures() {
            return results.stream().filter(r -> r.status() == Status.FAILED).toList();
        }

        public boolean hasFailures() {
            return count(Status.FAILED) > 0;
        }
    }
}
