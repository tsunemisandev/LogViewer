package com.logviewer.sample;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;
import com.logviewer.plugin.Plugin;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.util.Map;

/**
 * Sample external plugin — demonstrates the Plugin API.
 *
 * Collects all LogRecord fields except the standard header fields
 * (timestamp, level, thread, message) and displays them in a table.
 * Useful for visualizing custom structured data extracted by
 * FieldExtractorPlugin (e.g. "id/name:10/john" or "チェック呼び出し結果：OK").
 */
public class CheckResultTablePlugin implements Plugin {

    private static final java.util.Set<String> SKIP_FIELDS = java.util.Set.of(
            "timestamp", "level", "thread", "message", "level.normalized", "_peek"
    );

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Timestamp", "Field", "Value"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);

    public CheckResultTablePlugin() {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
    }

    @Override public String id()       { return "check-result-table"; }
    @Override public String tabLabel() { return "Check Results"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return scroll; }

    @Override
    public void onRecord(LogRecord record) {
        // Skip peek snapshots
        if ("true".equals(record.fields().get("_peek"))) return;

        String ts = record.fields().getOrDefault("timestamp", "");
        boolean hasCustomFields = false;

        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            if (SKIP_FIELDS.contains(entry.getKey())) continue;
            hasCustomFields = true;
            String field = entry.getKey();
            String value = entry.getValue();
            SwingUtilities.invokeLater(() -> model.addRow(new Object[]{ts, field, value}));
        }

        // If there are no extracted fields, show a row with the raw message
        // so the user can see something in the table.
        if (!hasCustomFields) {
            String msg = record.fields().getOrDefault("message", "");
            if (!msg.isEmpty()) {
                SwingUtilities.invokeLater(() -> model.addRow(new Object[]{ts, "(message)", msg}));
            }
        }
    }
}
