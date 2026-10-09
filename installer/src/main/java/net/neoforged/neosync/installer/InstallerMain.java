/*
 * Copyright (c) NeoSync contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neosync.installer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import net.minecraftforge.installer.SimpleInstaller;
import net.minecraftforge.installer.json.Util;

public final class InstallerMain {
    private static final Color BACKGROUND = new Color(15, 16, 19);
    private static final Color PANEL = new Color(25, 27, 32);
    private static final Color TEXT = new Color(240, 240, 243);
    private static final Color SECONDARY = new Color(157, 160, 169);
    private static final Color DISABLED = new Color(100, 103, 113);
    private final JFrame frame = new JFrame("NeoSync Installer");
    private final JPanel choices = new JPanel();
    private final ButtonGroup group = new ButtonGroup();
    private final List<Card> cards = new ArrayList<>();
    private final JButton install = button("Install NeoSync");
    private final JButton browse = button("+  Choose another folder...");
    private final JLabel status = label("Your existing mods and worlds stay in their own folders.", 12, SECONDARY);
    private final JProgressBar progress = new JProgressBar();
    private LauncherTarget selected;
    private boolean busy;

    private InstallerMain() throws Exception {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(850, 630));
        frame.setSize(960, 840);
        frame.setLocationRelativeTo(null);
        frame.setIconImage(ImageIO.read(InstallerMain.class.getResource("/icons/neoforged_background_128x128.png")));
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(BACKGROUND);
        content.add(branding(), BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(0, 20));
        right.setBackground(BACKGROUND);
        right.setBorder(BorderFactory.createEmptyBorder(38, 30, 28, 30));
        JPanel heading = vertical(BACKGROUND);
        heading.add(label("Welcome to NeoSync", 27, TEXT));
        heading.add(Box.createVerticalStrut(10));
        heading.add(label("Choose the launcher where you want to play.", 14, SECONDARY));
        heading.add(Box.createVerticalStrut(5));
        heading.add(label("Close the launcher before installing.", 13, SECONDARY));
        right.add(heading, BorderLayout.NORTH);
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        choices.setBackground(BACKGROUND);
        for (LauncherTarget target : LauncherTarget.discover(System.getenv(), Path.of(System.getProperty("user.home")), System.getProperty("os.name"))) addTarget(target);
        browse.setToolTipText("A .minecraft folder from another launcher, or another existing installation folder");
        browse.setAlignmentX(0);
        browse.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        browse.addActionListener(event -> chooseFolder());
        choices.add(browse);
        choices.add(Box.createVerticalStrut(7));
        choices.add(label("A .minecraft folder from another launcher, or your own folder.", 11, SECONDARY));
        JScrollPane scroll = new JScrollPane(choices);
        scroll.setBorder(null);
        scroll.setBackground(BACKGROUND);
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        right.add(scroll, BorderLayout.CENTER);
        JPanel footer = vertical(BACKGROUND);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setAlignmentX(0);
        footer.add(progress);
        footer.add(Box.createVerticalStrut(12));
        install.setAlignmentX(0);
        install.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        install.setPreferredSize(new Dimension(240, 48));
        install.setEnabled(false);
        install.setBackground(TEXT);
        install.setForeground(BACKGROUND);
        install.addActionListener(event -> start());
        footer.add(install);
        footer.add(Box.createVerticalStrut(12));
        status.setToolTipText(status.getText());
        footer.add(status);
        right.add(footer, BorderLayout.SOUTH);
        content.add(right, BorderLayout.CENTER);
        frame.setContentPane(content);
        scroll.getVerticalScrollBar().setValue(0);
        frame.addWindowFocusListener(new java.awt.event.WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                if (!busy) cards.forEach(card -> card.setEnabled(card.target.available()));
            }

            @Override
            public void windowLostFocus(WindowEvent event) {}
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (busy) {
                    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    status.setText("Please wait for installation to finish before closing.");
                }
            }
        });
        frame.setVisible(true);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--installer-check")) {
            if (!Util.loadInstallProfile().getVersion().startsWith("NeoSync-")) throw new IllegalStateException("Missing installer identity.");
            Class.forName("org.sqlite.JDBC");
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:"); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT json('{}')")) {
                if (!rows.next()) throw new IllegalStateException("SQLite is unavailable.");
            }
            System.out.println("NeoSync installer self-test passed.");
            return;
        }
        if (args.length > 0) {
            if (args.length == 3 && args[0].equals("--install-launcher")) {
                var kind = LauncherTarget.Kind.valueOf(args[1].toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
                Path result = InstallerService.install(new LauncherTarget(kind, Path.of(args[2])), ownJar(), System.out::println);
                System.out.println("NeoSync is ready: " + result);
                return;
            }
            SimpleInstaller.main(args);
            return;
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Button.font", new Font("SansSerif", Font.BOLD, 14));
        SwingUtilities.invokeLater(() -> {
            try {
                new InstallerMain();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, error.getMessage(), "NeoSync Installer", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static Path ownJar() throws Exception {
        return Path.of(InstallerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private JPanel branding() throws Exception {
        JPanel left = vertical(PANEL);
        left.setPreferredSize(new Dimension(300, 630));
        left.setBorder(BorderFactory.createEmptyBorder(50, 32, 36, 32));
        left.add(Box.createVerticalGlue());
        var logo = ImageIO.read(InstallerMain.class.getResource("/icons/neoforged_background_128x128.png"));
        JLabel mark = new JLabel(new javax.swing.ImageIcon(logo.getScaledInstance(165, 165, java.awt.Image.SCALE_SMOOTH)));
        mark.setAlignmentX(0.5f);
        left.add(mark);
        left.add(Box.createVerticalStrut(24));
        JLabel name = label("NEOSYNC", 31, TEXT);
        name.setAlignmentX(0.5f);
        left.add(name);
        left.add(Box.createVerticalStrut(15));
        JLabel tagline = label("YOUR SERVER. YOUR MODS.", 11, SECONDARY);
        tagline.setAlignmentX(0.5f);
        left.add(tagline);
        left.add(Box.createVerticalStrut(24));
        String version = Util.loadInstallProfile().getVersion();
        JLabel build = label(version.substring("NeoSync-".length(), version.indexOf("-neoforge-")), 12, SECONDARY);
        build.setAlignmentX(0.5f);
        left.add(build);
        left.add(Box.createVerticalGlue());
        JLabel minecraft = label("Minecraft 1.21.1 · Java 21", 12, SECONDARY);
        minecraft.setAlignmentX(0.5f);
        left.add(minecraft);
        return left;
    }

    private void addTarget(LauncherTarget target) {
        Card card = new Card(target);
        group.add(card);
        cards.add(card);
        choices.add(card);
        choices.add(Box.createVerticalStrut(10));
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an existing launcher or Minecraft folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        LauncherTarget target = LauncherTarget.custom(chooser.getSelectedFile().toPath());
        if (!target.available()) {
            JOptionPane.showMessageDialog(frame, "Choose an existing folder without symbolic links.", "Folder unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Card card = new Card(target);
        group.add(card);
        cards.add(card);
        choices.add(card, Math.max(0, choices.getComponentCount() - 3));
        card.setSelected(true);
        select(target);
        choices.revalidate();
        choices.repaint();
    }

    private void select(LauncherTarget target) {
        if (!target.available()) return;
        selected = target;
        install.setEnabled(true);
        status.setText("Ready to install in " + target.kind().label() + ".");
        status.setToolTipText(target.root().toString());
        cards.forEach(Card::repaint);
    }

    private void start() {
        if (selected == null || !selected.available()) {
            install.setEnabled(false);
            return;
        }
        busy = true;
        cards.forEach(card -> card.setEnabled(false));
        browse.setEnabled(false);
        install.setEnabled(false);
        progress.setVisible(true);
        LauncherTarget target = selected;
        new SwingWorker<Path, String>() {
            @Override
            protected Path doInBackground() throws Exception {
                return InstallerService.install(target, ownJar(), this::publish);
            }

            @Override
            protected void process(List<String> messages) {
                String text = messages.get(messages.size() - 1);
                status.setText(text.length() > 82 ? text.substring(0, 79) + "..." : text);
                status.setToolTipText(text);
            }

            @Override
            protected void done() {
                busy = false;
                progress.setVisible(false);
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                cards.forEach(card -> card.setEnabled(card.target.available()));
                browse.setEnabled(true);
                install.setEnabled(selected != null && selected.available());
                try {
                    Path instance = get();
                    status.setText("Installed. Reopen your launcher, select NeoSync and press Play.");
                    JOptionPane.showMessageDialog(frame, "NeoSync is ready in " + target.kind().label() + ".\nReopen the launcher, select the NeoSync instance and press Play.\n\n" + instance, "Installation complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    status.setText("Installation stopped. Your existing instances were preserved.");
                    JOptionPane.showMessageDialog(frame, cause.getMessage(), "Installation could not finish", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private final class Card extends JRadioButton {
        private final LauncherTarget target;
        private final java.awt.Image icon;

        Card(LauncherTarget target) {
            this.target = target;
            String resource = switch (target.kind()) {
                case PRISM -> "prism";
                case SKLAUNCHER -> "sklauncher";
                case SKLAUNCHER_BETA -> "sklauncher-beta";
                case MODRINTH -> "modrinth";
                default -> "minecraft";
            };
            try {
                icon = ImageIO.read(InstallerMain.class.getResource("/launchers/" + resource + ".png"));
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Launcher icon is missing.", error);
            }
            setText(target.kind().label() + " — " + target.root());
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setAlignmentX(0);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
            setPreferredSize(new Dimension(500, 76));
            setEnabled(target.available());
            setCursor(Cursor.getPredefinedCursor(isEnabled() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            setToolTipText(isEnabled() ? target.root().toString() : "Not installed · " + target.kind().website() + " · Install it, open it once and reopen this installer.");
            getAccessibleContext().setAccessibleName(target.kind().label());
            getAccessibleContext().setAccessibleDescription(getToolTipText());
            addActionListener(event -> select(target));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean present = target.available();
            g.setColor(isSelected() ? new Color(42, 45, 53) : PANEL);
            g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
            g.setColor(isSelected() ? TEXT : new Color(53, 56, 66));
            if (!present) g.setStroke(new java.awt.BasicStroke(1, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND, 1, new float[] { 3, 4 }, 0));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, present ? 1f : 0.35f));
            g.drawImage(icon, 16, 20, 36, 36, null);
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.setColor(present ? TEXT : DISABLED);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString(target.kind().label(), 66, 29);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setColor(present ? SECONDARY : DISABLED);
            String detail = present ? target.root().toString() : "Not installed · " + target.kind().website();
            while (g.getFontMetrics().stringWidth(detail) > getWidth() - 112 && detail.length() > 4) detail = detail.substring(0, detail.length() - 4) + "...";
            g.drawString(detail, 66, 47);
            if (!present) g.drawString("Install it, open it once and reopen this installer.", 66, 63);
            if (present) {
                g.setColor(isSelected() ? TEXT : SECONDARY);
                g.drawOval(getWidth() - 32, 30, 14, 14);
                if (isSelected()) g.fillOval(getWidth() - 29, 33, 8, 8);
            }
            if (hasFocus()) {
                g.setColor(SECONDARY);
                g.drawRoundRect(4, 4, getWidth() - 9, getHeight() - 9, 15, 15);
            }
            g.dispose();
        }
    }

    private static JPanel vertical(Color background) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(background);
        return panel;
    }

    private static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", size >= 20 ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        label.setAlignmentX(0);
        return label;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setBackground(PANEL);
        button.setForeground(TEXT);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(66, 69, 80)), BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        return button;
    }
}
