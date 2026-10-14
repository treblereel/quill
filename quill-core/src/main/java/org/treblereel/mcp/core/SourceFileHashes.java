package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Content evidence for source coordinates; timestamps cannot establish source identity. */
public final class SourceFileHashes {
    private SourceFileHashes() {}
    public static Map<String,String> captureJava(Path project,List<Path> roots) {return captureJava(project,roots,false);}
    /** Live enumeration with guarded content reuse; unsupported volumes retain full SHA checks. */
    public static Map<String,String> captureJavaCached(Path project,List<Path> roots) {return captureJava(project,roots,true);}
    private static Map<String, String> captureJava(Path project, List<Path> roots,boolean reuseContent) {
        Path root = project.toAbsolutePath().normalize();
        Map<String, String> hashes = new TreeMap<>();
        try {
            Path realRoot = root.toRealPath();
            var volume=SourceContentCache.volume(realRoot);
            for (Path sourceRoot : roots) {
                if (!Files.isDirectory(sourceRoot)) continue;
                Files.walkFileTree(sourceRoot,new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                        if(!file.getFileName().toString().endsWith(".java") || (!attributes.isRegularFile() && !Files.isRegularFile(file)))
                            return java.nio.file.FileVisitResult.CONTINUE;
                        Path normalized=file.toAbsolutePath().normalize();
                        if(!normalized.startsWith(root))return java.nio.file.FileVisitResult.CONTINUE;
                        Path real=normalized.toRealPath();
                        if(real.startsWith(realRoot))hashes.put(root.relativize(normalized).toString().replace('\\','/'),
                                reuseContent?SourceContentCache.hash(real,volume):hash(real));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
            return java.util.Collections.unmodifiableMap(hashes);
        } catch (IOException unavailable) { throw new IllegalStateException("Could not capture Java source evidence", unavailable); }
    }
    public static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file);
                 var sink = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
                org.treblereel.mcp.db.QueryMetrics.sourceRead(input.transferTo(sink));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
