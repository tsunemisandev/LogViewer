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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * メソッド呼び出しのトレースを HTML テーブルで表示するプラグイン。
 *
 * 対象レコード:
 *   開始: message に "メソッド呼び出し開始" を含む
 *   終了: message に "メソッド呼び出し終了" を含む
 *
 * 仮定するログフォーマット:
 *
 *   --- 開始ログ ---
 *   2026-03-16 14:22:01.000 情報 [worker-1] メソッド呼び出し開始
 *       行名：注文処理
 *       メソッド：com.example.OrderService#processOrder
 *       引数：orderId=12345, userId=alice
 *
 *   --- 終了ログ（シンプル値） ---
 *   2026-03-16 14:22:01.500 情報 [worker-1] メソッド呼び出し終了
 *       メソッド：com.example.OrderService#processOrder
 *       戻り値：SUCCESS
 *
 *   --- 終了ログ（オブジェクト） ---
 *   2026-03-16 14:22:01.500 情報 [worker-1] メソッド呼び出し終了
 *       メソッド：com.example.OrderService#processOrder
 *       戻り値.orderId：12345
 *       戻り値.status：SUCCESS
 *       戻り値.totalAmount：9800
 *
 * ペアリングキー: スレッド名 + メソッド名
 *
 * 列: 日時 | 行名 | メソッド | 引数 | 戻り値
 *
 * 戻り値カラーコード:
 *   SUCCESS / OK  → 緑
 *   ERROR / NG 等 → 赤
 *   （未完了）      → 黄
 */
public class MethodTraceHtmlPlugin implements Plugin {

    private static final String START_KEYWORD = "メソッド呼び出し開始";
    private static final String END_KEYWORD   = "メソッド呼び出し終了";

    /** 戻り値オブジェクトフィールドのプレフィックス ("戻り値.status" など) */
    private static final String RET_PREFIX = "戻り値.";

    /** 戻り値フィールドのキー（シンプル値用） */
    private static final String RET_FIELD = "戻り値";

    // ── 状態 ─────────────────────────────────────────────────────────────────

    /** ペアリング待ちの開始ログ。キー: "スレッド:メソッド名" */
    private final Map<String, PendingCall> pending = new LinkedHashMap<>();

    private final JEditorPane pane   = new JEditorPane();
    private final JScrollPane scroll = new JScrollPane(pane);
    private Element tableElement     = null;

    // ── コンストラクタ ────────────────────────────────────────────────────────

    public MethodTraceHtmlPlugin() {
        pane.setContentType("text/html");
        pane.setEditable(false);
        ((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        pane.setText(buildInitialHtml());
    }

    // ── Plugin ───────────────────────────────────────────────────────────────

    @Override public String id()        { return "method-trace-html"; }
    @Override public String tabLabel()  { return "メソッドトレース"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public JComponent getComponent() { return scroll; }

    @Override
    public void onRecord(LogRecord record) {
        if ("true".equals(record.get("_peek"))) return;

        String message = nvl(record.fields().get("message"));
        String thread  = nvl(record.fields().get("thread"));
        String method  = nvl(record.fields().get("メソッド"));

        if (message.contains(START_KEYWORD)) {
            String key = thread + ":" + method;
            pending.put(key, new PendingCall(
                    nvl(record.fields().get("行名")),
                    method,
                    nvl(record.fields().get("引数")),
                    nvl(record.fields().get("timestamp"))
            ));

        } else if (message.contains(END_KEYWORD)) {
            String key  = thread + ":" + method;
            PendingCall call = pending.remove(key);

            // 戻り値: シンプル値とオブジェクトフィールドを両方収集
            String simpleRet = nvl(record.fields().get(RET_FIELD));
            Map<String, String> retFields = collectReturnValueFields(record.fields());

            String rowName = call != null ? call.rowName : "—";
            String args    = call != null ? call.args    : "—";
            String ts      = call != null ? call.ts      : nvl(record.fields().get("timestamp"));

            String rowHtml = buildRow(ts, rowName, method, args, simpleRet, retFields);
            SwingUtilities.invokeLater(() -> appendRow(rowHtml));
        }
    }

    @Override
    public void onEndOfStream() {
        if (pending.isEmpty()) return;
        for (PendingCall call : pending.values()) {
            String rowHtml = buildRow(call.ts, call.rowName, call.method,
                                      call.args, "（未完了）", Map.of());
            SwingUtilities.invokeLater(() -> appendRow(rowHtml));
        }
        pending.clear();
    }

    // ── 戻り値フィールド収集 ──────────────────────────────────────────────────

    /** "戻り値." で始まるフィールドを収集してプレフィックスを除いたマップにして返す */
    private Map<String, String> collectReturnValueFields(Map<String, String> fields) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getKey().startsWith(RET_PREFIX)) {
                result.put(e.getKey().substring(RET_PREFIX.length()), e.getValue());
            }
        }
        return result;
    }

