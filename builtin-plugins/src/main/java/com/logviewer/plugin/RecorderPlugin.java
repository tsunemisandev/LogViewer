package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;
import com.logviewer.source.FileLogSource;
import com.logviewer.source.LogSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;

public class RecorderPlugin implements Plugin {

    private enum State { IDLE, RECORDING, STOPPING, STOPPED }

    private static final long DRAIN_MS = 600;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    // Injected via init()
    private Consumer<LogSource> onOpenAsSource;

    private final JButton startBtn    = new JButton("▶  Start");
    private final JButton stopBtn     = new JButton("■  Stop");
    private final JButton openBtn     = new JButton("→  ソースとして開く");
    private final JLabel  statusLabel = new JLabel("IDLE");
    private final JLabel  fileLabel   = new JLabel("—");
    private final JPanel  panel       = new JPanel(new BorderLayout());

    private volatile State state = State.IDLE;
    private BufferedWriter writer;
    private Path currentFile;

    public RecorderPlugin() {
        buildUI();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(Map<String, Object> context) {
        Object cb = context.get("addSource");
        if (cb instanceof Consumer<?>) {
            onOpenAsSource = (Consumer<LogSource>) cb;
        }
    }

    private void buildUI() {
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnRow.add(startBtn);
        btnRow.add(stopBtn);

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        infoPanel.add(row("Status :", statusLabel));
        infoPanel.add(Box.createVerticalStrut(4));
        infoPanel.add(row("File   :", fileLabel));
        infoPanel.add(Box.createVerticalStrut(12));

        JPanel openRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        openRow.add(openBtn);
        infoPanel.add(openRow);

        panel.add(btnRow,    BorderLayout.NORTH);
        panel.add(infoPanel, BorderLayout.CENTER);

        stopBtn.setEnabled(false);
        openBtn.setEnabled(false);

        startBtn.addActionListener(e -> startRecording());
        stopBtn.addActionListener(e -> stopRecording());
        openBtn.addActionListener(e -> openAsSource());
    }

    private JPanel row(String label, JLabel value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.add(new JLabel(label));
        p.add(value);
        return p;
    }

    @Override
    public void onEvent(LogEvent event) {
        if ((state != State.RECORDING && state != State.STOPPING) || writer == null) return;
        try {
            writer.write(event.rawLine());
            writer.newLine();
        } catch (IOException e) {
            stopRecording();
        }
    }

    @Override public void onRecord(LogRecord record) {}
    @Override public void onEndOfStream() {}

    private void startRecording() {
        try {
            Files.createDirectories(Path.of("recordings"));
            String ts = LocalDateTime.now().format(FILE_TS);
            currentFile = Path.of("recordings", "log-" + ts + ".log");
            writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8);
            state = State.RECORDING;

            SwingUtilities.invokeLater(() -> {
                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                openBtn.setEnabled(false);
                statusLabel.setText("RECORDING");
                fileLabel.setText(currentFile.toString());
            });
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("ERROR: " + e.getMessage()));
        }
    }

    private void stopRecording() {
        SwingUtilities.invokeLater(() -> {
            stopBtn.setEnabled(false);
            statusLabel.setText("STOPPING...");
        });

        new Thread(() -> {
            try { Thread.sleep(DRAIN_MS); } catch (InterruptedException ignored) {}
            state = State.STOPPING;
            try {
                if (writer != null) { writer.flush(); writer.close(); writer = null; }
            } catch (IOException ignored) {}
            state = State.STOPPED;
            SwingUtilities.invokeLater(() -> {
                startBtn.setEnabled(true);
                openBtn.setEnabled(currentFile != null && onOpenAsSource != null);
                statusLabel.setText("STOPPED");
            });
        }, "recorder-drain").start();
    }

    private void openAsSource() {
        if (currentFile != null && onOpenAsSource != null) {
            onOpenAsSource.accept(new FileLogSource(currentFile, false, 500));
        }
    }

    @Override public String id()       { return "recorder"; }
    @Override public String tabLabel() { return "Recorder"; }
    @Override public JComponent getComponent() { return panel; }
}
