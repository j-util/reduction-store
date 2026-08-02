package io.github.jutil.reductionstore.processor;

import io.github.jutil.reductionstore.Reduction;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class CompilerTestSupport {

    private CompilerTestSupport() {
    }

    static Map<String, String> sources(String... namesAndSources) {
        if (namesAndSources.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Sources require alternating names and contents");
        }
        Map<String, String> sources = new LinkedHashMap<String, String>();
        for (int index = 0; index < namesAndSources.length; index += 2) {
            sources.put(namesAndSources[index], namesAndSources[index + 1]);
        }
        return sources;
    }

    static String lines(String... lines) {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            source.append(line).append('\n');
        }
        return source.toString();
    }

    static Compilation compile(
            Path workingDirectory, Map<String, String> sources)
            throws IOException {
        Path sourceDirectory = workingDirectory.resolve("sources");
        Path classDirectory = workingDirectory.resolve("classes");
        Path generatedSourceDirectory = workingDirectory.resolve("generated");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classDirectory);
        Files.createDirectories(generatedSourceDirectory);

        List<File> sourceFiles = new ArrayList<File>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path sourceFile = sourceDirectory.resolve(
                    source.getKey().replace('.', File.separatorChar) + ".java");
            Files.createDirectories(sourceFile.getParent());
            Files.write(
                    sourceFile,
                    source.getValue().getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(sourceFile.toFile());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "Processor tests require a JDK with javac available");
        }

        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();
        boolean succeeded;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(
                        diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<String>(Arrays.asList(
                    "-classpath", classPathEntry(Reduction.class),
                    "-processorpath", classPathEntry(ReductionProcessor.class),
                    "-d", classDirectory.toString(),
                    "-s", generatedSourceDirectory.toString()));
            if (compiler.isSupportedOption("--release") >= 0) {
                options.add("--release");
                options.add("8");
            } else {
                options.add("-source");
                options.add("8");
                options.add("-target");
                options.add("8");
            }
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            succeeded = Boolean.TRUE.equals(compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits).call());
        }
        return new Compilation(
                succeeded,
                diagnostics.getDiagnostics(),
                classDirectory,
                generatedSourceDirectory);
    }

    private static String classPathEntry(Class<?> type) {
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        try {
            return Paths.get(location.toURI()).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    "Cannot resolve class path entry for " + type.getName(),
                    exception);
        }
    }

    static final class Compilation {
        private final boolean succeeded;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
        private final Path classDirectory;
        private final Path generatedSourceDirectory;

        private Compilation(
                boolean succeeded,
                List<Diagnostic<? extends JavaFileObject>> diagnostics,
                Path classDirectory,
                Path generatedSourceDirectory) {
            this.succeeded = succeeded;
            this.diagnostics = diagnostics;
            this.classDirectory = classDirectory;
            this.generatedSourceDirectory = generatedSourceDirectory;
        }

        boolean succeeded() {
            return succeeded;
        }

        String diagnostics() {
            StringBuilder message = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (message.length() != 0) {
                    message.append('\n');
                }
                if (diagnostic.getSource() != null) {
                    message.append(diagnostic.getSource().getName());
                    if (diagnostic.getLineNumber() >= 0) {
                        message.append(':').append(diagnostic.getLineNumber());
                    }
                    message.append(": ");
                }
                message.append(diagnostic.getKind()).append(": ")
                        .append(diagnostic.getMessage(Locale.ROOT));
            }
            return message.toString();
        }

        String generatedSource(String qualifiedName) throws IOException {
            Path sourceFile = generatedSourceDirectory.resolve(
                    qualifiedName.replace('.', File.separatorChar) + ".java");
            return new String(
                    Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        }

        List<Path> generatedJavaFiles() throws IOException {
            if (!Files.exists(generatedSourceDirectory)) {
                return Collections.emptyList();
            }
            List<Path> files = new ArrayList<Path>();
            try (Stream<Path> paths = Files.walk(generatedSourceDirectory)) {
                paths.filter(path -> path.toString().endsWith(".java"))
                        .forEach(files::add);
            }
            Collections.sort(files, new Comparator<Path>() {
                @Override
                public int compare(Path left, Path right) {
                    return left.toString().compareTo(right.toString());
                }
            });
            return files;
        }

        URLClassLoader newClassLoader() throws IOException {
            return new URLClassLoader(
                    new URL[]{classDirectory.toUri().toURL()},
                    Reduction.class.getClassLoader());
        }
    }
}
