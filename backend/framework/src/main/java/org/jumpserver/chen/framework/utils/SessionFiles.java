package org.jumpserver.chen.framework.utils;

import java.io.IOException;
import java.nio.file.*;

public final class SessionFiles {
    private SessionFiles() {}

    public static Path uploadedFile(Path root, String filename) throws IOException {
        if (filename == null || filename.isEmpty() || filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.indexOf('\0') >= 0) {
            throw new IOException("Invalid uploaded file name");
        }
        Path realRoot = root.toRealPath();
        Path file = realRoot.resolve(filename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(realRoot)) {
            throw new IOException("Uploaded file is not a regular session file");
        }
        return file;
    }
}
