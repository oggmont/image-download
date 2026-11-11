package org.ogm.imagedownload;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Appends one line per request to a text file ({@code download-requests.log} in the project root by default).
 *
 * <p>It records only what was asked and what came back: no user name, host name or machine details.
 * A logging failure is reported once on stderr and never stops a download.
 */
public final class RequestLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private boolean warned;

    /** @param file the log file, or {@code null} to log nothing */
    public RequestLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void start(DownloadConfig c) {
        write("");
        write("=== " + now() + " run started: " + c.pad(c.start()) + ".." + c.pad(c.end())
                + " (" + c.count() + " numbers), url " + c.urlTemplate() + ", file " + c.fileNameTemplate()
                + ", folder " + c.outputDir() + ", delay " + c.delay().toMillis() + "+" + c.delayJitter().toMillis()
                + " ms" + (c.proxy().isEmpty() ? "" : ", proxy " + c.proxy()));
    }

    public void log(DownloadResult r) {
        write(String.format("%s  #%s  %-10s %3s  GET %s -> %s  %s", now(), r.number(), r.status(),
                r.httpStatus() == 0 ? "-" : Integer.toString(r.httpStatus()), r.url(), r.file(), r.detail()));
    }

    public void finish(ImageDownloader.Summary s) {
        write("=== " + now() + " run " + (s.interrupted() ? "stopped" : "finished") + ": "
                + s.count(DownloadResult.Status.DOWNLOADED) + " downloaded, "
                + s.count(DownloadResult.Status.SKIPPED) + " skipped, "
                + s.count(DownloadResult.Status.NOT_FOUND) + " not found, "
                + s.count(DownloadResult.Status.FAILED) + " failed");
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    /**
     * FileOutputStream rather than Files.newOutputStream: the NIO channel would refuse to write once the thread
     * is interrupted, and "Stop" interrupts the download thread just before the final line is written.
     */
    private synchronized void write(String line) {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                parent.toFile().mkdirs();
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file.toFile(), true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write(System.lineSeparator());
            }
        } catch (IOException e) {
            if (!warned) {
                warned = true;
                System.err.println("Could not write request log " + file + ": " + e.getMessage());
            }
        }
    }
}
