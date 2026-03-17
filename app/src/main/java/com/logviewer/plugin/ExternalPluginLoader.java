package com.logviewer.plugin;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Scans ./plugins/*.jar and loads Plugin implementations via ServiceLoader.
 *
 * Each JAR must contain:
 *   META-INF/services/com.logviewer.plugin.Plugin
 * listing the fully-qualified implementation class name(s).
 *
 * The URLClassLoader is cached so JARs are loaded once per JVM run.
 * ServiceLoader is iterated fresh on each call to loadPlugins(),
 * yielding new instances every time (one per source).
 */
public class ExternalPluginLoader {

    private static volatile URLClassLoader cachedLoader;

    private static URLClassLoader buildLoader() {
        File pluginsDir = new File("plugins");
        if (!pluginsDir.isDirectory()) return null;

        File[] jars = pluginsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) return null;

        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            try {
                urls.add(jar.toURI().toURL());
                System.out.println("[ExternalPluginLoader] Found JAR: " + jar.getName());
            } catch (Exception e) {
                System.err.println("[ExternalPluginLoader] Cannot read JAR: " + jar + " - " + e.getMessage());
            }
        }
        if (urls.isEmpty()) return null;

        return new URLClassLoader(urls.toArray(new URL[0]),
                ExternalPluginLoader.class.getClassLoader());
    }

    private static URLClassLoader getLoader() {
        if (cachedLoader == null) {
            synchronized (ExternalPluginLoader.class) {
                if (cachedLoader == null) {
                    cachedLoader = buildLoader();
                }
            }
        }
        return cachedLoader;
    }

    /**
     * Returns freshly instantiated Plugin objects from all external JARs.
     * Safe to call multiple times — creates new instances each call.
     */
    public static List<Plugin> loadPlugins() {
        List<Plugin> result = new ArrayList<>();
        URLClassLoader loader = getLoader();
        if (loader == null) return result;

        ServiceLoader<Plugin> sl = ServiceLoader.load(Plugin.class, loader);
        for (Plugin p : sl) {
            result.add(p);
            System.out.println("[ExternalPluginLoader] Loaded plugin: " + p.id()
                    + " (" + p.getClass().getName() + ")");
        }
        return result;
    }
}
