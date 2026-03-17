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
import java.util.List;

public class HtmlPlugin implements Plugin {

    /**
     * HTMLDocument は DOM のオーバーヘッドが大きい（テキスト量の10〜20倍）ため、
     * 表示レコード数を上限で止める。Rolling 削除はDOM破損リスクがあるため行わない。
     */
    private static final int MAX_RECORDS = 5_000;

    private final JEditorPane pane = new JEditorPane();
    private final JScrollPane scroll = new JScrollPane(pane);

    // EDT-only state for peek/replace logic
    private Element lastPeekElement = null;
    private String  lastPeekTs      = null;

    // EDT-only: 確定レコード数（peek は除く）
    private int recordCount = 0;
    private boolean limitReached = false;

    public HtmlPlugin() {
        pane.setContentType("text/html");
        pane.setEditable(false);
        // NEVER_UPDATE: caret の移動によるスクロール位置リセットを防ぐ
        ((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        pane.setText("<html><body style='font-family:monospace;font-size:12px;margin:0;padding:0;'></body></html>");
    }

    @Override public String id()          { return "html"; }
    @Override public String tabLabel()    { return "HTML"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return scroll; }

    @Override
    public void onRecord(LogRecord record) {
        boolean isPeek = "true".equals(record.get("_peek"));
        String  ts     = nvl(record.timestamp());
        String  html   = buildHtml(record);
        SwingUtilities.invokeLater(() -> appendOrReplace(html, isPeek, ts));
    }

    private void appendOrReplace(String html, boolean isPeek, String ts) {
        try {
            HTMLDocument doc  = (HTMLDocument) pane.getDocument();
            Element body = doc.getElement(
                    doc.getDefaultRootElement(),
                    StyleConstants.NameAttribute, HTML.Tag.BODY);
            if (body == null) return;

            // 上限チェック（peek はカウントしない）
            if (!isPeek && recordCount >= MAX_RECORDS) {
                if (!limitReached) {
                    limitReached = true;
                    String notice = "<div style='padding:4px 8px;background:#fff3cd;"
                            + "color:#856404;border-top:2px solid #ffc107;font-weight:bold;'>"
                            + "⚠ 表示上限 " + String.format("%,d", MAX_RECORDS)
                            + " 件に達しました。以降のレコードは HTML タブに表示されません。"
                            + "Grid タブをご利用ください。</div>";
                    doc.insertBeforeEnd(body, notice);
                }
                return;
            }

            JScrollBar vsb = scroll.getVerticalScrollBar();
            int savedValue = vsb.getValue();
            boolean atBottom = savedValue >= vsb.getMaximum() - vsb.getVisibleAmount() - 5;

            if (lastPeekElement != null && ts.equals(lastPeekTs)) {
                doc.setOuterHTML(lastPeekElement, html);
                lastPeekElement = isPeek ? body.getElement(body.getElementCount() - 1) : null;
                lastPeekTs      = isPeek ? ts : null;
            } else {
                doc.insertBeforeEnd(body, html);
                if (!isPeek) recordCount++;
                lastPeekElement = isPeek ? body.getElement(body.getElementCount() - 1) : null;
                lastPeekTs      = isPeek ? ts : null;
            }

            SwingUtilities.invokeLater(() -> {
                if (atBottom) {
                    vsb.setValue(vsb.getMaximum());
                } else {
                    vsb.setValue(savedValue);
                }
            });
        } catch (Exception e) {
            // ignore rendering errors
        }
    }

    private String buildHtml(LogRecord record) {
        String normalized = record.get("level.normalized");
        if (normalized == null) normalized = nvl(record.level()).toUpperCase();

        String bg = switch (normalized) {
            case "ERROR" -> "#ffe0e0";
            case "WARN"  -> "#fff3cd";
            case "DEBUG" -> "#f0f0f0";
            default      -> "#ffffff";
        };

        String levelColor = switch (normalized) {
            case "ERROR" -> "#cc0000";
            case "WARN"  -> "#cc6600";
            case "DEBUG" -> "#888888";
            default      -> "#003399";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin:1px 0;padding:2px 4px;background:").append(bg)
          .append(";border-bottom:1px solid #ddd;'>");
        sb.append("<span style='color:#888;'>").append(esc(nvl(record.timestamp()))).append("</span> ");
        sb.append("<b style='color:").append(levelColor).append(";'>[").append(esc(nvl(record.level()))).append("]</b> ");
        sb.append("<span style='color:#555;'>").append(esc(nvl(record.thread()))).append("</span> ");
        sb.append("<span>").append(esc(nvl(record.message()))).append("</span>");

        List<String> lines = record.rawLines();
        if (lines.size() > 1) {
            sb.append("<div style='margin-left:20px;color:#444;'>");
            for (int i = 1; i < lines.size(); i++) {
                sb.append(esc(lines.get(i).strip())).append("<br>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