    // ── HTML 構築 ─────────────────────────────────────────────────────────────

    private String buildInitialHtml() {
        return "<html><body style='margin:4px;'>"
            + "<table border='1' cellpadding='5' cellspacing='0'"
            + " style='border-collapse:collapse;width:100%;"
            + "font-family:monospace;font-size:12px;'>"
            + "<tr style='background:#d0d0d0;'>"
            + "<th style='white-space:nowrap;'>日時</th>"
            + "<th>行名</th>"
            + "<th>メソッド</th>"
            + "<th>引数</th>"
            + "<th>戻り値</th>"
            + "</tr>"
            + "</table>"
            + "</body></html>";
    }

    private String buildRow(String ts, String rowName, String method, String args,
                            String simpleRet, Map<String, String> retFields) {
        // 戻り値セルの HTML を生成
        String retCellHtml;
        String allRetText; // 色判定用の全テキスト

        if (!retFields.isEmpty()) {
            // オブジェクト: "key: value" を改行区切りで表示
            StringBuilder sb = new StringBuilder();
            StringBuilder plain = new StringBuilder();
            for (Map.Entry<String, String> e : retFields.entrySet()) {
                if (sb.length() > 0) { sb.append("<br>"); plain.append(" "); }
                sb.append("<span style='color:#888;'>").append(esc(e.getKey())).append(":</span> ")
                  .append(esc(e.getValue()));
                plain.append(e.getValue());
            }
            retCellHtml = sb.toString();
            allRetText  = plain.toString();
        } else {
            // シンプル値
            retCellHtml = esc(simpleRet);
            allRetText  = simpleRet;
        }

        // 色判定
        String retBg;
        String retColor;
        String upperAll = allRetText.toUpperCase();
        if (upperAll.contains("SUCCESS") || upperAll.contains("OK")
                || upperAll.contains("ALLOW")) {
            retBg    = "#90EE90";
            retColor = "#1a6b1a";
        } else if (upperAll.contains("ERROR") || upperAll.contains("NG")
                || upperAll.contains("FAIL") || upperAll.contains("例外")
                || upperAll.contains("DENY")) {
            retBg    = "#FF6666";
            retColor = "#8b0000";
        } else if (allRetText.contains("未完了")) {
            retBg    = "#FFF3CD";
            retColor = "#856404";
        } else {
            retBg    = "#ffffff";
            retColor = "#333333";
        }

        return "<tr>"
            + "<td style='white-space:nowrap;color:#555;'>" + esc(ts) + "</td>"
            + "<td style='white-space:nowrap;'>" + esc(rowName) + "</td>"
            + "<td style='white-space:nowrap;color:#336;'>" + esc(method) + "</td>"
            + "<td style='color:#444;'>" + esc(args) + "</td>"
            + "<td style='background:" + retBg + ";color:" + retColor + ";"
            +     "font-weight:bold;vertical-align:top;'>"
            +     retCellHtml + "</td>"
            + "</tr>";
    }

    // ── DOM 操作 ──────────────────────────────────────────────────────────────

    private void appendRow(String rowHtml) {
        try {
            HTMLDocument doc = (HTMLDocument) pane.getDocument();
            if (tableElement == null) {
                tableElement = doc.getElement(
                        doc.getDefaultRootElement(),
                        StyleConstants.NameAttribute, HTML.Tag.TABLE);
            }
            if (tableElement == null) return;

            JScrollBar vsb   = scroll.getVerticalScrollBar();
            int savedValue   = vsb.getValue();
            boolean atBottom = savedValue >= vsb.getMaximum() - vsb.getVisibleAmount() - 5;

            doc.insertBeforeEnd(tableElement, rowHtml);

            SwingUtilities.invokeLater(() -> {
                if (atBottom) {
                    vsb.setValue(vsb.getMaximum());
                } else {
                    vsb.setValue(savedValue);
                }
            });
        } catch (Exception ignored) {}
    }

    // ── 内部データクラス ──────────────────────────────────────────────────────

    private static class PendingCall {
        final String rowName;
        final String method;
        final String args;
        final String ts;

        PendingCall(String rowName, String method, String args, String ts) {
            this.rowName = rowName;
            this.method  = method;
            this.args    = args;
            this.ts      = ts;
        }
    }

    // ── ユーティリティ ─────────────────────────────────────────────────────────

    private String esc(String s) {
        if (s == null || s.isEmpty()) return "&nbsp;";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
