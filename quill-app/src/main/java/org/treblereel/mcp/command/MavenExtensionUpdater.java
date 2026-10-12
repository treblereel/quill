package org.treblereel.mcp.command;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.eclipse.aether.util.version.GenericVersionScheme;

/** Refreshes an existing Maven integration before the MCP server accepts requests. */
public final class MavenExtensionUpdater {
    private MavenExtensionUpdater() {}

    record Entry(String version, int versionStart, int versionEnd) {
        String update(String content, String version) {
            return content.substring(0, versionStart) + version + content.substring(versionEnd);
        }
    }

    private static final Pattern IGNORED = Pattern.compile("(?s)<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>");
    private static final Pattern EXTENSION = Pattern.compile(
            "(?s)<((?:[\\w.-]+:)?extension)(?:\\s[^>]*)?>.*?</\\1\\s*>");
    private static final Pattern VERSION = Pattern.compile(
            "(?s)<(?:[\\w.-]+:)?version(?:\\s[^>]*)?>([^<]*)</(?:[\\w.-]+:)?version\\s*>");

    public static void upgrade(Path project) {
        try {
            if (!Files.isRegularFile(project.resolve("pom.xml"))) return;
            Path file = existingConfiguration(project);
            if (file == null) return;
            synchronized (MavenExtensionUpdater.class) {
                String content = Files.readString(file);
                Entry entry = entry(content);
                String required = BuildIntegrationInstaller.mavenExtensionVersion();
                if (entry == null || required == null || entry.version().equals(required)) return;
                // Expressions and ranges cannot be compared without evaluating Maven's model.
                if (!entry.version().matches("[0-9][A-Za-z0-9._+-]*") || entry.version().contains("${")
                        || entry.version().matches(".*[\\[\\](),].*")) {
                    throw new IOException("Quill extension version must be a concrete version");
                }
                org.eclipse.aether.version.VersionScheme scheme = new GenericVersionScheme();
                if (scheme.parseVersion(entry.version()).compareTo(scheme.parseVersion(required)) >= 0) return;
                if (!Files.readString(file).equals(content)) throw new IOException("extensions.xml changed during update");
                BuildIntegrationInstaller.atomicWrite(file, entry.update(content, required));
                System.err.println("[quill] Updated Maven extension " + entry.version() + " -> "
                        + required + " in " + file + "; applies to the next Maven invocation");
            }
        } catch (Exception failure) {
            System.err.println("[quill] Could not update Maven extension for " + project + ": " + failure.getMessage());
        }
    }

    private static Path existingConfiguration(Path project) throws IOException {
        for (Path directory = project.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
            Path maven = directory.resolve(".mvn");
            // Maven uses the nearest .mvn directory; do not modify a different ancestor's integration.
            if (Files.isDirectory(maven)) {
                Path file = maven.resolve("extensions.xml");
                return Files.isRegularFile(file) ? file.toRealPath() : null;
            }
        }
        return null;
    }

    static Entry entry(String content) throws IOException {
        // No external entities or DTD expansion is needed for an extensions descriptor.
        if (content.contains("<!DOCTYPE")) throw new IOException("DOCTYPE is not supported in extensions.xml");
        if (content.contains("quill:build-integration:start") != content.contains("quill:build-integration:end"))
            throw new IOException("Incomplete Quill-managed extension block");
        Xpp3Dom document = parse(content);
        if (!local(document.getName()).equals("extensions")) throw new IOException("Expected extensions root");
        int expected = 0;
        for (var extension : document.getChildren()) {
            if (!local(extension.getName()).equals("extension")) throw new IOException("Unexpected extensions element");
            for (var child : extension.getChildren()) {
                String name = local(child.getName());
                if (!name.equals("groupId") && !name.equals("artifactId") && !name.equals("version"))
                    throw new IOException("Unexpected extension element " + name);
            }
            if (isQuill(extension)) expected++;
        }
        if (expected > 1) throw new IOException("Duplicate Quill Maven extensions");
        if (expected == 0) return null;
        String searchable = IGNORED.matcher(content).replaceAll(match -> " ".repeat(match.group().length()));
        var extensions = EXTENSION.matcher(searchable);
        while (extensions.find()) {
            Xpp3Dom extension = parse(content.substring(extensions.start(), extensions.end()));
            if (!isQuill(extension)) continue;
            String version = value(extension, "version");
            var versions = VERSION.matcher(searchable.substring(extensions.start(), extensions.end()));
            if (version == null || !versions.find()) throw new IOException("Quill extension version is missing");
            int start = extensions.start() + versions.start(1);
            int end = extensions.start() + versions.end(1);
            if (content.substring(start, end).contains("<"))
                throw new IOException("Markup inside Quill extension version is not supported");
            // Preserve surrounding whitespace and all unrelated XML exactly.
            while (start < end && Character.isWhitespace(content.charAt(start))) start++;
            while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
            return new Entry(version.trim(), start, end);
        }
        throw new IOException("Could not locate Quill extension version");
    }

    private static Xpp3Dom parse(String content) throws IOException {
        try { return Xpp3DomBuilder.build(new StringReader(content)); }
        catch (Exception failure) { throw new IOException("Invalid extensions.xml", failure); }
    }

    private static boolean isQuill(Xpp3Dom element) throws IOException {
        return "org.treblereel.mcp".equals(value(element, "groupId"))
                && "quill-maven-extension".equals(value(element, "artifactId"));
    }

    private static String value(Xpp3Dom element, String name) throws IOException {
        String result = null;
        boolean found = false;
        for (var child : element.getChildren()) if (local(child.getName()).equals(name)) {
            if (found || child.getChildCount() != 0) throw new IOException("Invalid extension " + name);
            found = true;
            result = child.getValue() == null ? "" : child.getValue().trim();
        }
        return result;
    }

    private static String local(String name) { return name.substring(name.indexOf(':') + 1); }
}
