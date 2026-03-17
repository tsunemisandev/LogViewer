package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * チェック処理の呼び出し結果を HTML テーブルで表示するプラグイン。
 *
 * 対象レコード: LogRecord.fields に "チェック呼び出し結果" キーを持つもの。
 *
 * 列:
 *   日時 | チェックID | 呼び出し結果 | 引き渡しパラメータ
 *
 * カラーコード:
 *   OK  → ライトグリーン (#90EE90)
 *   NG  → レッド        (#FF6666)
 */
public class CheckResultHtmlPlugin implements Plugin {

    private static final String RESULT_FIELD = "チェック呼び出し結果";

    private static final Set<String> SKIP_FIELDS = Set.of(
            "timestamp", "level", "thread", "message", "level.normalized", "_peek",
            "id", RESULT_FIELD
    );

    private final JEditorPane pane = new JEditorPane();
    private final JScrollPane scroll = new JScrollPane(pane);

    // TABLE 要素をキャッシュして毎回探索しない
    private Element tableElement = null;

    public CheckResultHtmlPlugin() {
        pane.setContentType("text/html");
        pane.setEditable(false);
        // NEVER_UPDATE: caret の移動によるスクロール位置リセットを防ぐ
        ((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        pane.setText(buildInitialHtml());
    }

    // ── Plugin ──────────────────────────────────────────────────────────────

    @Override public String id()        { return "check-result-html"; }
    @Override public String tabLabel()  { return "チェック結果"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return scroll; }

    @Override
    public void onRecord(LogRecord record) {
        // ライブ Tail のプレビュー行はスキップ
        if ("true".equals(record.get("_peek"))) return;

        String result = record.fields().get(RESULT_FIELD);
        if (result == null) return;

        String ts      = nvl(record.fields().get("timestamp"));
        String checkId = nvl(record.fields().get("id"));
        String params  = buildParams(record.fields());
        String rowHtml = buildRow(ts, checkId, result.trim(), params);

        SwingUtilities.invokeLater(() -> appendRow(rowHtml));
    }

    // ── HTML 構築 ────────────────────────────────────────────────────────────

    private String buildInitialHtml() {
        // <tbody> は Swing の HTML 3.2 パーサーが正しく扱わないため使用しない
        return "<html><body style='margin:4px;'>"
            + "<table border='1' cellpadding='5' cellspacing='0'"
            + " style='border-collapse:collapse;width:100%;"
            + "font-family:monospace;font-size:12px;'>"
            + "<tr style='background:#d0d0d0;'>"
            + "<th style='white-space:nowrap;'>日時</th>"
            + "<th>チェックID</th>"
            + "<th>呼び出し結果</th>"
            + "<th>引き渡しパラメータ</th>"
            + "</tr>"
            + "</table>"
            + "</body></html>";
    }

    private String buildRow(String ts, String checkId, String result, String params) {
        boolean isOk = result.equalsIgnoreCase("OK");
        String resultBg    = isOk ? "#90EE90" : "#FF6666";
        String resultColor = isOk ? "#1a6b1a" : "#8b0000";

        return "<tr>"
            + "<td style='white-space:nowrap;color:#555;'>" + esc(ts) + "</td>"
            + "<td>" + esc(checkId) + "</td>"
            + "<td style='background:" + resultBg + ";color:" + resultColor + ";"
            +     "font-weight:bold;text-align:center;'>" + esc(result) + "</td>"
            + "<td style='color:#444;'>" + esc(params) + "</td>"
            + "</tr>";
    }

    private String buildParams(Map<String, String> fields) {
        StringJoiner sj = new StringJoiner(", ");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (SKIP_FIELDS.contains(entry.getKey())) continue;
            sj.add(entry.getKey() + "=" + entry.getValue());
        }
        return sj.toString().isEmpty() ? "—" : sj.toString();
    }

    // ── DOM 操作 ─────────────────────────────────────────────────────────────

    private void appendRow(String rowHtml) {
        try {
            HTMLDocument doc = (HTMLDocument) pane.getDocument();

            // HtmlPlugin と同じ方法で TABLE 要素を探す（tbody+id より信頼性が高い）
            if (tableElement == null) {
                tableElement = doc.getElement(
                        doc.getDefaultRootElement(),
                        StyleConstants.NameAttribute, HTML.Tag.TABLE);
            }
            if (tableElement == null) return;

            JScrollBar vsb = scroll.getVerticalScrollBar();
            int savedValue = vsb.getValue();
            boolean atBottom = savedValue >= vsb.getMaximum() - vsb.getVisibleAmount() - 5;

            doc.insertBeforeEnd(tableElement, rowHtml);

            SwingUtilities.invokeLater(() -> {
                if (atBottom) {
                    vsb.setValue(vsb.getMaximum());
                } else {
                    vsb.setValue(savedValue);
                }
            });
        } catch (Exception ignored) {
            // HTMLDocument の内部エラーを無視
        }
    }

    // ── ユーティリティ ────────────────────────────────────────────────────────

    private String esc(String s) {
        if (s == null || s.isEmpty()) return "&nbsp;";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
