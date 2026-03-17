package com.logviewer;

import com.logviewer.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class AppMain {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            applyLookAndFeel();
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }

    private static void applyLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // fallback to default Metal LaF
        }
    }
}
