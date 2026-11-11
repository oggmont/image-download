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
 * Everything the downloader needs to know. The naming defaults match the original Testt#test:
 * numbers 0..999, zero-padded to 4 digits, saved as {@code name_NNNN.jpg} into {@code downloads/}.
 * The pause between requests is longer than the original 300 ms and randomized (see {@link #delayJitter()}).
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
        Duration delayJitter,
        boolean overwrite,
        int retries,
        Duration timeout,
        int stopAfterMisses,
        Map<String, String> headers,
        Map<String, String> cookies,
        String proxy,
        Path logFile) {

    public static final String PLACEHOLDER = "{n}";
    static final String PREFIX = "download.";
    static final String HEADER_PREFIX = "header.";
    static final String COOKIE_PREFIX = "cookie.";

    /**
     * Sent with every request unless overridden with {@code header.<Name>}. They replace the JDK's default
     * {@code User-Agent: Java-HttpClient/<version>} with what an ordinary browser sends, so requests don't
     * reveal the OS, the Java version or that they come from a script.
     */
    public static final Map<String, String> DEFAULT_HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36",
            "Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            "Accept-Language", "en-US,en;q=0.9");

    public DownloadConfig {
        require(urlTemplate != null && urlTemplate.contains(PLACEHOLDER),
                "url.template must contain " + PLACEHOLDER + " (got: " + urlTemplate + ")");
        require(fileNameTemplate != null && fileNameTemplate.contains(PLACEHOLDER),
                "file.template must contain " + PLACEHOLDER + " (got: " + fileNameTemplate + ")");
        require(!fileNameTemplate.contains("/") && !fileNameTemplate.contains("\\"),
                "file name must not contain / or \\ (got: " + fileNameTemplate + ")");
        require(start >= 0, "start must be >= 0");
        require(end >= start, "end must be >= start");
        require(padding >= 0, "padding must be >= 0");
        require(retries >= 0, "retries must be >= 0");
        require(stopAfterMisses >= 0, "stop.after.misses must be >= 0");
        require(outputDir != null, "output.dir is required");
        require(delay != null && !delay.isNegative(), "delay.ms must be >= 0");
        require(delayJitter != null && !delayJitter.isNegative(), "delay.jitter.ms must be >= 0");
        require(timeout != null && !timeout.isNegative() && !timeout.isZero(), "timeout.seconds must be > 0");
        proxy = proxy == null ? "" : proxy.trim();
        require(proxy.isEmpty() || proxy.matches("[^:\\s]+:\\d{1,5}"), "proxy must look like host:port (got: " + proxy + ")");
        headers = Collections.unmodifiableMap(withDefaultHeaders(headers == null ? Map.of() : headers));
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
     * The same settings with the four values typed in the UI.
     *
     * @param urlBase    incomplete URL; {@code <padded i>.jpg} is appended (see {@link #urlTemplateFromBase})
     * @param filePrefix file name before {@code _<padded i>.jpg} (see {@link #fileTemplateFromPrefix})
     */
    public DownloadConfig forRun(String urlBase, String filePrefix, int start, int end) {
        return new DownloadConfig(urlTemplateFromBase(urlBase), fileTemplateFromPrefix(filePrefix), start, end,
                padding, outputDir, delay, delayJitter, overwrite, retries, timeout, stopAfterMisses, headers,
                cookies, proxy, logFile);
    }

    /** {@code https://h/image_} → {@code https://h/image_{n}.jpg}. A value that already has {@code {n}} is kept. */
    public static String urlTemplateFromBase(String base) {
        String b = base.trim();
        return b.contains(PLACEHOLDER) ? b : b + PLACEHOLDER + ".jpg";
    }

    /** {@code name} → {@code name_{n}.jpg} ({@code name_} works too). A value that already has {@code {n}} is kept. */
    public static String fileTemplateFromPrefix(String prefix) {
        String p = prefix.trim();
        if (p.contains(PLACEHOLDER)) {
            return p;
        }
        return (p.endsWith("_") ? p : p + "_") + PLACEHOLDER + ".jpg";
    }

    /** Inverse of {@link #urlTemplateFromBase}, used to fill the UI from the config. */
    public static String urlBaseFromTemplate(String template) {
        String suffix = PLACEHOLDER + ".jpg";
        return template.endsWith(suffix) ? template.substring(0, template.length() - suffix.length()) : template;
    }

    /** Inverse of {@link #fileTemplateFromPrefix}, used to fill the UI from the config. */
    public static String filePrefixFromTemplate(String template) {
        String suffix = "_" + PLACEHOLDER + ".jpg";
        return template.endsWith(suffix) ? template.substring(0, template.length() - suffix.length()) : template;
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
        String log = get(p, "log.file", "download-requests.log");
        return new DownloadConfig(
                get(p, "url.template", "https://test.com/media/b/r/name/1000/image_{n}.jpg"),
                get(p, "file.template", "name_{n}.jpg"),
                Integer.parseInt(get(p, "start", "0")),
                Integer.parseInt(get(p, "end", "999")),
                Integer.parseInt(get(p, "padding", "4")),
                Path.of(get(p, "output.dir", "downloads")),
                Duration.ofMillis(Long.parseLong(get(p, "delay.ms", "1500"))),
                Duration.ofMillis(Long.parseLong(get(p, "delay.jitter.ms", "1500"))),
                Boolean.parseBoolean(get(p, "overwrite", "true")),
                Integer.parseInt(get(p, "retries", "2")),
                Duration.ofSeconds(Long.parseLong(get(p, "timeout.seconds", "30"))),
                Integer.parseInt(get(p, "stop.after.misses", "0")),
                headers,
                cookies,
                get(p, "proxy", ""),
                log.equalsIgnoreCase("none") ? null : Path.of(log));
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

    /** Defaults first; a configured header replaces the default with the same name (case-insensitive). */
    private static Map<String, String> withDefaultHeaders(Map<String, String> configured) {
        Map<String, String> merged = new LinkedHashMap<>();
        DEFAULT_HEADERS.forEach((name, value) -> {
            if (configured.keySet().stream().noneMatch(name::equalsIgnoreCase)) {
                merged.put(name, value);
            }
        });
        merged.putAll(configured);
        return merged;
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
