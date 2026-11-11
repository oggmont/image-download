package org.ogm.imagedownload;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Everything the downloader needs to know. Defaults match the original Testt#test behaviour:
 * numbers 0..999, zero-padded to 4 digits, saved as {@code name_NNNN.jpg} into {@code downloads/},
 * with a 300 ms pause between requests.
 *
 * <p>{@code {n}} in {@link #urlTemplate()} and {@link #fileNameTemplate()} is replaced by the padded number.
 */
public record DownloadConfig(
        String urlTemplate,
        String fileNameTemplate,
        int start,
        int end,
        int padding,
        Path outputDir,
        Duration delay,
        boolean overwrite,
        int retries,
        Duration timeout,
        int stopAfterMisses,
        Map<String, String> headers,
        Map<String, String> cookies) {

    public static final String PLACEHOLDER = "{n}";
    static final String PREFIX = "download.";
    static final String HEADER_PREFIX = "header.";
    static final String COOKIE_PREFIX = "cookie.";

    public DownloadConfig {
        require(urlTemplate != null && urlTemplate.contains(PLACEHOLDER),
                "url.template must contain " + PLACEHOLDER + " (got: " + urlTemplate + ")");
        require(fileNameTemplate != null && fileNameTemplate.contains(PLACEHOLDER),
                "file.template must contain " + PLACEHOLDER + " (got: " + fileNameTemplate + ")");
        require(start >= 0, "start must be >= 0");
        require(end >= start, "end must be >= start");
        require(padding >= 0, "padding must be >= 0");
        require(retries >= 0, "retries must be >= 0");
        require(stopAfterMisses >= 0, "stop.after.misses must be >= 0");
        require(outputDir != null, "output.dir is required");
        require(delay != null && !delay.isNegative(), "delay.ms must be >= 0");
        require(timeout != null && !timeout.isNegative() && !timeout.isZero(), "timeout.seconds must be > 0");
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
        cookies = Collections.unmodifiableMap(new LinkedHashMap<>(cookies == null ? Map.of() : cookies));
    }

    /** Zero-pads {@code number} the same way as {@code String.format("%04d", i)} in the original. */
    public String pad(int number) {
        return padding == 0 ? Integer.toString(number) : String.format("%0" + padding + "d", number);
    }

    public String urlFor(int number) {
        return urlTemplate.replace(PLACEHOLDER, pad(number));
    }

    public String fileNameFor(int number) {
        return fileNameTemplate.replace(PLACEHOLDER, pad(number));
    }

    public int count() {
        return end - start + 1;
    }

    /**
     * Builds a config from properties (keys without the {@code download.} prefix, e.g. {@code url.template}).
     * Missing keys fall back to the defaults below.
     */
    public static DownloadConfig fromProperties(Properties p) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            String value = p.getProperty(key);
            if (key.startsWith(HEADER_PREFIX) && !value.isBlank()) {
                headers.put(key.substring(HEADER_PREFIX.length()), value.trim());
            } else if (key.startsWith(COOKIE_PREFIX) && !value.isBlank()) {
                cookies.put(key.substring(COOKIE_PREFIX.length()), value.trim());
            }
        }
        return new DownloadConfig(
                get(p, "url.template", "https://test.com/media/b/r/name/1000/brewi_{n}.jpg"),
                get(p, "file.template", "name_{n}.jpg"),
                Integer.parseInt(get(p, "start", "0")),
                Integer.parseInt(get(p, "end", "999")),
                Integer.parseInt(get(p, "padding", "4")),
                Path.of(get(p, "output.dir", "downloads")),
                Duration.ofMillis(Long.parseLong(get(p, "delay.ms", "300"))),
                Boolean.parseBoolean(get(p, "overwrite", "true")),
                Integer.parseInt(get(p, "retries", "2")),
                Duration.ofSeconds(Long.parseLong(get(p, "timeout.seconds", "30"))),
                Integer.parseInt(get(p, "stop.after.misses", "0")),
                headers,
                cookies);
    }

    /**
     * Loads configuration, each layer overriding the previous one:
     * <ol>
     *   <li>{@code download.properties} on the classpath (shipped defaults)</li>
     *   <li>{@code download.local.properties} in the working directory (your own, git-ignored)</li>
     *   <li>the file passed as {@code config=path/to/file.properties}, if any</li>
     *   <li>JVM system properties prefixed with {@code download.} (e.g. {@code -Ddownload.end=10})</li>
     *   <li>command-line arguments {@code key=value} (e.g. {@code end=10})</li>
     * </ol>
     */
    public static DownloadConfig load(String... args) throws IOException {
        Properties p = new Properties();
        try (InputStream in = DownloadConfig.class.getResourceAsStream("/download.properties")) {
            if (in != null) {
                p.load(in);
            }
        }
        loadFile(p, Path.of("download.local.properties"));

        Map<String, String> cli = new LinkedHashMap<>();
        for (String arg : args) {
            String a = arg.startsWith("--") ? arg.substring(2) : arg;
            int eq = a.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Arguments must look like key=value, got: " + arg);
            }
            cli.put(a.substring(0, eq).trim(), a.substring(eq + 1).trim());
        }

        String explicit = cli.remove("config");
        if (explicit == null) {
            explicit = System.getProperty(PREFIX + "config");
        }
        if (explicit != null) {
            Path file = Path.of(explicit);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Config file not found: " + file.toAbsolutePath());
            }
            loadFile(p, file);
        }

        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(PREFIX) && !key.equals(PREFIX + "config")) {
                p.setProperty(key.substring(PREFIX.length()), System.getProperty(key));
            }
        }
        cli.forEach(p::setProperty);
        return fromProperties(p);
    }

    private static void loadFile(Properties p, Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
    }

    private static String get(Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
