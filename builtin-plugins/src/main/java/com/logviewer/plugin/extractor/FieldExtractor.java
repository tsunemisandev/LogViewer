package com.logviewer.plugin.extractor;

import java.util.Map;

public interface FieldExtractor {
    void extract(String line, Map<String, String> fields);
}
