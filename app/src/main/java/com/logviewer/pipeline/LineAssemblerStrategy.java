package com.logviewer.pipeline;

public interface LineAssemblerStrategy {
    boolean isNewRecord(String line, String previousLine);
}
