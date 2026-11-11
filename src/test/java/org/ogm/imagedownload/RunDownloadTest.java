package org.ogm.imagedownload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The "run button" version of the original Testt#test: click the green ▶ next to this method in VS Code.
 *
 * <p>It uses the same config as {@link Main} (download.properties + your download.local.properties).
 * Tagged {@code manual} so {@code mvn test} skips it; run it from Maven with {@code mvn test -Pdownload}.
 */
@Tag("manual")
class RunDownloadTest {

    @Test
    @DisplayName("Download the configured image series")
    void downloadImages() throws Exception {
        assertEquals(0, Main.run(), "Some downloads failed; see the output above");
    }
}
