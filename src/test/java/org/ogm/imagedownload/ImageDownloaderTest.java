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
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
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
            userAgents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
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
                Duration.ZERO, overwrite, 2, Duration.ofSeconds(5), stopAfterMisses, Map.of(), cookies, "", null);
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
        assertEquals(Duration.ofMillis(1500), c.delay());
        assertEquals(Duration.ofMillis(1500), c.delayJitter());
        assertEquals(Path.of("download-requests.log"), c.logFile());
        assertEquals("", c.proxy());
    }

    @Test
    void cliArgumentsOverrideDefaults() throws IOException {
        DownloadConfig c = DownloadConfig.load("end=9", "--padding=3", "header.user-agent=test-agent");
        assertEquals(10, c.count());
        assertEquals("name_007.jpg", c.fileNameFor(7));
        assertEquals("test-agent", c.headers().get("user-agent"));
        assertFalse(c.headers().containsKey("User-Agent"), "configured header replaces the default, any case");
        assertTrue(c.headers().containsKey("Accept-Language"), "other defaults are kept");
    }

    @Test
    void sendsBrowserUserAgentInsteadOfJavaOne() throws IOException {
        files.put("/a_0000.jpg", bytes("x"));

        downloader(config(base + "/a_{n}.jpg", 0, 0, true, 0, Map.of())).run(r -> { });

        assertEquals(1, userAgents.size());
        assertTrue(userAgents.get(0).startsWith("Mozilla/5.0"), userAgents.get(0));
        assertFalse(userAgents.get(0).contains("Java"), userAgents.get(0));
        assertFalse(userAgents.get(0).contains("Mac"), userAgents.get(0));
    }

    @Test
    void uiInputsMapToTheOriginalLoop() {
        // urlToDownload + String.format("%04d", i) + ".jpg"  and  prefix + "_" + format + ".jpg"
        DownloadConfig c = DownloadConfig.fromProperties(new java.util.Properties())
                .forRun("https://h.com/x/brewi_", "brewi", 5, 7);
        assertEquals("https://h.com/x/brewi_0005.jpg", c.urlFor(5));
        assertEquals("brewi_0007.jpg", c.fileNameFor(7));
        assertEquals(3, c.count());
        assertEquals("a_{n}.jpg", DownloadConfig.fileTemplateFromPrefix("a_"));
        assertEquals("a", DownloadConfig.filePrefixFromTemplate(DownloadConfig.fileTemplateFromPrefix("a")));
        assertEquals("https://h/b_", DownloadConfig.urlBaseFromTemplate(DownloadConfig.urlTemplateFromBase("https://h/b_")));
    }

    @Test
    void delayIsRandomizedWithinJitter() {
        DownloadConfig c = new DownloadConfig(base + "/a_{n}.jpg", "a_{n}.jpg", 0, 1, 4, out, Duration.ofMillis(100),
                Duration.ofMillis(50), true, 0, Duration.ofSeconds(5), 0, Map.of(), Map.of(), "", null);
        ImageDownloader d = downloader(c);
        for (int i = 0; i < 200; i++) {
            long ms = d.nextDelay().toMillis();
            assertTrue(ms >= 100 && ms <= 150, "delay " + ms);
        }
    }

    @Test
    void listenerSeesEachRequestAndPause() throws IOException {
        files.put("/a_0000.jpg", bytes("x"));
        List<String> events = new ArrayList<>();

        downloader(config(base + "/a_{n}.jpg", 0, 1, true, 0, Map.of())).run(new ImageDownloader.Listener() {
            @Override public void beforeRequest(int n, String url, Path file) { events.add("request " + n); }
            @Override public void onResult(DownloadResult r) { events.add("result " + r.number() + " " + r.status()); }
            @Override public void beforeDelay(Duration pause) { events.add("pause " + pause.toMillis()); }
        });

        assertEquals(List.of("request 0", "result 0 DOWNLOADED", "pause 300", "request 1", "result 1 NOT_FOUND"),
                events);
    }

    @Test
    void requestLogRecordsEveryRequest() throws IOException {
        files.put("/a_0000.jpg", bytes("x"));
        DownloadConfig c = config(base + "/a_{n}.jpg", 0, 1, true, 0, Map.of());
        RequestLog log = new RequestLog(out.resolve("requests.log"));

        log.start(c);
        log.finish(downloader(c).run(log::log));

        List<String> lines = Files.readAllLines(out.resolve("requests.log"));
        assertTrue(lines.get(1).contains("run started"), lines.toString());
        assertTrue(lines.get(2).contains("#0  DOWNLOADED 200  GET " + base + "/a_0000.jpg"), lines.get(2));
        assertTrue(lines.get(3).contains("NOT_FOUND  404"), lines.get(3));
        assertTrue(lines.get(4).contains("1 downloaded, 0 skipped, 1 not found, 0 failed"), lines.get(4));
    }

    @Test
    void rejectsBadProxy() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("proxy", "not a proxy");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DownloadConfig.fromProperties(p));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
