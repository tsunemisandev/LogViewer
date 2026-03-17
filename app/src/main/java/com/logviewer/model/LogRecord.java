package com.logviewer.model;

import java.util.List;
import java.util.Map;

public record LogRecord(
        List<String> rawLines,
        Map<String, String> fields) {

    public String get(String key)  { return fields.get(key); }
    public String timestamp()      { return fields.get("timestamp"); }
    public String level()          { return fields.get("level"); }
    public String thread()         { return fields.get("thread"); }
    public String message()        { return fields.get("message"); }
}
