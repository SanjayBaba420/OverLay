package org.OverlayNotes;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

public class Main extends JFrame {
    private final Path saveFile;
    private final JTextArea editor = new JTextArea();
    private final JLabel status = label("Ready - autosave on");
    private final JLabel privacy = label("Capture exclusion not yet applied");
    private final GlassPanel glass = new GlassPanel();
    private boolean dirty;
    private boolean loadFailed;
    private boolean hidden;
    private boolean darkMode = true;
    private GlassButton themeButton;
    private JSlider tintSlider;
    private RoundedField fontField;
    private StripPanel headerStrip;
    private StripPanel controlsStrip;
    private Color themeText;
    private Color themeBorder;
    private Color themeBtnBg;
    private Color themeBtnHover;
    private Timer autosaveTimer;
    private static RandomAccessFile lockFile;
    private static FileChannel lockChannel;
    private static FileLock appLock;

    private static Path defaultSaveFile() {
        String launcher = System.getProperty("jpackage.app-path");
        return launcher == null ? Path.of("overlay_notes.txt").toAbsolutePath()
                : Path.of(launcher).toAbsolutePath().getParent().resolve("overlay_notes.txt");
    }

    Main(Path saveFile) {
        super("OverlayNotes");
        this.saveFile = saveFile.toAbsolutePath();
        this.darkMode = loadTheme();
        setUndecorated(true);
        setType(Window.Type.UTILITY);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(480, 280));
        setSize(600, 400);
        setLocationRelativeTo(null);
        if (isAlwaysOnTopSupported()) {
            setAlwaysOnTop(true);
        }
        boolean transparent = getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        if (transparent) {
            setBackground(new Color(0, 0, 0, 0));
        } else {
            glass.tint = 255;
        }
        setContentPane(glass);
        glass.setLayout(new BorderLayout(0, 10));
        glass.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));

        headerStrip = new StripPanel(new BorderLayout(12, 0));
        headerStrip.setBottomLine(true);
        JLabel title = label("GLASS NOTES");
        title.setFont(new Font("Calibri", Font.BOLD, 14));
        title.setToolTipText("Drag to move");
        headerStrip.add(title, BorderLayout.CENTER);
        JPanel actions = panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        themeButton = glassButton("LIGHT", this::toggleTheme);
        themeButton.setToolTipText("Toggle dark / light mode (Ctrl+T)");
        actions.add(themeButton);
        actions.add(glassButton("HIDE", this::toggleHide));
        actions.add(glassButton("CLOSE", this::closeWindow));
        headerStrip.add(actions, BorderLayout.EAST);
        glass.add(headerStrip, BorderLayout.NORTH);
        enableDragging(headerStrip);
        enableDragging(title);

        editor.setFont(new Font("Calibri", Font.PLAIN, loadFontSize()));
        editor.setOpaque(false);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setMargin(new Insets(15, 15, 15, 15));
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        glass.add(scroll, BorderLayout.CENTER);

        JPanel footer = panel(new BorderLayout(8, 4));
        controlsStrip = new StripPanel(new BorderLayout(8, 0));
        controlsStrip.setTopLine(true);
        controlsStrip.setBottomLine(true);
        controlsStrip.setFill(true);
        controlsStrip.setBorder(BorderFactory.createEmptyBorder(12, 4, 12, 4));
        JPanel tintGroup = panel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        tintGroup.add(controlLabel("GLASS TINT"));
        tintSlider = new JSlider(5, 95, loadTint());
        tintSlider.setOpaque(false);
        tintSlider.setEnabled(transparent);
        tintSlider.setPreferredSize(new Dimension(150, 20));
        tintSlider.setUI(new TintSliderUI(tintSlider));
        tintSlider.setToolTipText("Lower tint shows more of the apps behind; note text stays solid");
        glass.tint = Math.round(tintSlider.getValue() * 2.55f);
        tintSlider.addChangeListener(e -> {
            glass.tint = Math.round(tintSlider.getValue() * 2.55f);
            applyTheme();
            if (!tintSlider.getValueIsAdjusting()) {
                saveSettings();
            }
        });
        tintGroup.add(tintSlider);
        controlsStrip.add(tintGroup, BorderLayout.WEST);
        JPanel fontControls = panel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        JLabel fontLabel = controlLabel("FONT SIZE");
        fontField = new RoundedField(String.valueOf(loadFontSize()), 3);
        fontField.setHorizontalAlignment(JTextField.CENTER);
        fontField.setPreferredSize(new Dimension(50, 24));
        fontField.setFont(new Font("Calibri", Font.PLAIN, 13));
        fontField.setToolTipText("Type a size 10-48 and press Enter");
        fontLabel.setLabelFor(fontField);
        fontField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFontField(); }
            public void removeUpdate(DocumentEvent e) { applyFontField(); }
            public void changedUpdate(DocumentEvent e) { applyFontField(); }
        });
        fontField.addActionListener(e -> commitFontField());
        fontField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { commitFontField(); }
        });
        fontControls.add(fontLabel);
        fontControls.add(fontField);
        controlsStrip.add(fontControls, BorderLayout.EAST);
        footer.add(controlsStrip, BorderLayout.NORTH);
        status.setFont(new Font("Calibri", Font.PLAIN, 11));
        status.setToolTipText(this.saveFile.toString());
        privacy.setFont(new Font("Calibri", Font.PLAIN, 11));
        privacy.setToolTipText("Windows capture exclusion is best-effort, not a guarantee. Test with another participant.");
        JPanel statusBar = panel(new BorderLayout(8, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(8, 1, 0, 1));
        statusBar.add(status, BorderLayout.WEST);
        statusBar.add(privacy, BorderLayout.EAST);
        footer.add(statusBar, BorderLayout.CENTER);
        JLabel resize = label("\u25a2");
        resize.setToolTipText("Drag to resize");
        resize.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        MouseAdapter resizer = new MouseAdapter() {
            private Point start;
            private Dimension size;
            @Override public void mousePressed(MouseEvent e) {
                start = e.getLocationOnScreen();
                size = getSize();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                setSize(Math.max(getMinimumSize().width, size.width + e.getXOnScreen() - start.x),
                        Math.max(getMinimumSize().height, size.height + e.getYOnScreen() - start.y));
            }
        };
        resize.addMouseListener(resizer);
        resize.addMouseMotionListener(resizer);
        footer.add(resize, BorderLayout.EAST);
        glass.add(footer, BorderLayout.SOUTH);

        applyTheme();
        loadContent();
        autosaveTimer = new Timer(500, e -> { if (dirty) saveContent(); });
        autosaveTimer.setRepeats(false);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() {
                dirty = true;
                status.setText("Editing - autosaving...");
                autosaveTimer.restart();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "hide");
        getRootPane().getActionMap().put("hide", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggleHide(); }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "theme");
        getRootPane().getActionMap().put("theme", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggleTheme(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeWindow(); }
            @Override public void windowOpened(WindowEvent e) { editor.requestFocusInWindow(); }
        });
        addNotify();
        privacy.setText(CapturePrivacy.enable(this));
        if (!transparent) status.setText("Transparency unavailable on this display; editing still works");
        if (!CapturePrivacy.isExcluded(this)) {
            privacy.setText(CapturePrivacy.lastResult());
            dispose();
            throw new IllegalStateException(
                    "Capture exclusion could not be applied; notes were never shown on screen");
        }
    }

    private static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(230, 239, 250));
        return label;
    }

    private static GlassButton glassButton(String text, Runnable action) {
        return new GlassButton(text, action);
    }

    private static JLabel controlLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(230, 239, 250));
        label.setFont(new Font("Calibri", Font.BOLD, 13));
        return label;
    }

    private void applyFontField() {
        try {
            int size = Integer.parseInt(fontField.getText().trim());
            if (size >= 10 && size <= 48) {
                editor.setFont(editor.getFont().deriveFont((float) size));
                saveSettings();
                editor.revalidate();
                glass.repaint();
            }
        } catch (NumberFormatException ignored) {}
    }

    private void commitFontField() {
        try {
            int size = Integer.parseInt(fontField.getText().trim());
            size = Math.min(48, Math.max(10, size));
            fontField.setText(String.valueOf(size));
            editor.setFont(editor.getFont().deriveFont((float) size));
            saveSettings();
            editor.revalidate();
            glass.repaint();
        } catch (NumberFormatException e) {
            fontField.setText(String.valueOf(editor.getFont().getSize()));
        }
    }

    private void enableDragging(JComponent component) {
        MouseAdapter drag = new MouseAdapter() {
            private Point offset;
            @Override public void mousePressed(MouseEvent e) {
                offset = new Point(e.getXOnScreen() - getX(), e.getYOnScreen() - getY());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (offset != null) setLocation(e.getXOnScreen() - offset.x, e.getYOnScreen() - offset.y);
            }
        };
        component.addMouseListener(drag);
        component.addMouseMotionListener(drag);
    }

    private void toggleHide() {
        if (hidden) {
            CapturePrivacy.uncloak(Main.this);
            setVisible(true);
            toFront();
            editor.requestFocusInWindow();
            hidden = false;
            privacy.setText(CapturePrivacy.isExcluded(Main.this) ? "Capture exclusion active" : "Capture exclusion lost");
        } else {
            CapturePrivacy.cloak(Main.this);
            setVisible(false);
            hidden = true;
            privacy.setText("Window hidden - press Ctrl+H to show");
        }
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        applyTheme();
        saveSettings();
    }

    private void applyTheme() {
        glass.dark = darkMode;
        themeText = darkMode ? new Color(255, 255, 255) : new Color(26, 26, 26);
        themeBorder = darkMode ? new Color(255, 255, 255, 51) : new Color(0, 0, 0, 46);
        themeBtnBg = darkMode ? new Color(255, 255, 255, 26) : new Color(0, 0, 0, 15);
        themeBtnHover = darkMode ? new Color(255, 255, 255, 51) : new Color(0, 0, 0, 31);
        Color dim = darkMode ? new Color(255, 255, 255, 204) : new Color(26, 26, 26, 204);
        Color inputBg = darkMode ? new Color(255, 255, 255, 230) : new Color(255, 255, 255, 217);
        if (darkMode) {
            editor.setForeground(Color.WHITE);
            editor.setCaretColor(Color.WHITE);
            editor.setSelectionColor(new Color(65, 100, 150));
            editor.setSelectedTextColor(Color.WHITE);
        } else {
            Color text = new Color(26, 26, 26);
            editor.setForeground(text);
            editor.setCaretColor(text);
            editor.setSelectionColor(new Color(180, 210, 240));
            editor.setSelectedTextColor(text);
        }
        headerStrip.setLineColor(themeBorder);
        controlsStrip.setLineColor(themeBorder);
        controlsStrip.setFillColor(new Color(
                darkMode ? 20 : 240, darkMode ? 30 : 245, darkMode ? 40 : 255,
                Math.min(255, Math.max(0, Math.round(glass.tint * 0.8f)))));
        applyThemeTo(glass, themeText);
        status.setForeground(dim);
        privacy.setForeground(dim);
        themeButton.setText(darkMode ? "LIGHT" : "DARK");
        themeButton.setGlyph(darkMode ? "\u2600" : "\u263E");
        fontField.setBackground(inputBg);
        fontField.setForeground(Color.BLACK);
        glass.repaint();
    }

    private void applyThemeTo(Container parent, Color labelColor) {
        for (Component component : parent.getComponents()) {
            if (component instanceof GlassButton) {
                ((GlassButton) component).updateTheme(themeText, themeBtnBg, themeBtnHover, themeBorder);
            } else if (component instanceof JLabel) {
                ((JLabel) component).setForeground(labelColor);
            }
            if (component instanceof Container) {
                applyThemeTo((Container) component, labelColor);
            }
        }
    }

    private void loadContent() {
        try {
            editor.setText(Files.readString(saveFile, StandardCharsets.UTF_8));
            editor.setCaretPosition(0);
            status.setText("Loaded " + saveFile.getFileName());
        } catch (NoSuchFileException e) {
            status.setText("New note - autosave on");
        } catch (IOException e) {
            loadFailed = true;
            status.setText("Could not load notes - " + e.getMessage());
        }
    }

    private boolean saveContent() {
        if (loadFailed) {
            status.setText("Autosave paused - original note could not be loaded");
            return false;
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(saveFile.getParent(), ".overlay-", ".tmp");
            Files.writeString(temporary, editor.getText(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            loadFailed = false;
            status.setText("Autosaved - " + saveFile.getFileName());
            return true;
        } catch (IOException e) {
            status.setText("Autosave failed - " + e.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ex) { System.err.println("Could not remove temporary note: " + temporary); }
            }
        }
    }

    private void saveSettings() {
        try {
            int tintValue = tintSlider != null ? tintSlider.getValue() : Math.round(glass.tint / 2.55f);
            String data = editor.getFont().getSize() + "\n" + tintValue + "\n" + (darkMode ? "dark" : "light");
            Files.writeString(saveFile.resolveSibling(".overlay_notes_settings"), data,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {}
    }

    private int loadFontSize() {
        try {
            Path settings = saveFile.resolveSibling(".overlay_notes_settings");
            if (Files.exists(settings)) {
                String[] parts = Files.readString(settings, StandardCharsets.UTF_8).trim().split("\n");
                return Math.min(48, Math.max(10, Integer.parseInt(parts[0].trim())));
            }
        } catch (Exception ignored) {}
        return 16;
    }

    private int loadTint() {
        try {
            Path settings = saveFile.resolveSibling(".overlay_notes_settings");
            if (Files.exists(settings)) {
                String[] parts = Files.readString(settings, StandardCharsets.UTF_8).trim().split("\n");
                int value = Integer.parseInt(parts[1].trim());
                if (value > 95) {
                    value = Math.round(value / 2.55f);
                }
                return Math.min(95, Math.max(5, value));
            }
        } catch (Exception ignored) {}
        return 65;
    }

    private boolean loadTheme() {
        try {
            Path settings = saveFile.resolveSibling(".overlay_notes_settings");
            if (Files.exists(settings)) {
                String[] parts = Files.readString(settings, StandardCharsets.UTF_8).trim().split("\n");
                if (parts.length >= 3) {
                    return !"light".equalsIgnoreCase(parts[2].trim());
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    private void closeWindow() {
        if (dirty) saveContent();
        dispose();
    }

    private static class GlassPanel extends JPanel {
        private int tint = 166;
        private boolean dark = true;
        GlassPanel() { setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            for (int i = 6; i >= 1; i--) {
                g.setColor(new Color(0, 0, 0, 7));
                g.fillRoundRect(i, i + 2, w - 2 * i, h - 2 * i, 18, 18);
            }
            Color rgb = dark ? new Color(20, 30, 40) : new Color(240, 245, 255);
            g.setColor(new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue(),
                    Math.min(255, Math.max(0, tint))));
            g.fillRoundRect(6, 6, w - 12, h - 12, 16, 16);
            g.setColor(dark ? new Color(255, 255, 255, 51) : new Color(0, 0, 0, 46));
            g.drawRoundRect(6, 6, w - 13, h - 13, 16, 16);
            g.dispose();
        }
    }

    private static class StripPanel extends JPanel {
        private boolean topLine;
        private boolean bottomLine;
        private boolean fill;
        private Color lineColor = new Color(255, 255, 255, 51);
        private Color fillColor = new Color(20, 30, 40, 133);
        StripPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }
        void setTopLine(boolean value) { topLine = value; }
        void setBottomLine(boolean value) { bottomLine = value; }
        void setFill(boolean value) { fill = value; }
        void setLineColor(Color color) { lineColor = color; repaint(); }
        void setFillColor(Color color) { fillColor = color; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            if (fill) {
                g.setColor(fillColor);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            g.setColor(lineColor);
            if (topLine) {
                g.drawLine(0, 0, getWidth(), 0);
            }
            if (bottomLine) {
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            }
            g.dispose();
        }
    }

    private static class GlassButton extends JButton {
        private Color bg = new Color(255, 255, 255, 26);
        private Color hoverBg = new Color(255, 255, 255, 51);
        private Color border = new Color(255, 255, 255, 51);
        private boolean hover;
        private String glyph;
        private static final Font GLYPH_FONT = new Font("Segoe UI Symbol", Font.PLAIN, 12);
        private static final int GLYPH_GAP = 5;
        GlassButton(String text, Runnable action) {
            super(text);
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setFont(new Font("Calibri", Font.PLAIN, 12));
            setBorder(BorderFactory.createEmptyBorder(7, 10, 3, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> action.run());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        void updateTheme(Color fg, Color bg, Color hoverBg, Color border) {
            setForeground(fg);
            this.bg = bg;
            this.hoverBg = hoverBg;
            this.border = border;
            repaint();
        }
        void setGlyph(String glyph) {
            this.glyph = glyph;
            repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color fill = bg;
            if (model.isEnabled() && (hover || (model.isPressed() && model.isArmed()))) {
                fill = hoverBg;
            }
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g.setColor(border);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            Font textFont = getFont();
            FontMetrics textMetrics = g.getFontMetrics(textFont);
            String label = getText();
            int labelWidth = textMetrics.stringWidth(label);
            int glyphWidth = 0;
            FontMetrics glyphMetrics = null;
            if (glyph != null) {
                glyphMetrics = g.getFontMetrics(GLYPH_FONT);
                glyphWidth = glyphMetrics.stringWidth(glyph) + GLYPH_GAP;
            }
            Insets insets = getInsets();
            int contentX = insets.left;
            int contentWidth = getWidth() - insets.left - insets.right;
            int contentY = insets.top;
            int contentHeight = getHeight() - insets.top - insets.bottom;
            int x = contentX + (contentWidth - glyphWidth - labelWidth) / 2;
            int y = contentY + (contentHeight - textMetrics.getHeight()) / 2 + textMetrics.getAscent();
            g.setColor(getForeground());
            if (glyph != null) {
                g.setFont(GLYPH_FONT);
                g.drawString(glyph, x, y);
                x += glyphWidth;
            }
            g.setFont(textFont);
            g.drawString(label, x, y);
            g.dispose();
        }
    }

    private static class RoundedField extends JTextField {
        RoundedField(String text, int columns) {
            super(text, columns);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class TintSliderUI extends BasicSliderUI {
        TintSliderUI(JSlider slider) { super(slider); }
        @Override protected Dimension getThumbSize() { return new Dimension(14, 14); }
        @Override public void paintTrack(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = trackRect.y + trackRect.height / 2 - 2;
            g.setColor(new Color(128, 128, 128, 102));
            g.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4);
            g.dispose();
        }
        @Override public void paintThumb(Graphics graphics) {
            if (thumbRect == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 90));
            g.fillOval(thumbRect.x, thumbRect.y + 1, 14, 14);
            g.setColor(Color.WHITE);
            g.fillOval(thumbRect.x, thumbRect.y, 14, 14);
            g.dispose();
        }
        @Override public void paintFocus(Graphics graphics) { }
    }

    private static boolean acquireSingleInstance(Path saveFile) {
        try {
            Path lockPath = saveFile.resolveSibling(".overlay_notes.lock");
            lockFile = new RandomAccessFile(lockPath.toFile(), "rw");
            lockChannel = lockFile.getChannel();
            appLock = lockChannel.tryLock();
            if (appLock == null) {
                return false;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (appLock != null) appLock.release(); } catch (IOException ignored) {}
                try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) {}
                try { if (lockFile != null) lockFile.close(); } catch (IOException ignored) {}
            }));
            return true;
        } catch (OverlappingFileLockException e) {
            return false;
        } catch (IOException | SecurityException e) {
            System.err.println("Single-instance lock unavailable, continuing: " + e.getMessage());
            return true;
        }
    }

    public static void main(String[] args) {
        Path saveFile = defaultSaveFile().toAbsolutePath();
        if (!acquireSingleInstance(saveFile)) {
            JOptionPane.showMessageDialog(null, "OverlayNotes is already running.",
                    "OverlayNotes", JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return;
        }
        SwingUtilities.invokeLater(() -> new Main(saveFile).setVisible(true));
    }
}