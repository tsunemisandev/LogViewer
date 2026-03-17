package com.logviewer.pipeline;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;
import com.logviewer.plugin.Plugin;
import com.logviewer.source.FileLogSource;
import com.logviewer.source.LogSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LogPipeline {

    private final LogSource source;
    private final List<Plugin> plugins;
    private final Consumer<String> onStatusUpdate;
    private final LineAssembler assembler = new LineAssembler(new PrefixDateStrategy());
    private final boolean tailEnabled;

    private Thread readerThread;
    private volatile boolean running = false;
    private final AtomicLong lineCount   = new AtomicLong(0);
    private final AtomicLong recordCount = new AtomicLong(0);

    public LogPipeline(LogSource source, List<Plugin> plugins, Consumer<String> onStatusUpdate) {
        this.source = source;
        this.plugins = plugins;
        this.onStatusUpdate = onStatusUpdate;
        this.tailEnabled = source instanceof FileLogSource fls && fls.isTail();
    }

    public void start() {
        running = true;
        readerThread = new Thread(() -> {
            if (source instanceof FileLogSource fls) {
                runWithFileChannel(fls);
            } else {
                runWithInputStream();
            }
        }, "reader-" + source.name());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void runWithFileChannel(FileLogSource fls) {
        try (FileLineReader reader = new FileLineReader(fls.path(), fls.isTail(), fls.isTailOnly(), fls.pollIntervalMs())) {
            long offset = 0;
            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    if (fls.isTail()) {
                        emitPeek(); // idle — preview current buffer
                    } else {
                        break; // EOF
                    }
                } else {
                    processLine(line, offset);
                    offset += line.length() + 1;
                }
            }
            finishStream();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            onStatusUpdate.accept("ERROR: " + e.getMessage());
        }
    }

    /** Emits a snapshot of the assembler's current buffer to all plugins as a preview record. */
    private void emitPeek() {
        LogRecord peeked = assembler.peek();
        if (peeked == null) return;
        peeked.fields().put("_peek", "true");
        for (Plugin p : plugins) p.onRecord(peeked);
    }

    private void runWithInputStream() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(source.open()))) {
            String line;
            long offset = 0;
            while (running && (line = reader.readLine()) != null) {
                processLine(line, offset);
                offset += line.length() + 1;
            }
            finishStream();
        } catch (Exception e) {
            onStatusUpdate.accept("ERROR: " + e.getMessage());
        }
    }

    private void processLine(String line, long offset) {
        long count = lineCount.incrementAndGet();
        LogEvent event = new LogEvent(line, offset, Instant.now());

        for (Plugin p : plugins) p.onEvent(event);

        LogRecord record = assembler.feed(line);
        if (record != null) {
            recordCount.incrementAndGet();
            for (Plugin p : plugins) p.onRecord(record);
        }

        if (count % 100 == 0) updateStatus();
    }

    private void finishStream() {
        LogRecord last = assembler.flush();
        if (last != null) {
            recordCount.incrementAndGet();
            for (Plugin p : plugins) p.onRecord(last);
        }
        for (Plugin p : plugins) p.onEndOfStream();
        updateStatus();
    }

    public void stop() {
        running = false;
        if (readerThread != null) readerThread.interrupt();
    }

    public String sourceName() { return source.name(); }
    public boolean isTail()    { return tailEnabled; }

    private void updateStatus() {
        long l = lineCount.get();
        long r = recordCount.get();
        String tail = tailEnabled ? "ON" : "OFF";
        onStatusUpdate.accept("lines: " + l + " | records: " + r + " | tail: " + tail);
    }
}
