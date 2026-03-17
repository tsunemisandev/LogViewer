package com.logviewer.pipeline;

import com.logviewer.model.LogRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LineAssembler {

    // Parses: "2026-03-16 14:22:01.123 情報 [main] メッセージ"
    private static final Pattern HEADER = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s+(\\S+)\\s+\\[([^\\]]+)\\]\\s+(.*)$"
    );

    private final LineAssemblerStrategy strategy;
    private final List<String> buffer = new ArrayList<>();
    private String previousLine = null;

    public LineAssembler(LineAssemblerStrategy strategy) {
        this.strategy = strategy;
    }

    // Returns a completed LogRecord when a new record boundary is detected, null otherwise.
    public LogRecord feed(String line) {
        LogRecord completed = null;

        if (!buffer.isEmpty() && strategy.isNewRecord(line, previousLine)) {
            completed = buildRecord(buffer);
            buffer.clear();
        }

        buffer.add(line);
        previousLine = line;
        return completed;
    }

    // Flush the last buffered record at end-of-stream.
    public LogRecord flush() {
        if (buffer.isEmpty()) return null;
        LogRecord record = buildRecord(buffer);
        buffer.clear();
        return record;
    }

    // Returns a snapshot of the current buffer as a LogRecord WITHOUT clearing it.
    // Used in tail mode to preview the in-progress record during idle periods.
    public LogRecord peek() {
        if (buffer.isEmpty()) return null;
        return buildRecord(buffer);
    }

    private LogRecord buildRecord(List<String> lines) {
        Map<String, String> fields = new HashMap<>();

        if (!lines.isEmpty()) {
            Matcher m = HEADER.matcher(lines.get(0));
            if (m.matches()) {
                fields.put("timestamp", m.group(1));
                fields.put("level",     m.group(2));
                fields.put("thread",    m.group(3));
                fields.put("message",   m.group(4));
            } else {
                fields.put("message", lines.get(0));
            }
        }

        // fields must remain mutable so downstream headless plugins can enrich it
        return new LogRecord(List.copyOf(lines), fields);
    }
}
