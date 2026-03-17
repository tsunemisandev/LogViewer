package com.logviewer.plugin.extractor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts slash-delimited key/value pairs.
 * Example: "id/name:10/john" → id=10, name=john
 */
public class SlashPairExtractor implements FieldExtractor {

    private static final Pattern PATTERN =
            Pattern.compile("^([^:/\\s][^:]*):([^:\\s].*)$");

    @Override
    public void extract(String line, Map<String, String> fields) {
        Matcher m = PATTERN.matcher(line.strip());
        if (!m.matches()) return;

        String[] keys = m.group(1).split("/");
        String[] vals = m.group(2).split("/");

        if (keys.length < 2 || keys.length != vals.length) return;

        for (int i = 0; i < keys.length; i++) {
            String k = keys[i].strip();
            String v = vals[i].strip();
            if (!k.isEmpty()) fields.put(k, v);
        }
    }
}
