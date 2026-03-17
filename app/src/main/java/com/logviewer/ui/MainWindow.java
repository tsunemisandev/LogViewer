package com.logviewer.ui;

import com.logviewer.config.AppConfig;
import com.logviewer.pipeline.LogPipeline;
import com.logviewer.plugin.ExternalPluginLoader;
import com.logviewer.plugin.Plugin;
import com.logviewer.plugin.RawStreamPlugin;
import com.logviewer.source.FileLogSource;
import com.logviewer.source.LogSource;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MainWindow extends JFrame {

    /** Lightweight descriptor used for the Plugins menu (no UI components held). */
    private record PluginMeta(String id, String label, int priority) {}

    private final DefaultListModel<String> sourceListModel = new DefaultListModel<>();
    private final JList<String>            sourceList      = new JList<>(sourceListModel);
    private final JTabbedPane              tabbedPane      = new JTabbedPane();
    private final JLabel                   statusBar       = new JLabel(" Ready");

    // sourceName → SourceEntry (pipeline + plugins)
    private final Map<String, SourceEntry> sources = new LinkedHashMap<>();

    // Available plugins (discovered once at startup for menu building)
    private final List<PluginMeta>                   availablePlugins = new ArrayList<>();
    private final Map<String, JCheckBoxMenuItem>     pluginCheckboxes = new LinkedHashMap<>();

    // Recent files submenu (rebuilt each time a file is opened)
    private final JMenu recentMenu = new JMenu("最近開いたファイル");

    public MainWindow() {
        super("Log Viewer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);

        discoverAvailablePlugins();
        buildMenuBar();
        buildUI();
        refreshRecentFilesMenu();
    }

    // ── Plugin discovery ──────────────────────────────────────────────────────

    /**
     * Loads plugin JARs once at startup to populate the Plugins menu.
     * These temporary instances are used only for id/label/priority metadata.
     */
    private void discoverAvailablePlugins() {
        availablePlugins.add(new PluginMeta("raw", "Raw", 0));
        for (Plugin p : ExternalPluginLoader.loadPlugins()) {
            availablePlugins.add(new PluginMeta(p.id(), p.tabLabel(), p.priority()));
        }
        availablePlugins.sort(Comparator.comparingInt(PluginMeta::priority));
    }

    // ── Menu bar ──────────────────────────────────────────────────────────────

    private void buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ── File ──────────────────────────────────────────────────────────────
        JMenu fileMenu = new JMenu("File");

        JMenuItem openItem = new JMenuItem("Open File...");
        openItem.addActionListener(e -> openFile(false));
        fileMenu.add(openItem);

        JMenuItem openTailItem = new JMenuItem("Open File (Live Tail)...");
        openTailItem.addActionListener(e -> openFile(true));
        fileMenu.add(openTailItem);

        fileMenu.addSeparator();
        fileMenu.add(recentMenu);
        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        // ── Plugins ───────────────────────────────────────────────────────────
        JMenu pluginsMenu = new JMenu("Plugins");
        Set<String> savedEnabled = AppConfig.INSTANCE.getEnabledPlugins();

        for (PluginMeta meta : availablePlugins) {
            // null means first run → all enabled by default
            boolean enabled = savedEnabled == null || savedEnabled.contains(meta.id());
            JCheckBoxMenuItem cb = new JCheckBoxMenuItem(meta.label(), enabled);
            cb.addActionListener(e -> savePluginSelection());
            pluginCheckboxes.put(meta.id(), cb);
            pluginsMenu.add(cb);
        }

        menuBar.add(pluginsMenu);
        setJMenuBar(menuBar);
    }

    private void savePluginSelection() {
        Set<String> enabled = new LinkedHashSet<>();
        pluginCheckboxes.forEach((id, cb) -> { if (cb.isSelected()) enabled.add(id); });
        AppConfig.INSTANCE.setEnabledPlugins(enabled);
    }

    // ── Recent files ──────────────────────────────────────────────────────────

    private void refreshRecentFilesMenu() {
        recentMenu.removeAll();
        List<AppConfig.RecentFile> recents = AppConfig.INSTANCE.getRecentFiles();
        if (recents.isEmpty()) {
            JMenuItem none = new JMenuItem("(なし)");
            none.setEnabled(false);
            recentMenu.add(none);
        } else {
            for (AppConfig.RecentFile rf : recents) {
                String suffix = rf.tail() ? " [tail]" : "";
                JMenuItem item = new JMenuItem(rf.path().getFileName() + suffix);
                item.setToolTipText(rf.path().toString());
                item.addActionListener(e -> openRecentFile(rf));
                recentMenu.add(item);
            }
        }
    }

    private void openRecentFile(AppConfig.RecentFile rf) {
        if (rf.tail()) {
            Boolean tailOnly = askTailOnly();
            if (tailOnly == null) return; // cancelled
            addSource(new FileLogSource(rf.path(), true, tailOnly, 200));
        } else {
            addSource(new FileLogSource(rf.path(), false, false, 200));
        }
    }

    // ── UI layout ─────────────────────────────────────────────────────────────

    private void buildUI() {
        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setFixedCellWidth(180);
        sourceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSourceTabs(sourceList.getSelectedValue());
        });

        JScrollPane sourceScroll = new JScrollPane(sourceList);
        sourceScroll.setBorder(BorderFactory.createTitledBorder("Sources"));
        sourceScroll.setPreferredSize(new Dimension(200, 0));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourceScroll, tabbedPane);
        split.setDividerLocation(200);
        split.setDividerSize(4);
        split.setContinuousLayout(true);

        statusBar.setBorder(BorderFactory.createEtchedBorder());
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusBar, BorderLayout.WEST);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);
    }

    // ── File open ─────────────────────────────────────────────────────────────

    private void openFile(boolean tail) {
        JFileChooser chooser = new JFileChooser(".");
        chooser.setDialogTitle(tail ? "Open File (Live Tail)" : "Open File");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        boolean tailOnly = false;
        if (tail) {
            Boolean result = askTailOnly();
            if (result == null) return; // cancelled
            tailOnly = result;
        }

        addSource(new FileLogSource(chooser.getSelectedFile().toPath(), tail, tailOnly, 200));
    }

    /**
     * Shows the live tail start-position dialog.
     * Returns true = tail-only, false = read from beginning, null = cancelled.
     */
    private Boolean askTailOnly() {
        String[] options = {"先頭から読む＋監視", "末尾から監視のみ", "キャンセル"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "ライブ監視の開始位置を選択してください",
                "ライブ監視オプション",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 2 || choice < 0) return null;
        return choice == 1;
    }

    // ── Source management ─────────────────────────────────────────────────────

    public void addSource(LogSource source) {
        String name = source.name();

        // Already open — just select it
        if (sources.containsKey(name)) {
            sourceList.setSelectedValue(name, true);
            return;
        }

        // Build plugin list filtered by current Plugins menu selection
        Set<String> enabledIds = resolveEnabledPluginIds();
        List<Plugin> plugins = new ArrayList<>();
        if (enabledIds.contains("raw")) {
            plugins.add(new RawStreamPlugin());
        }
        for (Plugin p : ExternalPluginLoader.loadPlugins()) {
            if (enabledIds.contains(p.id())) {
                plugins.add(p);
            }
        }
        plugins.sort(Comparator.comparingInt(Plugin::priority));

        Map<String, Object> context = Map.of(
                "addSource", (Consumer<LogSource>) this::addSource
        );
        plugins.forEach(p -> p.init(context));

        LogPipeline pipeline = new LogPipeline(source, plugins,
                text -> SwingUtilities.invokeLater(() -> setStatus(name + " | " + text)));

        sources.put(name, new SourceEntry(pipeline, plugins));

        String suffix = "";
        if (source instanceof FileLogSource fls && fls.isTail()) {
            suffix = fls.isTailOnly() ? " [監視のみ]" : " [tail]";
        }
        String label = name + suffix;
        sourceListModel.addElement(label);

        pipeline.start();
        sourceList.setSelectedValue(label, true);

        // Persist to recent files
        if (source instanceof FileLogSource fls) {
            AppConfig.INSTANCE.addRecentFile(fls.path(), fls.isTail(), fls.isTailOnly());
            SwingUtilities.invokeLater(this::refreshRecentFilesMenu);
        }
    }

    /**
     * Returns the set of currently enabled plugin IDs.
     * Falls back to "all available" if the config has never been saved.
     */
    private Set<String> resolveEnabledPluginIds() {
        Set<String> configured = AppConfig.INSTANCE.getEnabledPlugins();
        if (configured != null) return configured;
        // First run: treat all as enabled
        return availablePlugins.stream()
                .map(PluginMeta::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void showSourceTabs(String label) {
        if (label == null) return;
        // Strip display suffix (" [tail]", " [監視のみ]", etc.)
        int suffixIdx = label.lastIndexOf(" [");
        String name = suffixIdx >= 0 ? label.substring(0, suffixIdx) : label;
        SourceEntry entry = sources.get(name);
        if (entry == null) return;

        tabbedPane.removeAll();
        for (Plugin plugin : entry.plugins()) {
            JComponent comp = plugin.getComponent();
            if (comp != null) {
                tabbedPane.addTab(plugin.tabLabel(), comp);
            }
        }
    }

    public void setStatus(String text) {
        statusBar.setText(" " + text);
    }

    private record SourceEntry(LogPipeline pipeline, List<Plugin> plugins) {}
}
