package com.logviewer.source;

import java.io.IOException;
import java.io.InputStream;

public interface LogSource {
    InputStream open() throws IOException;
    String name();
}
