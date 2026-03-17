package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GridPlugin implements Plugin {

    // モデル列インデックス
    private static final int COL_TS         = 0;
    private static final int COL_LEVEL      = 1;
    private static final int COL_THREAD     = 2;
    private static final int COL_MESSAGE    = 3;
    private static final int COL_CONTENT    = 4;
    private static final int COL_LINES      = 5;
    private static final int COL_NORMALIZED = 6; // 非表示・フィルタ/色付け用

    private static final String[] COLUMNS = {
        "Timestamp", "Level", "Thread", "Message", "内容", "Lines", "_level"
    };

    /** 保持する最大行数。超えた分は先頭から削除（ローリングウィンドウ）*/
    private static final int MAX_ROWS = 100_000;

    // レベル別背景色
    private static final Color COLOR_ERROR = new Color(255, 218, 218);
    private static final Color COLOR_WARN  = new Color(255, 243, 205);
    private static final Color COLOR_DEBUG = new Color(238, 238, 238);

    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMNS, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTable  table     = new JTable(tableModel);
    private final JPanel  mainPanel = new JPanel(new BorderLayout(0, 2));

    // フィルタ状態（EDT のみ）
    private String levelFilter = "ALL";
    private String textFilter  = "";

    // peek/replace 状態（EDT のみ）
    private boolean lastRowIsPeek = false;
    private String  lastPeekTs    = null;

    // ローリングウィンドウ状態（EDT のみ）
    private final JLabel rowCountLabel = new JLabel();
    private boolean rollingActive = false;

    public GridPlugin() {
        // _level 列をビューから隠す（モデルには残す）
        table.removeColumn(table.getColumnModel().getColumn(COL_NORMALIZED));

        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setRowSorter(sorter);
        table.setDefaultRenderer(Object.class, new LevelColorRenderer());
        table.setFillsViewportHeight(true);

        // 列幅（ビュー: 0=TS, 1=Level, 2=Thread, 3=Message, 4=内容, 5=Lines）
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(280);
        table.getColumnModel().getColumn(4).setPreferredWidth(280);
        table.getColumnModel().getColumn(5).setPreferredWidth(45);

        rowCountLabel.setForeground(new Color(150, 80, 0));
        mainPanel.add(buildFilterPanel(), BorderLayout.NORTH);
        mainPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
    }

    // ── フィルタUI ────────────────────────────────────────────────────────────

    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        // レベルフィルタ
        panel.add(new JLabel("レベル:"));
        JComboBox<String> levelBox = new JComboBox<>(
                new String[]{"ALL", "ERROR", "WARN", "INFO", "DEBUG"});
        levelBox.addActionListener(e -> {
            levelFilter = (String) levelBox.getSelectedItem();
            applyFilter();
        });
        panel.add(levelBox);

        // テキスト検索
        panel.add(new JLabel("検索:"));
        JTextField searchField = new JTextField(22);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { textFilter = searchField.getText(); applyFilter(); }
            @Override public void insertUpdate(DocumentEvent e)  { update(); }
            @Override public void removeUpdate(DocumentEvent e)  { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        panel.add(searchField);

        // クリアボタン
        JButton clearBtn = new JButton("クリア");
        clearBtn.addActionListener(e -> {
            searchField.setText("");
            levelBox.setSelectedIndex(0);
        });
        panel.add(clearBtn);
        panel.add(rowCountLabel);

        return panel;
    }

    private void applyFilter() {
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        // レベルフィルタ（非表示の COL_NORMALIZED 列を使用）
        if (!"ALL".equals(levelFilter)) {
            filters.add(RowFilter.regexFilter(
                    "(?i)^" + Pattern.quote(levelFilter) + "$", COL_NORMALIZED));
        }

        // テキストフィルタ（TS〜内容 の全列を OR 検索）
        String trimmed = textFilter.trim();
        if (!trimmed.isEmpty()) {
            try {
                String regex = "(?i)" + Pattern.quote(trimmed);
                List<RowFilter<Object, Object>> ors = new ArrayList<>();
                for (int i = COL_TS; i <= COL_CONTENT; i++) {
                    ors.add(RowFilter.regexFilter(regex, i));
                }
                filters.add(RowFilter.orFilter(ors));
            } catch (Exception ignored) {}
        }

        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    // ── Plugin ───────────────────────────────────────────────────────────────

    @Override public String id()       { return "grid"; }
    @Override public String tabLabel() { return "Grid"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return mainPanel; }

    @Override
    public void onRecord(LogRecord record) {
        boolean isPeek   = "true".equals(record.get("_peek"));
        String  content  = buildContent(record);
        String  normalized = nvl(record.fields().get("level.normalized"));

        Object[] row = {
            nvl(record.timestamp()),
            nvl(record.level()),
            nvl(record.thread()),
            nvl(record.message()),
            content,
            String.valueOf(record.rawLines().size()),
            normalized   // 非表示列
        };
        String ts = nvl(record.timestamp());
        SwingUtilities.invokeLater(() -> addOrUpdate(row, isPeek, ts));
    }

    /** 継続行（index 1+）をパイプ区切りで連結して内容列の文字列を作る */
    private String buildContent(LogRecord record) {
        if (record.rawLines().size() <= 1) return "";
        return record.rawLines().subList(1, record.rawLines().size())
                .stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" │ "));
    }

    private void addOrUpdate(Object[] row, boolean isPeek, String ts) {
        int lastRow = tableModel.getRowCount() - 1;
        if (lastRow >= 0 && lastRowIsPeek && ts.equals(lastPeekTs)) {
            tableModel.removeRow(lastRow);
            tableModel.addRow(row);
        } else {
            tableModel.addRow(row);
        }
        lastRowIsPeek = isPeek;
        lastPeekTs    = isPeek ? ts : null;

        // ローリングウィンドウ: MAX_ROWS を超えたら先頭から削除
        if (tableModel.getRowCount() > MAX_ROWS) {
            tableModel.removeRow(0);
            if (!rollingActive) {
                rollingActive = true;
            }
            rowCountLabel.setText(
                    String.format("  ⚠ 最新 %,d 件を表示（古い行は破棄済み）", MAX_ROWS));
        } else if (rollingActive || tableModel.getRowCount() % 10_000 == 0) {
            rowCountLabel.setText(String.format("  %,d 件", tableModel.getRowCount()));
        }
    }

    // ── セルレンダラー ────────────────────────────────────────────────────────

    private class LevelColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable tbl, Object value, boolean selected,
                boolean focused, int viewRow, int viewCol) {

            Component c = super.getTableCellRendererComponent(
                    tbl, value, selected, focused, viewRow, viewCol);

            if (!selected) {
                int modelRow = tbl.convertRowIndexToModel(viewRow);
                String level = (String) tableModel.getValueAt(modelRow, COL_NORMALIZED);
                c.setBackground(levelColor(nvl(level), tbl.getBackground()));
            }
            return c;
        }
    }

    private Color levelColor(String normalized, Color defaultColor) {
        return switch (normalized) {
            case "ERROR" -> COLOR_ERROR;
            case "WARN"  -> COLOR_WARN;
            case "DEBUG" -> COLOR_DEBUG;
            default      -> defaultColor;
        };
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
