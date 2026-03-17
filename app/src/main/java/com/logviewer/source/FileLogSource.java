package com.logviewer.source;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class FileLogSource implements LogSource {

    private final Path path;
    private final boolean tail;
    private final boolean tailOnly;
    private final long pollIntervalMs;

    public FileLogSource(Path path) {
        this(path, false, false, 500);
    }

    public FileLogSource(Path path, boolean tail, long pollIntervalMs) {
        this(path, tail, false, pollIntervalMs);
    }

    /**
     * @param tailOnly when true, seek to end of file before reading (skip existing content)
     */
    public FileLogSource(Path path, boolean tail, boolean tailOnly, long pollIntervalMs) {
        this.path = path;
        this.tail = tail;
        this.tailOnly = tailOnly;
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public InputStream open() throws IOException {
        return new FileInputStream(path.toFile());
    }

    @Override
    public String name() {
        return path.getFileName().toString();
    }

    public Path path()            { return path; }
    public boolean isTail()       { return tail; }
    public boolean isTailOnly()   { return tailOnly; }
    public long pollIntervalMs()  { return pollIntervalMs; }
}
