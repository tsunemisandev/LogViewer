package com.logviewer.plugin;

import com.logviewer.model.LogEvent;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.awt.Font;

public class RawStreamPlugin implements Plugin {

    /** 保持する最大文字数（約 2MB）。超えたら先頭から同量を削除 */
    private static final int MAX_CHARS  = 2 * 1024 * 1024;
    private static final int TRIM_CHARS = MAX_CHARS / 2;

    private final JTextArea   area   = new JTextArea();
    private final JScrollPane scroll = new JScrollPane(area);

    public RawStreamPlugin() {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    }

    @Override public String id()       { return "raw"; }
    @Override public String tabLabel() { return "Raw"; }
    @Override public void onEndOfStream() {}
    @Override public JComponent getComponent() { return scroll; }

    @Override
    public void onEvent(LogEvent event) {
        SwingUtilities.invokeLater(() -> {
            area.append(event.rawLine() + "\n");
            trimIfNeeded();
        });
    }

    /**
     * ドキュメントが MAX_CHARS を超えたら先頭から TRIM_CHARS 分削除する。
     * JTextArea の PlainDocument.remove() は O(n) だが、
     * テキストが多くなりすぎる前に定期的に刈り取ることで実用的な速度を維持する。
     */
    private void trimIfNeeded() {
        Document doc = area.getDocument();
        int len = doc.getLength();
        if (len <= MAX_CHARS) return;
        try {
            // 行の途中で切らないよう改行位置まで進める
            int trimTo = TRIM_CHARS;
            String text = doc.getText(TRIM_CHARS, Math.min(200, len - TRIM_CHARS));
            int nl = text.indexOf('\n');
            if (nl >= 0) trimTo += nl + 1;
            doc.remove(0, trimTo);
        } catch (Exception ignored) {}
    }
}
