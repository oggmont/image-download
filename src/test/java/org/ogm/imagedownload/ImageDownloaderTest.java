package org.ogm.imagedownload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ogm.imagedownload.DownloadResult.Status;

class ImageDownloaderTest {

    @TempDir
    Path out;

    private HttpServer server;
    private String base;
    /** path -> body; anything else returns 404. */
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final List<String> cookieHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger flakyFailuresLeft = new AtomicInteger();
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(exchange.getRequestURI().getRawPath());
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie != null) {
                cookieHeaders.add(cookie);
            }
            if (path.startsWith("/flaky") && flakyFailuresLeft.getAndDecrement() > 0) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = files.get(path.replace("/flaky", ""));
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DownloadConfig config(String urlTemplate, int start, int end, boolean overwrite, int stopAfterMisses,
            Map<String, String> cookies) {
        return new DownloadConfig(urlTemplate, "name_{n}.jpg", start, end, 4, out, Duration.ofMillis(300),
                overwrite, 2, Duration.ofSeconds(5), stopAfterMisses, Map.of(), cookies);
    }

    private ImageDownloader downloader(DownloadConfig c) {
        return new ImageDownloader(c, HttpClient.newHttpClient(), sleeps::add);
    }

    @Test
    void downloadsSeriesWithPaddedNamesLikeTheOriginal() throws IOException {
        files.put("/media/brewi_0000.jpg", bytes("zero"));
        files.put("/media/brewi_0002.jpg", bytes("two"));

        var summary = downloader(config(base + "/media/brewi_{n}.jpg", 0, 3, true, 0, Map.of()))
                .run(r -> { });

        assertEquals(List.of("/media/brewi_0000.jpg", "/media/brewi_0001.jpg", "/media/brewi_0002.jpg",
                "/media/brewi_0003.jpg"), requested);
        assertArrayEquals(bytes("zero"), Files.readAllBytes(out.resolve("name_0000.jpg")));
        assertArrayEquals(bytes("two"), Files.readAllBytes(out.resolve("name_0002.jpg")));
        assertFalse(Files.exists(out.resolve("name_0001.jpg")), "404 must not create a file");
        assertEquals(2, summary.count(Status.DOWNLOADED));
        assertEquals(2, summary.count(Status.NOT_FOUND));
        assertFalse(summary.hasFailures());
        // 300 ms pause between each of the 4 requests (not after the last one)
        assertEquals(List.of(Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300)), sleeps);
        try (var listing = Files.list(out)) {
            assertTrue(listing.noneMatch(p -> p.toString().endsWith(".part")), "no temp files left behind");
        }
    }

    @Test
    void overwriteFalseSkipsExistingFilesWithoutRequesting() throws IOException {
        files.put("/a_0005.jpg", bytes("new"));
        Files.write(out.resolve("name_0005.jpg"), bytes("old"));

        var summary = downloader(config(base + "/a_{n}.jpg", 5, 5, false, 0, Map.of())).run(r -> { });

        assertEquals(1, summary.count(Status.SKIPPED));
        assertTrue(requested.isEmpty());
        assertArrayEquals(bytes("old"), Files.readAllBytes(out.resolve("name_0005.jpg")));
    }

    @Test
    void overwriteTrueReplacesExistingFiles() throws IOException {
        files.put("/a_0005.jpg", bytes("new"));
        Files.write(out.resolve("name_0005.jpg"), bytes("old"));

        downloader(config(base + "/a_{n}.jpg", 5, 5, true, 0, Map.of())).run(r -> { });

        assertArrayEquals(bytes("new"), Files.readAllBytes(out.resolve("name_0005.jpg")));
    }

    @Test
    void retriesServerErrorsThenSucceeds() throws IOException {
        files.put("/a_0001.jpg", bytes("ok"));
        flakyFailuresLeft.set(2);

        var summary = downloader(config(base + "/flaky/a_{n}.jpg", 1, 1, true, 0, Map.of())).run(r -> { });

        assertEquals(1, summary.count(Status.DOWNLOADED));
        assertEquals(3, requested.size());
    }

    @Test
    void networkErrorIsRecordedAndDoesNotStopTheRun() throws IOException {
        server.stop(0);
        var summary = downloader(config(base + "/a_{n}.jpg", 0, 2, true, 0, Map.of())).run(r -> { });

        assertEquals(3, summary.results().size(), "every number is still attempted");
        assertEquals(3, summary.count(Status.FAILED));
    }

    @Test
    void stopsAfterConsecutiveMisses() throws IOException {
        files.put("/a_0000.jpg", bytes("x"));

        var summary = downloader(config(base + "/a_{n}.jpg", 0, 999, true, 3, Map.of())).run(r -> { });

        assertEquals(4, requested.size());
        assertTrue(summary.stoppedAfterMisses());
    }

    @Test
    void sendsCookies() throws IOException {
        files.put("/a_0000.jpg", bytes("x"));

        downloader(config(base + "/a_{n}.jpg", 0, 0, true, 0, Map.of("session_id", "abc"))).run(r -> { });

        assertEquals(List.of("session_id=abc"), cookieHeaders);
    }

    @Test
    void normalizeDecodesThenReEncodes() {
        assertEquals("https://h.com/a%20b/c_0001.jpg",
                ImageDownloader.normalize("https://h.com/a%20b/c_0001.jpg").toASCIIString());
        assertEquals("https://h.com/a%20b/c.jpg",
                ImageDownloader.normalize("https://h.com/a b/c.jpg").toASCIIString());
        assertEquals("https://h.com/a+b.jpg", ImageDownloader.normalize("https://h.com/a+b.jpg").toASCIIString());
    }

    @Test
    void configDefaultsMatchOriginalTest() {
        DownloadConfig c = DownloadConfig.fromProperties(new java.util.Properties());
        assertEquals(1000, c.count());
        assertEquals("name_0230.jpg", c.fileNameFor(230));
        assertEquals("https://test.com/media/b/r/name/1000/brewi_0230.jpg", c.urlFor(230));
        assertEquals(Path.of("downloads"), c.outputDir());
        assertEquals(Duration.ofMillis(300), c.delay());
    }

    @Test
    void cliArgumentsOverrideDefaults() throws IOException {
        DownloadConfig c = DownloadConfig.load("end=9", "--padding=3", "header.User-Agent=test-agent");
        assertEquals(10, c.count());
        assertEquals("name_007.jpg", c.fileNameFor(7));
        assertEquals(Map.of("User-Agent", "test-agent"), c.headers());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
