package com.evolution.benchmark.frontend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class FrontendSupport {
    private FrontendSupport() { }

    static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot enumerate Java files", exception);
        }
    }

    static TargetOrigin origin(String qualifiedName) {
        if (qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.")) return TargetOrigin.JDK;
        return TargetOrigin.DEPENDENCY;
    }

    static Span uniqueTextSpan(String source, String text) {
        int start = source.indexOf(text);
        if (start < 0 || source.indexOf(text, start + 1) >= 0) {
            throw new IllegalArgumentException("lossless text cannot be uniquely located without parser offsets: " + text);
        }
        int startLine = 1;
        int startColumn = 1;
        for (int i = 0; i < start; i++) {
            if (source.charAt(i) == '\n') { startLine++; startColumn = 1; } else { startColumn++; }
        }
        int end = start + text.length();
        int endLine = startLine;
        int endColumn = startColumn;
        for (int i = start; i < end; i++) {
            if (source.charAt(i) == '\n') { endLine++; endColumn = 1; } else { endColumn++; }
        }
        return new Span(startLine, startColumn, endLine, endColumn);
    }
}
