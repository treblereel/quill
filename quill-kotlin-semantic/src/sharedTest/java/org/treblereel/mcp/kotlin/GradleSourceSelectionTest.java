package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleSourceSelectionTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode selection() { var s = mapper.createObjectNode().put("protocol", 1); s.putArray("entries"); return s; }
    ObjectNode tree(ObjectNode s, String kind, String path) {
        var e = s.withArray("entries").addObject().put("kind", kind).put("root", root.resolve(path).toString());
        e.putArray("gates"); e.putArray("defaultExcludes").add("**/.git/**"); return e;
    }
    ObjectNode gate(ObjectNode e, String include, String exclude) {
        var g = e.withArray("gates").addObject().put("caseSensitive", true);
        var i = g.putArray("includes"); if (include != null) i.add(include);
        var x = g.putArray("excludes"); if (exclude != null) x.add(exclude); return g;
    }
    Path write(String path, String content) throws Exception {
        Path p = root.resolve(path); Files.createDirectories(p.getParent()); Files.writeString(p, content); return p.toRealPath();
    }
    void status(String status, ObjectNode s) {
        var failure = assertThrows(GradleSourceSelection.Invalid.class, () -> GradleSourceSelection.collect(root, s));
        assertEquals(status, failure.status);
    }
    @Test void antMatchesZeroOrManyDirectoriesAndEscapesLiteralRegex() {
        for (String path : List.of("A.kt", "a/A.kt", "a/b/A.kt")) assertTrue(GradleSourceSelection.ant("**/*.kt", path, true));
        assertTrue(GradleSourceSelection.ant("a/**/B?.kt", "a/B1.kt", true));
        assertTrue(GradleSourceSelection.ant("a/", "a/sub/File.kt", true));
        assertTrue(GradleSourceSelection.ant("a\\*.kt", "a/X.kt", true));
        assertTrue(GradleSourceSelection.ant("Ü?.KT", "üx.kt", false));
        assertTrue(GradleSourceSelection.ant("[x].kt", "[x].kt", true));
        assertFalse(GradleSourceSelection.ant("[x].kt", "x.kt", true));
        assertFalse(GradleSourceSelection.ant("*.kt", "a/X.kt", true));
        assertFalse(GradleSourceSelection.ant("A?.kt", "A.kt", true));
        assertFalse(GradleSourceSelection.ant("*.KT", "X.kt", true));
    }
    @Test void intersectionsExclusionsDefaultsAndDuplicatesAreReplayed() throws Exception {
        Path a = write("src/deep/A.kt", "fun a() = 1"); write("src/deep/Ignored.kt", "invalid");
        write("src/Other.kt", "invalid"); write("src/.git/deep/Git.kt", "invalid");
        var s = selection(); var e = tree(s, "kotlin", "src"); gate(e, "**/*.kt", "**/Ignored.kt"); gate(e, "**/deep/**", null);
        s.withArray("entries").add(e.deepCopy());
        assertEquals(List.of(a), GradleSourceSelection.collect(root, s).kotlin());
    }
    @Test void additionsDeletionsAndAbsentDirectoriesDoNotRequireRecapture() throws Exception {
        var s = selection(); gate(tree(s, "kotlin", "new ü"), "**/*.kt", null);
        assertTrue(GradleSourceSelection.collect(root, s).kotlin().isEmpty());
        Path a = write("new ü/A.kt", "fun a() = 1"); assertEquals(List.of(a), GradleSourceSelection.collect(root, s).kotlin());
        Files.delete(a); assertTrue(GradleSourceSelection.collect(root, s).kotlin().isEmpty());
    }
    @Test void javaFiltersAndExplicitFilesRemainIsolated() throws Exception {
        Path java = write("src/Api.java", "class Api {}"); write("src/Excluded.java", "invalid");
        Path kotlin = write("direct ü.kt", "fun a() = 1"); write("unselected.kt", "invalid");
        var s = selection(); gate(tree(s, "java", "src"), "**/*.java", "**/Excluded.java");
        s.withArray("entries").addObject().put("kind", "kotlin").put("file", kotlin.toString());
        var result = GradleSourceSelection.collect(root, s); assertEquals(List.of(java), result.java()); assertEquals(List.of(kotlin), result.kotlin());
        Files.delete(kotlin); assertTrue(GradleSourceSelection.collect(root, s).kotlin().isEmpty());
    }
    @Test void gradleFileTreeMayHaveAnIndividualFileAsItsRoot() throws Exception {
        Path file = write("src/Explicit.kt", "fun explicit() = 1"); write("src/Neighbour.kt", "invalid unrelated Kotlin");
        var s = selection(); var entry = tree(s, "kotlin", "src/Explicit.kt"); var rule = gate(entry, "**/*.kt", null);
        var result = GradleSourceSelection.collect(root, s);
        assertEquals(List.of(file), result.kotlin()); assertEquals(List.of(file.getParent()), result.roots());
        rule.withArray("excludes").add("**/Explicit.kt"); assertTrue(GradleSourceSelection.collect(root, s).kotlin().isEmpty());
        Files.delete(file); assertTrue(GradleSourceSelection.collect(root, s).kotlin().isEmpty());
        write("src/Explicit.kt", "restored"); rule.withArray("excludes").removeAll();
        assertEquals(List.of(file), GradleSourceSelection.collect(root, s).kotlin());
    }
    @Test void predicateSnapshotGuardAlsoCoversAnIndividualFileTreeRoot() throws Exception {
        Path file = write("src/Explicit.kt", "aaaa"); var s = selection().put("snapshot", true);
        gate(tree(s, "kotlin", "src/Explicit.kt"), "**/*.kt", null);
        s.putObject("selected").putArray("kotlin").add(file.toString()); s.withObject("selected").putArray("java");
        s.putArray("guard").addObject().put("path", file.toString()).put("sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        GradleSourceSelection.collect(root, s); Files.writeString(file, "bbbb"); status("source_selection_refresh_required", s);
    }

    @Test void caseInsensitiveIncludesAndExcludesUseSameSetting() throws Exception {
        Path a = write("src/A.kt", "ok"); write("src/Ignored.kt", "invalid");
        var s = selection(); gate(tree(s, "kotlin", "src"), "**/*.KT", "**/IGNORED.KT").put("caseSensitive", false);
        assertEquals(List.of(a), GradleSourceSelection.collect(root, s).kotlin());
    }
    ObjectNode snapshot() throws Exception {
        Path a = write("src/A.kt", "aaaa"), excluded = write("src/Excluded.kt", "invalid");
        var s = selection().put("snapshot", true); gate(tree(s, "kotlin", "src"), "**/*.kt", null);
        var selected = s.putObject("selected"); selected.putArray("kotlin").add(a.toString()); selected.putArray("java");
        var guard = s.putArray("guard");
        for (Path p : List.of(a, excluded)) guard.addObject().put("path", p.toString()).put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        return s;
    }
    @Test void predicatesUseOnlyCapturedFilesAndDetectContentChangesWithRestoredMtime() throws Exception {
        var s = snapshot(); assertEquals(List.of(root.resolve("src/A.kt").toRealPath()), GradleSourceSelection.collect(root, s).kotlin());
        Path p = root.resolve("src/Excluded.kt"); var time = Files.getLastModifiedTime(p); Files.writeString(p, "changed"); Files.setLastModifiedTime(p, time);
        status("source_selection_refresh_required", s);
    }
    @Test void predicateGuardsDetectAddedAndRemovedExcludedFiles() throws Exception {
        var s = snapshot(); Path p = write("src/Future.kt", "invalid"); status("source_selection_refresh_required", s);
        Files.delete(p); GradleSourceSelection.collect(root, s); Files.delete(root.resolve("src/Excluded.kt")); status("source_selection_refresh_required", s);
    }
    @Test void malformedProtocolsPatternsAndAmbiguousPathsFailExplicitly() throws Exception {
        status("source_selection_protocol_unsupported", selection().put("protocol", 2));
        var s = selection(); var e = tree(s, "kotlin", "src"); gate(e, null, null).put("includes", "bad"); status("source_selection_invalid", s);
        s = selection(); tree(s, "kotlin", "src").put("file", root.resolve("A.kt").toString()); status("source_selection_invalid", s);
        s = selection(); tree(s, "scala", "src"); status("source_selection_invalid", s);
        s = selection(); gate(tree(s, "java", "src"), "a".repeat(4097), null); status("source_selection_invalid", s);
    }
    @Test void escapedRootsAndSymlinkedFilesAreRejectedAndCyclesFailSafely() throws Exception {
        var s = selection(); tree(s, "kotlin", "../outside"); status("source_outside_root", s);
        Path outside = Files.createTempDirectory("selection-outside");
        try {
            Path file = outside.resolve("X.kt"); Files.writeString(file, "invalid"); Files.createDirectories(root.resolve("src"));
            Files.createSymbolicLink(root.resolve("src/X.kt"), file);
            s = selection(); gate(tree(s, "kotlin", "src"), "**/*.kt", null); status("source_outside_root", s);
            Files.delete(root.resolve("src/X.kt")); Files.createSymbolicLink(root.resolve("src/cycle"), root.resolve("src"));
            var cycle = s; assertThrows(java.io.IOException.class, () -> GradleSourceSelection.collect(root, cycle));
        } finally { Files.deleteIfExists(outside.resolve("X.kt")); Files.delete(outside); }
    }
    @Test void excessiveEntriesAndGuardsFailBeforeScanning() {
        var s = selection(); for (int i = 0; i < 1025; i++) s.withArray("entries").addObject(); status("source_selection_invalid", s);
        s = selection().put("snapshot", true); for (int i = 0; i < 20001; i++) s.withArray("guard").addObject(); status("source_selection_invalid", s);
    }
}
