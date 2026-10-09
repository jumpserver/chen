package org.jumpserver.chen.framework.utils;

import java.nio.file.*;
import java.io.IOException;
import java.util.List;

public class SessionFilesSecurityCheck {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("session-file-check");
        try {
            Path root = Files.createDirectory(temp.resolve("session"));
            Path outside = Files.writeString(temp.resolve("outside.sql"), "select 1");
            Path good = Files.writeString(root.resolve("sql_123.sql"), "select 2");
            Files.createSymbolicLink(root.resolve("linked.sql"), outside);
            if (!SessionFiles.uploadedFile(root, "sql_123.sql").equals(good.toRealPath())) throw new AssertionError();
            for (String name : List.of("../outside.sql", outside.toString(), "a/b", "a\\b", "linked.sql", ".", "..")) {
                try { SessionFiles.uploadedFile(root, name); throw new AssertionError("accepted " + name); }
                catch (IOException expected) {}
            }
            if (!Files.exists(outside)) throw new AssertionError("outside file changed");
            System.out.println("Session uploaded-file boundary controls passed");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
