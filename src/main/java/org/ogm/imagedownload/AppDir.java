package org.ogm.imagedownload;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where relative paths (config, inputs, log, downloads) live.
 *
 * <p>Started from a jar (e.g. by double-clicking {@code image-download.jar}), the working directory is often
 * the home folder or {@code /}, so relative paths are resolved against the jar's own folder instead. Started
 * from compiled classes ({@code mvn exec:java}, the IDE, tests), paths stay relative to the working directory.
 */
final class AppDir {

    private static final Path BASE = detect();

    private AppDir() {
    }

    /** {@code path} unchanged if absolute or not running from a jar, otherwise resolved next to the jar. */
    static Path resolve(Path path) {
        return BASE == null || path.isAbsolute() ? path : BASE.resolve(path);
    }

    static Path resolve(String path) {
        return resolve(Path.of(path));
    }

    private static Path detect() {
        try {
            Path location = Path.of(AppDir.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.toAbsolutePath().getParent() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
