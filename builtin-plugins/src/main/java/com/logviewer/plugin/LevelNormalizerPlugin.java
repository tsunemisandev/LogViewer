package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;

import javax.swing.JComponent;
import java.util.Map;

/**
 * Headless plugin — maps locale-specific level strings to standard values.
 * Writes the result into fields["level.normalized"].
 */
public class LevelNormalizerPlugin implements Plugin {

    private static final Map<String, String> LEVEL_MAP = Map.of(
            "エラー",   "ERROR",
            "警告",     "WARN",
            "情報",     "INFO",
            "デバッグ", "DEBUG"
    );

    @Override public String id()          { return "level-normalizer"; }
    @Override public String tabLabel()    { return null; }
    @Override public int    priority()    { return -10; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return null; }

    @Override
    public void onRecord(LogRecord record) {
        String level = record.level();
        if (level == null) return;
        String normalized = LEVEL_MAP.getOrDefault(level, level.toUpperCase());
        record.fields().put("level.normalized", normalized);
    }
}
