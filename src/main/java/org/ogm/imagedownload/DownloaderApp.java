package org.ogm.imagedownload;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

import org.ogm.imagedownload.DownloadResult.Status;

/**
 * The window. Start it with {@code ./mvnw -q compile exec:java} or by running {@link #main} from any IDE.
 *
 * <p>Inputs are the four values of the original loop: initial i, last i, the file name prefix and the
 * incomplete URL that {@code <padded i>.jpg} is appended to. Everything else (delay, retries, headers, proxy,
 * output folder) comes from {@code download.properties} / {@code download.local.properties}.
 */
public final class DownloaderApp {

    /** Last values typed in the window, restored on the next launch. Git-ignored (it holds the real URL). */
    static final Path INPUTS_FILE = Path.of("ui.local.properties");
    private static final int HISTORY_LINES = 500;
    private static final Color OK = new Color(0x2E7D32);
    private static final Color WARN = new Color(0xB26A00);
    private static final Color ERROR = new Color(0xC62828);

    private final DownloadConfig baseConfig;

    private final JFrame frame = new JFrame("Image Downloader");
    private final JTextField startField = new JTextField(6);
    private final JTextField endField = new JTextField(6);
    private final JTextField prefixField = new JTextField(18);
    private final JTextField urlField = new JTextField(40);
    private final JLabel previewLabel = new JLabel(" ");
    private final JButton downloadButton = new JButton("Download");
    private final JButton stopButton = new JButton("Stop");
    private final JButton resetButton = new JButton("Reset inputs");
    private final JButton folderButton = new JButton("Open folder");
    private final JTextArea history = new JTextArea(12, 60);
    private final ImageViewer viewer = new ImageViewer();
    private final JLabel viewerCaption = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel statusBar = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private DownloadWorker worker;
    private String lastResult = "";

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Image Downloader");
        DownloadConfig config;
        try {
            config = DownloadConfig.load(args);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(null, "Could not read the configuration:\n" + e.getMessage(),
                    "Image Downloader", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel is fine too
            }
            new DownloaderApp(config).show();
        });
    }

    DownloaderApp(DownloadConfig baseConfig) {
        this.baseConfig = baseConfig;
    }

    // ---------------------------------------------------------------- layout

    private void show() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        root.add(buildForm(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.getRootPane().setDefaultButton(downloadButton);

        fillInputs(loadSavedInputs());
        updatePreview();
        setRunning(false);
        setStatus("Ready. Pause between requests: " + seconds(baseConfig.delay()) + "–"
                + seconds(baseConfig.delay().plus(baseConfig.delayJitter())) + " s. Request log: "
                + (baseConfig.logFile() == null ? "off" : baseConfig.logFile()), null);

        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Inputs"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;

        addRow(form, c, 0, "Initial i", startField, "first number of the loop");
        addRow(form, c, 1, "Last i", endField, "last number, inclusive (999 → stops at _0999)");
        addRow(form, c, 2, "File name prefix", prefixField, "saved as <prefix>_0000.jpg in "
                + baseConfig.outputDir() + "/");
        addRow(form, c, 3, "URL to download", urlField, "incomplete: 0000.jpg, 0001.jpg … is appended");

        previewLabel.setFont(previewLabel.getFont().deriveFont(Font.ITALIC));
        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(previewLabel, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(downloadButton);
        buttons.add(stopButton);
        buttons.add(resetButton);
        buttons.add(folderButton);
        c.gridy = 5;
        form.add(buttons, c);

        DocumentListener onChange = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };
        for (JTextField f : List.of(startField, endField, prefixField, urlField)) {
            f.getDocument().addDocumentListener(onChange);
        }
        downloadButton.addActionListener(e -> startDownload());
        stopButton.addActionListener(e -> stopDownload());
        resetButton.addActionListener(e -> {
            fillInputs(defaultInputs());
            setStatus("Inputs reset to the configured defaults.", null);
        });
        folderButton.addActionListener(e -> openFolder());
        return form;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field,
            String hint) {
        c.gridwidth = 1;
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JLabel h = new JLabel(hint);
        h.setForeground(Color.GRAY);
        form.add(h, c);
    }

    private JComponent buildCenter() {
        history.setEditable(false);
        history.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane historyScroll = new JScrollPane(history);
        historyScroll.setBorder(BorderFactory.createTitledBorder("Requests"));

        JPanel viewerPanel = new JPanel(new BorderLayout(0, 4));
        viewerPanel.setBorder(BorderFactory.createTitledBorder("Latest image"));
        viewerPanel.add(viewer, BorderLayout.CENTER);
        viewerPanel.add(viewerCaption, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, historyScroll, viewerPanel);
        split.setResizeWeight(0.55);
        split.setBorder(null);
        return split;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 2, 0, 2)));
        progress.setStringPainted(true);
        progress.setString("");
        bar.add(statusBar, BorderLayout.CENTER);
        bar.add(progress, BorderLayout.EAST);
        return bar;
    }

    // ---------------------------------------------------------------- inputs

    record Inputs(String start, String end, String prefix, String url) {
    }

    private Inputs defaultInputs() {
        return new Inputs(Integer.toString(baseConfig.start()), Integer.toString(baseConfig.end()),
                DownloadConfig.filePrefixFromTemplate(baseConfig.fileNameTemplate()),
                DownloadConfig.urlBaseFromTemplate(baseConfig.urlTemplate()));
    }

    private Inputs loadSavedInputs() {
        Inputs d = defaultInputs();
        if (!Files.isRegularFile(INPUTS_FILE)) {
            return d;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(INPUTS_FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return d;
        }
        return new Inputs(p.getProperty("start", d.start()), p.getProperty("end", d.end()),
                p.getProperty("prefix", d.prefix()), p.getProperty("url", d.url()));
    }

    private void saveInputs() {
        Properties p = new Properties();
        p.setProperty("start", startField.getText().trim());
        p.setProperty("end", endField.getText().trim());
        p.setProperty("prefix", prefixField.getText().trim());
        p.setProperty("url", urlField.getText().trim());
        try (Writer w = Files.newBufferedWriter(INPUTS_FILE, StandardCharsets.UTF_8)) {
            p.store(w, "Last values typed in the Image Downloader window (git-ignored)");
        } catch (IOException ignored) {
            // only a convenience
        }
    }

    private void fillInputs(Inputs in) {
        startField.setText(in.start());
        endField.setText(in.end());
        prefixField.setText(in.prefix());
        urlField.setText(in.url());
    }

    /** A message plus the field to focus. */
    private static final class InputError extends RuntimeException {
        final JComponent field;

        InputError(JComponent field, String message) {
            super(message);
            this.field = field;
        }
    }

    private DownloadConfig readForm() {
        int start = parseNumber(startField, "Initial i");
        int end = parseNumber(endField, "Last i");
        if (end < start) {
            throw new InputError(endField, "Last i must be greater than or equal to initial i.");
        }
        String prefix = prefixField.getText().trim();
        if (prefix.isEmpty()) {
            throw new InputError(prefixField, "The file name prefix is required.");
        }
        if (prefix.contains("/") || prefix.contains("\\") || prefix.startsWith(".")) {
            throw new InputError(prefixField, "The file name prefix can't contain / or \\ or start with a dot.");
        }
        String url = urlField.getText().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new InputError(urlField, "The URL must start with http:// or https://");
        }
        try {
            return baseConfig.forRun(url, prefix, start, end);
        } catch (IllegalArgumentException e) {
            throw new InputError(urlField, e.getMessage());
        }
    }

    private static int parseNumber(JTextField field, String name) {
        try {
            int n = Integer.parseInt(field.getText().trim());
            if (n < 0) {
                throw new InputError(field, name + " can't be negative.");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new InputError(field, name + " must be a whole number.");
        }
    }

    private void updatePreview() {
        try {
            DownloadConfig c = readForm();
            previewLabel.setForeground(Color.GRAY);
            String text = "First: GET " + c.urlFor(c.start()) + "  →  " + c.outputDir().resolve(c.fileNameFor(c.start()))
                    + "   (" + c.count() + " images)";
            previewLabel.setText(text);
            previewLabel.setToolTipText(text);
        } catch (InputError e) {
            previewLabel.setForeground(ERROR);
            previewLabel.setText(e.getMessage());
            previewLabel.setToolTipText(null);
        }
    }

    // ---------------------------------------------------------------- actions

    private void startDownload() {
        DownloadConfig config;
        try {
            config = readForm();
        } catch (InputError e) {
            setStatus(e.getMessage(), ERROR);
            e.field.requestFocusInWindow();
            return;
        }
        saveInputs();
        history.setText("");
        lastResult = "";
        progress.setMaximum(config.count());
        progress.setValue(0);
        progress.setString("0 / " + config.count());
        setStatus("Starting " + config.count() + " downloads…", null);
        appendHistory("Run " + config.pad(config.start()) + ".." + config.pad(config.end()) + " → "
                + config.outputDir().toAbsolutePath());
        worker = new DownloadWorker(config);
        setRunning(true);
        worker.execute();
    }

    private void stopDownload() {
        if (worker != null) {
            stopButton.setEnabled(false);
            setStatus("Stopping…", WARN);
            worker.cancel(true);
        }
    }

    private void openFolder() {
        try {
            Files.createDirectories(baseConfig.outputDir());
            Desktop.getDesktop().open(baseConfig.outputDir().toAbsolutePath().toFile());
        } catch (IOException | UnsupportedOperationException e) {
            setStatus("Could not open " + baseConfig.outputDir().toAbsolutePath() + ": " + e.getMessage(), ERROR);
        }
    }

    private void setRunning(boolean running) {
        for (JTextField f : List.of(startField, endField, prefixField, urlField)) {
            f.setEnabled(!running);
        }
        downloadButton.setEnabled(!running);
        resetButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    private void setStatus(String text, Color color) {
        statusBar.setText(text);
        statusBar.setToolTipText(text);
        statusBar.setForeground(color == null ? UIManager.getColor("Label.foreground") : color);
    }

    private void appendHistory(String line) {
        history.append(line + "\n");
        try {
            while (history.getLineCount() > HISTORY_LINES) {
                history.replaceRange("", 0, history.getLineEndOffset(0));
            }
        } catch (BadLocationException ignored) {
            // cannot happen: line 0 always exists
        }
        history.setCaretPosition(history.getDocument().getLength());
    }

    private static String seconds(Duration d) {
        return String.format("%.1f", d.toMillis() / 1000.0);
    }

    // ---------------------------------------------------------------- background work

    private sealed interface Event permits Requesting, Finished, Waiting {
    }

    private record Requesting(int number, String url) implements Event {
    }

    /** {@code image} is decoded on the download thread so the window never freezes. */
    private record Finished(DownloadResult result, BufferedImage image) implements Event {
    }

    private record Waiting(Duration pause) implements Event {
    }

    private final class DownloadWorker extends SwingWorker<ImageDownloader.Summary, Event> {

        private final DownloadConfig config;
        private final RequestLog log;
        private int done;
        private int downloaded;
        private int skipped;
        private int missing;
        private int failed;

        DownloadWorker(DownloadConfig config) {
            this.config = config;
            this.log = new RequestLog(config.logFile());
        }

        @Override
        protected ImageDownloader.Summary doInBackground() throws IOException {
            log.start(config);
            ImageDownloader.Summary summary = new ImageDownloader(config).run(new ImageDownloader.Listener() {
                @Override
                public void beforeRequest(int number, String url, Path file) {
                    publish(new Requesting(number, url));
                }

                @Override
                public void onResult(DownloadResult r) {
                    log.log(r);
                    publish(new Finished(r, r.status() == Status.DOWNLOADED ? readImage(r.file()) : null));
                }

                @Override
                public void beforeDelay(Duration pause) {
                    publish(new Waiting(pause));
                }
            });
            log.finish(summary);
            return summary;
        }

        private BufferedImage readImage(Path file) {
            try {
                return ImageIO.read(file.toFile());
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        protected void process(List<Event> events) {
            if (isCancelled()) {
                return;
            }
            for (Event e : events) {
                if (e instanceof Requesting q) {
                    setStatus("Requesting #" + config.pad(q.number()) + "  " + q.url()
                            + (lastResult.isEmpty() ? "" : "   |   last: " + lastResult), null);
                } else if (e instanceof Waiting w) {
                    setStatus(lastResult + "   |   next request in " + seconds(w.pause()) + " s", colorOf(null));
                } else if (e instanceof Finished f) {
                    onFinished(f);
                }
            }
        }

        private Color lastColor;

        private Color colorOf(Status s) {
            if (s != null) {
                lastColor = switch (s) {
                    case DOWNLOADED, SKIPPED -> OK;
                    case NOT_FOUND -> WARN;
                    case FAILED -> ERROR;
                };
            }
            return lastColor;
        }

        private void onFinished(Finished f) {
            DownloadResult r = f.result();
            done++;
            switch (r.status()) {
                case DOWNLOADED -> downloaded++;
                case SKIPPED -> skipped++;
                case NOT_FOUND -> missing++;
                case FAILED -> failed++;
            }
            String label = switch (r.status()) {
                case DOWNLOADED -> "OK";
                case SKIPPED -> "SKIPPED";
                case NOT_FOUND -> "MISSING";
                case FAILED -> "FAILED";
            };
            lastResult = label + " #" + config.pad(r.number()) + " " + r.file().getFileName() + " (" + r.detail() + ")";
            appendHistory(String.format("%-8s %-22s %s", label, r.file().getFileName(), r.detail()));
            setStatus(lastResult + "   |   " + counts(), colorOf(r.status()));
            progress.setValue(done);
            progress.setString(done + " / " + config.count());

            if (r.status() == Status.DOWNLOADED) {
                if (f.image() != null) {
                    viewer.showImage(f.image());
                    viewerCaption.setText(r.file().getFileName() + "  ·  " + f.image().getWidth() + "×"
                            + f.image().getHeight() + "  ·  " + r.detail());
                } else {
                    viewer.showMessage("Saved, but it is not a readable image");
                    viewerCaption.setText(r.file().getFileName() + "  ·  " + r.detail());
                }
            }
        }

        private String counts() {
            return downloaded + " downloaded, " + (skipped > 0 ? skipped + " skipped, " : "") + missing
                    + " missing, " + failed + " failed";
        }

        @Override
        protected void done() {
            setRunning(false);
            try {
                ImageDownloader.Summary s = get();
                String text = "Finished: " + counts() + "." + (s.stoppedAfterMisses() ? " Stopped early: too many "
                        + "missing numbers in a row (stop.after.misses)." : "");
                setStatus(text, s.hasFailures() ? ERROR : OK);
                appendHistory(text);
            } catch (CancellationException e) {
                String text = "Stopped after " + done + " of " + config.count() + ": " + counts() + ".";
                setStatus(text, WARN);
                appendHistory(text);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                setStatus("Error: " + cause.getMessage(), ERROR);
                appendHistory("ERROR " + cause);
            }
        }
    }
}
