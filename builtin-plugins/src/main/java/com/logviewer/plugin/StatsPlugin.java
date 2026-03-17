package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;

public class StatsPlugin implements Plugin {

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final StatsPanel panel = new StatsPanel();

    public StatsPlugin() {
        counts.put("ERROR", 0);
        counts.put("WARN",  0);
        counts.put("INFO",  0);
        counts.put("DEBUG", 0);
    }

    @Override public String id()        { return "stats"; }
    @Override public String tabLabel()  { return "Stats"; }
    @Override public void onEvent(LogEvent event) {}
    @Override public void onEndOfStream() { SwingUtilities.invokeLater(panel::repaint); }
    @Override public JComponent getComponent() { return panel; }

    @Override
    public void onRecord(LogRecord record) {
        // peek はプレビュー用の未確定レコードなのでカウントしない
        if ("true".equals(record.fields().get("_peek"))) return;
        String level = record.fields().getOrDefault("level.normalized", "OTHER");
        synchronized (counts) {
            counts.merge(level, 1, Integer::sum);
        }
        SwingUtilities.invokeLater(panel::repaint);
    }

    private class StatsPanel extends JPanel {

        StatsPanel() {
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Map<String, Integer> snapshot;
            synchronized (counts) {
                snapshot = new LinkedHashMap<>(counts);
            }

            int total    = snapshot.values().stream().mapToInt(Integer::intValue).sum();
            int maxCount = snapshot.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (maxCount == 0) maxCount = 1;

            int panelW   = getWidth();
            int panelH   = getHeight();
            int padTop   = 50;
            int padBot   = 50;
            int padSide  = 40;
            int barAreaH = panelH - padTop - padBot;
            int n        = snapshot.size();
            int availW   = panelW - padSide * 2;
            int barW     = Math.max(20, Math.min(80, availW / n - 20));
            int gap      = (availW - barW * n) / (n + 1);

            g2.setColor(Color.DARK_GRAY);
            g2.setFont(new Font("SansSerif", Font.BOLD, 14));
            g2.drawString("Total: " + total, padSide, 28);

            int x = padSide + gap;
            for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
                String key   = entry.getKey();
                int    count = entry.getValue();
                int    barH  = (int) ((double) count / maxCount * barAreaH);

                Color barColor = switch (key) {
                    case "ERROR" -> new Color(210, 50,  50);
                    case "WARN"  -> new Color(220, 150,  0);
                    case "INFO"  -> new Color( 50, 140, 210);
                    case "DEBUG" -> new Color(150, 150, 150);
                    default      -> new Color(100, 100, 200);
                };

                int barY = padTop + barAreaH - barH;

                g2.setColor(barColor);
                if (barH > 0) g2.fillRect(x, barY, barW, barH);

                g2.setColor(barColor.darker());
                g2.drawRect(x, padTop, barW, barAreaH);

                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                String countStr = String.valueOf(count);
                FontMetrics fm = g2.getFontMetrics();
                int cx = x + (barW - fm.stringWidth(countStr)) / 2;
                g2.drawString(countStr, cx, barY - 5 < padTop - 2 ? padTop - 2 : barY - 5);

                g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
                fm = g2.getFontMetrics();
                int lx = x + (barW - fm.stringWidth(key)) / 2;
                g2.drawString(key, lx, padTop + barAreaH + 18);

                x += barW + gap;
            }

            g2.dispose();
        }
    }
}
