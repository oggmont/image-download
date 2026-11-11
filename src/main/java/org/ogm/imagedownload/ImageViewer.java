package org.ogm.imagedownload;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.UIManager;

/** Shows one image scaled to fit (aspect ratio kept), or a short message when there is none. */
final class ImageViewer extends JComponent {

    private BufferedImage image;
    private String message = "No image downloaded yet";

    ImageViewer() {
        setPreferredSize(new Dimension(420, 420));
        setMinimumSize(new Dimension(200, 200));
    }

    void showImage(BufferedImage img) {
        image = img;
        repaint();
    }

    void showMessage(String text) {
        image = null;
        message = text;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            Color bg = UIManager.getColor("Panel.background");
            g2.setColor(bg == null ? Color.DARK_GRAY : bg.darker());
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (image == null) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(Color.GRAY);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
                return;
            }
            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) Math.round(image.getWidth() * scale);
            int h = (int) Math.round(image.getHeight() * scale);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        } finally {
            g2.dispose();
        }
    }
}
