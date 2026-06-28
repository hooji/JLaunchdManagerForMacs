package com.u1.servicepal.gui;

import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * Captures a Swing window's content to a PNG by painting its root pane into a {@link BufferedImage}
 * ({@code printAll}), rather than grabbing the screen with {@code Robot}. Swing widgets — including
 * the native-themed macOS Aqua and Windows look-and-feels — paint themselves into the image, so
 * this works on any platform that merely has a display (Xvfb on headless Linux; the real session on
 * macOS/Windows) without depending on the window being composited on a visible, interactive desktop
 * (a notorious source of black captures on Windows CI).
 *
 * <p>The trade-off: OS-drawn title bars are not included — the widgets are what we review. On Linux
 * the GUI asks Swing to draw its own title bar (see {@code ServicePalGui}), which <em>is</em>
 * captured.
 */
final class Screenshotter {

	private Screenshotter() {
	}

	static void capture(final Window window, final Path out) throws Exception {
		settle(window);
		final BufferedImage image = onEdt(() -> paint(window));
		write(image, out);
	}

	private static BufferedImage paint(final Window window) {
		final JRootPane root = ((RootPaneContainer) window).getRootPane();
		final int w = Math.max(1, root.getWidth());
		final int h = Math.max(1, root.getHeight());
		final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		g.setColor(root.getBackground());
		g.fillRect(0, 0, w, h);
		root.printAll(g);
		g.dispose();
		return image;
	}

	private static void settle(final Window window) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			window.toFront();
			window.repaint();
		});
		// Let the look-and-feel finish laying out and painting before we snapshot.
		Thread.sleep(500);
		SwingUtilities.invokeAndWait(() -> {
			// no-op: drains the EDT queue so the repaint above has completed
		});
	}

	private static void write(final BufferedImage image, final Path out) throws IOException {
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		ImageIO.write(image, "png", out.toFile());
		System.out.println("wrote " + out.toAbsolutePath() + " (" + image.getWidth() + "x"
				+ image.getHeight() + ")");
	}

	private interface EdtCall<T> {
		T get();
	}

	private static <T> T onEdt(final EdtCall<T> call) throws Exception {
		final Object[] holder = new Object[1];
		SwingUtilities.invokeAndWait(() -> holder[0] = call.get());
		@SuppressWarnings("unchecked")
		final T result = (T) holder[0];
		return result;
	}
}
