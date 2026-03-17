package com.logviewer.plugin.extractor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts key/value from lines using half-width (:) or full-width (：) colon.
 * Example: "チェック呼び出し結果：OK" → チェック呼び出し結果=OK
 *
 * Skips lines that look like slash-pair patterns (handled by SlashPairExtractor).
 */
public class ColonExtractor implements FieldExtractor {

    private static final Pattern PATTERN =
            Pattern.compile("^([^:：/\\d][^:：]*)[:：](.+)$");

    @Override
    public void extract(String line, Map<String, String> fields) {
        String stripped = line.strip();

        if (stripped.contains("/") && stripped.indexOf('/') < stripped.indexOf(':')) return;

        Matcher m = PATTERN.matcher(stripped);
        if (!m.matches()) return;

        String key   = m.group(1).strip();
        String value = m.group(2).strip();
        if (!key.isEmpty()) fields.put(key, value);
    }
}
