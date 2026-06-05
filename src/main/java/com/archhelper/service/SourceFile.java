package com.archhelper.service;

import java.util.List;

/**
 * A single text file from the cloned repo, with its content already loaded and
 * split into lines so analyzers can report file:line locations cheaply.
 */
public record SourceFile(
        String relativePath,
        String fileName,
        String extension,
        String content,
        List<String> lines
) {
    public boolean hasExtension(String... exts) {
        for (String e : exts) {
            if (e.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }
}
