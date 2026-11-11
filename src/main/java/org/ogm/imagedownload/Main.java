package org.ogm.imagedownload;

import java.io.IOException;

import org.ogm.imagedownload.DownloadResult.Status;

/**
 * Console version (no window). The normal way to use the project is the window: {@link DownloaderApp}.
 *
 * <p>Arguments are optional {@code key=value} overrides, e.g. {@code start=0 end=9 delay.ms=500}.
 * See README.md for all keys.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    /** Runs a download and returns the process exit code (0 = no failures). Shared with the IDE test runner. */
    public static int run(String... args) throws IOException {
        DownloadConfig config = DownloadConfig.load(args);
        printHeader(config);
        RequestLog log = new RequestLog(config.logFile());
        log.start(config);

        int total = config.count();
        int width = Integer.toString(total).length();
        int[] index = {0};
        long startedAt = System.nanoTime();

        ImageDownloader.Summary summary = new ImageDownloader(config).run(r -> {
            log.log(r);
            index[0]++;
            System.out.printf("[%" + width + "d/%d] %-8s %-24s %s%n",
                    index[0], total, label(r.status()), r.file().getFileName(), r.detail());
        });

        log.finish(summary);
        printSummary(summary, System.nanoTime() - startedAt);
        return summary.hasFailures() || summary.interrupted() ? 1 : 0;
    }

    private static void printHeader(DownloadConfig c) {
        System.out.println("Starting downloads...");
        System.out.println("  URL      : " + c.urlTemplate());
        System.out.println("  File     : " + c.fileNameTemplate());
        System.out.println("  Range    : " + c.pad(c.start()) + " .. " + c.pad(c.end()) + " (" + c.count() + " files)");
        System.out.println("  Folder   : " + c.outputDir().toAbsolutePath());
        System.out.println("  Delay    : " + c.delay().toMillis() + "-" + c.delay().plus(c.delayJitter()).toMillis()
                + " ms, retries: " + c.retries()
                + ", overwrite: " + c.overwrite()
                + (c.stopAfterMisses() > 0 ? ", stop after " + c.stopAfterMisses() + " misses" : ""));
        System.out.println("  Log      : " + (c.logFile() == null ? "off" : c.logFile().toAbsolutePath()));
        System.out.println();
    }

    private static void printSummary(ImageDownloader.Summary s, long nanos) {
        System.out.println();
        System.out.printf("Done in %.1fs: %d downloaded, %d skipped, %d not found, %d failed%n",
                nanos / 1e9, s.count(Status.DOWNLOADED), s.count(Status.SKIPPED),
                s.count(Status.NOT_FOUND), s.count(Status.FAILED));
        if (s.stoppedAfterMisses()) {
            System.out.println("Stopped early: too many consecutive missing numbers (stop.after.misses).");
        }
        if (s.interrupted()) {
            System.out.println("Interrupted before finishing.");
        }
        for (DownloadResult f : s.failures()) {
            System.out.println("  FAILED " + f.url() + " -> " + f.detail());
        }
    }

    private static String label(Status s) {
        return switch (s) {
            case DOWNLOADED -> "OK";
            case SKIPPED -> "SKIP";
            case NOT_FOUND -> "MISSING";
            case FAILED -> "FAILED";
        };
    }
}
