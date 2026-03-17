package com.logviewer.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Persistent application settings stored in ~/.logviewer/config.properties.
 *
 * Managed settings:
 *   - enabled plugins (by plugin ID)
 *   - recently opened files (path + tail mode flags)
 */
public class AppConfig {

    public record RecentFile(Path path, boolean tail, boolean tailOnly) {}

    private static final Path CONFIG_DIR  = Path.of(System.getProperty("user.home"), ".logviewer");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    private static final int  MAX_RECENT  = 10;

    public static final AppConfig INSTANCE = new AppConfig();

    private final Properties props = new Properties();

    private AppConfig() { load(); }

    // ── Enabled plugins ───────────────────────────────────────────────────────

    /**
     * Returns the set of enabled plugin IDs, or null if never configured
     * (meaning "all plugins enabled by default").
     */
    public Set<String> getEnabledPlugins() {
        String val = props.getProperty("enabled.plugins");
        if (val == null) return null;
        if (val.isBlank()) return Set.of();
        Set<String> set = new LinkedHashSet<>();
        for (String id : val.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    public void setEnabledPlugins(Collection<String> ids) {
        props.setProperty("enabled.plugins", String.join(",", ids));
        save();
    }

    // ── Recent files ──────────────────────────────────────────────────────────

    public List<RecentFile> getRecentFiles() {
        int count = Integer.parseInt(props.getProperty("recent.count", "0"));
        List<RecentFile> list = new ArrayList<>();
        for (int i = 0; i < count && i < MAX_RECENT; i++) {
            String pathStr = props.getProperty("recent." + i + ".path");
            if (pathStr == null) continue;
            boolean tail     = "true".equals(props.getProperty("recent." + i + ".tail",     "false"));
            boolean tailOnly = "true".equals(props.getProperty("recent." + i + ".tailOnly", "false"));
            try {
                list.add(new RecentFile(Path.of(pathStr), tail, tailOnly));
            } catch (Exception ignored) {}
        }
        return list;
    }

    public void addRecentFile(Path path, boolean tail, boolean tailOnly) {
        List<RecentFile> list = new ArrayList<>(getRecentFiles());
        list.removeIf(r -> r.path().equals(path));
        list.add(0, new RecentFile(path, tail, tailOnly));
        if (list.size() > MAX_RECENT) list = list.subList(0, MAX_RECENT);

        // Clear old indexed entries
        for (int i = 0; i < MAX_RECENT; i++) {
            props.remove("recent." + i + ".path");
            props.remove("recent." + i + ".tail");
            props.remove("recent." + i + ".tailOnly");
        }
        props.setProperty("recent.count", String.valueOf(list.size()));
        for (int i = 0; i < list.size(); i++) {
            RecentFile r = list.get(i);
            props.setProperty("recent." + i + ".path",     r.path().toAbsolutePath().toString());
            props.setProperty("recent." + i + ".tail",     String.valueOf(r.tail()));
            props.setProperty("recent." + i + ".tailOnly", String.valueOf(r.tailOnly()));
        }
        save();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(CONFIG_FILE)) return;
        try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
            props.load(r);
        } catch (IOException ignored) {}
    }

    private void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
                props.store(w, "LogViewer configuration");
            }
        } catch (IOException ignored) {}
    }
}
