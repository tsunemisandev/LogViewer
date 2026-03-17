package com.logviewer.plugin;

import com.logviewer.model.LogEvent;
import com.logviewer.model.LogRecord;

import javax.swing.JComponent;
import java.util.Map;

public interface Plugin {
    String id();
    String tabLabel();
    void onEvent(LogEvent event);
    default void onRecord(LogRecord record) {}
    void onEndOfStream();
    JComponent getComponent(); // null for headless plugins

    /**
     * Called once after construction, before the pipeline starts.
     * Plugins can retrieve injected services from the context map.
     * Known keys:
     *   "addSource" — Consumer&lt;LogSource&gt; to open a new source tab (used by RecorderPlugin)
     */
    default void init(Map<String, Object> context) {}

    /**
     * Lower values run first. Headless enrichment plugins use negative values
     * so they execute before render plugins receive onRecord().
     */
    default int priority() { return 0; }
}
