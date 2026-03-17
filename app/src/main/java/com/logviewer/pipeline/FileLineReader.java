package com.logviewer.pipeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads lines from a file via FileChannel.
 *
 * Non-tail mode: returns null at EOF.
 * Tail mode:     returns null when no new data is available (idle signal),
 *                then the caller should try again after doing any idle work.
 *                Returns null only when pending buffer is empty (no partial line).
 */
public class FileLineReader implements AutoCloseable {

    private final FileChannel channel;
    private final boolean tail;
    private final long pollMs;
    private volatile boolean running = true;

    private final ByteBuffer buf = ByteBuffer.allocate(8192);
    private final StringBuilder pending = new StringBuilder();

    public FileLineReader(Path path, boolean tail, long pollMs) throws IOException {
        this(path, tail, false, pollMs);
    }

    /**
     * @param tailOnly when true, seek to end of file immediately (skip existing content)
     */
    public FileLineReader(Path path, boolean tail, boolean tailOnly, long pollMs) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.tail = tail;
        this.pollMs = pollMs;
        if (tailOnly) {
            channel.position(channel.size());
        }
    }

    /**
     * Returns the next complete line, or null when:
     *   - non-tail: EOF reached
     *   - tail:     no new data right now (idle); caller should call again
     */
    public String readLine() throws IOException, InterruptedException {
        while (running) {
            // serve a complete line from the pending buffer
            int idx = pending.indexOf("\n");
            if (idx >= 0) {
                String line = pending.substring(0, idx);
                pending.delete(0, idx + 1);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }

            // read more bytes from the channel
            buf.clear();
            int read = channel.read(buf);

            if (read > 0) {
                buf.flip();
                pending.append(StandardCharsets.UTF_8.decode(buf));
                // loop — try to extract a line from the enlarged buffer

            } else if (!tail) {
                // non-tail EOF — flush any remaining content
                if (pending.length() > 0) {
                    String line = pending.toString();
                    pending.setLength(0);
                    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                }
                return null; // definitive EOF

            } else {
                // tail idle — wait one poll cycle
                Thread.sleep(pollMs);
                // signal idle only when there is no partial line waiting
                if (pending.isEmpty()) {
                    return null;
                }
                // else loop: try to read the rest of the partial line
            }
        }
        return null;
    }

    public void stop() { running = false; }

    @Override
    public void close() throws IOException {
        running = false;
        channel.close();
    }
}
