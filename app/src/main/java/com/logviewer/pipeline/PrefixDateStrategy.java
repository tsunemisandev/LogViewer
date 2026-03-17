package com.logviewer.pipeline;

import java.util.regex.Pattern;

public class PrefixDateStrategy implements LineAssemblerStrategy {

    private static final Pattern TIMESTAMP =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    @Override
    public boolean isNewRecord(String line, String previousLine) {
        return TIMESTAMP.matcher(line).find();
    }
}
