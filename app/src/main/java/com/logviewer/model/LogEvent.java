package com.logviewer.model;

import java.time.Instant;

public record LogEvent(String rawLine, long offsetBytes, Instant receivedAt) {}
