package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;
import com.logviewer.plugin.extractor.ColonExtractor;
import com.logviewer.plugin.extractor.FieldExtractor;
import com.logviewer.plugin.extractor.SlashPairExtractor;

import javax.swing.JComponent;
import java.util.List;

/**
 * Headless plugin — runs before render plugins.
 * Iterates over continuation lines (index 1+) and applies each FieldExtractor.
 * Extracted key/value pairs are added to LogRecord.fields for downstream plugins.
 */
public class FieldExtractorPlugin implements Plugin {

    private final List<FieldExtractor> extractors = List.of(
            new SlashPairExtractor(),
            new ColonExtractor()
    );

    @Override public String id()          { return "field-extractor"; }
    @Override public String tabLabel()    { return null; }
    @Override public int    priority()    { return -20; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return null; }

    @Override
    public void onRecord(LogRecord record) {
        List<String> lines = record.rawLines();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) continue;
            for (FieldExtractor ex : extractors) {
                ex.extract(line, record.fields());
            }
        }
    }
}
