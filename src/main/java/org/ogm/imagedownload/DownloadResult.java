package org.ogm.imagedownload;

import java.nio.file.Path;

/** Outcome of one numbered download. */
public record DownloadResult(int number, String url, Path file, Status status, int httpStatus, String detail) {

    public enum Status {
        /** HTTP 200 and the file was written. */
        DOWNLOADED,
        /** File already existed and {@code overwrite=false}. */
        SKIPPED,
        /** Server answered with something other than 200 (usually 404: that number does not exist). */
        NOT_FOUND,
        /** Network/IO error after all retries. */
        FAILED
    }

    public boolean exists() {
        return status == Status.DOWNLOADED || status == Status.SKIPPED;
    }
}
