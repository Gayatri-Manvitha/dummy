package com.archhelper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks a cloned repository and loads all relevant text/source files into
 * memory, skipping binaries, build output and version-control metadata.
 */
@Service
public class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".idea",
            ".gradle", "bin", "obj", "venv", ".venv", "__pycache__", ".mvn",
            "vendor", ".next", "coverage"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy",
            "js", "jsx", "ts", "tsx", "py", "rb", "go", "cs", "php",
            "jsp", "jspx", "tag", "tagx", "jspf",
            "xml", "yml", "yaml", "json", "properties", "gradle", "sql",
            "html", "css", "md", "txt", "sh", "dockerfile"
    );

    @Value("${analysis.max-files:20000}")
    private int maxFiles;

    @Value("${analysis.max-file-size-bytes:2000000}")
    private long maxFileSize;

    public List<SourceFile> scan(Path root) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isInSkippedDir(root, p))
                    .forEach(p -> {
                        if (files.size() >= maxFiles) {
                            return;
                        }
                        SourceFile sf = load(root, p);
                        if (sf != null) {
                            files.add(sf);
                        }
                    });
        }
        log.info("Scanned {} text files under {}", files.size(), root);
        return files;
    }

    private boolean isInSkippedDir(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private SourceFile load(Path root, Path file) {
        String name = file.getFileName().toString();
        String ext = extensionOf(name);
        boolean isDockerfile = name.equalsIgnoreCase("Dockerfile");
        if (!isDockerfile && !TEXT_EXTENSIONS.contains(ext)) {
            return null;
        }
        try {
            if (Files.size(file) > maxFileSize) {
                return null;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String rel = root.relativize(file).toString().replace('\\', '/');
            List<String> lines = Arrays.asList(content.split("\n", -1));
            return new SourceFile(rel, name, ext.isEmpty() ? (isDockerfile ? "dockerfile" : "") : ext, content, lines);
        } catch (IOException | RuntimeException e) {
            // Likely a binary file mislabeled, or unreadable encoding: skip it.
            return null;
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }
}
