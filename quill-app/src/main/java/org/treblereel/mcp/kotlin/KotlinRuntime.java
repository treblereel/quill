package org.treblereel.mcp.kotlin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.treblereel.mcp.QuillTopCommand;

/** One pinned compiler runtime for every build system. Only preparation performs resolution. */
public final class KotlinRuntime {
    private KotlinRuntime() {}

    public static Path cacheRoot() {
        return Path.of(System.getProperty("quill.kotlin.cache",
                Path.of(System.getProperty("user.home"), ".quill", "kotlin").toString()));
    }

    public static String version() { return QuillTopCommand.property("kotlin-version"); }

    /** Shared across projects and processes; a manifest is published only after every JAR exists. */
    public static synchronized List<Path> prepare() throws IOException {
        checkJava();
        Path directory = directory(cacheRoot());
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve("runtime.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            List<Path> cached = read(directory);
            if (!cached.isEmpty()) return cached;
            List<Path> dependencies = resolve();
            return publish(directory, dependencies);
        }
    }

    /** Never contacts a repository, creates files, or runs Maven/Gradle. */
    public static List<Path> available() {
        try { return read(directory(cacheRoot())); }
        catch (IOException | RuntimeException failure) { return List.of(); }
    }

    static String workerIdentity() throws IOException {
        Path manifest = directory(cacheRoot()).resolve("classpath.properties");
        var attributes = Files.readAttributes(manifest, java.nio.file.attribute.BasicFileAttributes.class);
        return manifest.toAbsolutePath() + ":" + attributes.lastModifiedTime() + ":" + attributes.size()
                + ":" + attributes.fileKey() + ":" + javaExecutable();
    }

    public static List<String> workerCommand() throws IOException {
        List<Path> files = available();
        if (files.isEmpty()) throw new IOException("K2 runtime is missing or damaged; run quill init");
        return List.of(javaExecutable(), "-Xmx512m", "-cp",
                String.join(File.pathSeparator, files.stream().map(Path::toString).toList()),
                "-Xss8m", "org.treblereel.mcp.kotlin.SemanticWorker");
    }

    static String javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String home = System.getenv("JAVA_HOME");
        if (home != null && Files.isRegularFile(Path.of(home, "bin", name))) return Path.of(home, "bin", name).toString();
        home = System.getProperty("java.home");
        if (home != null && Files.isRegularFile(Path.of(home, "bin", name))) return Path.of(home, "bin", name).toString();
        return name;
    }

    private static void checkJava() throws IOException {
        Process process = new ProcessBuilder(javaExecutable(), "-version").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Java version check timed out");
            String output = new String(process.getInputStream().readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || javaMajor(output) < 17) throw new IOException("K2 requires Java 17 or newer; check JAVA_HOME or PATH");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Java version check interrupted", interrupted);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }

    static int javaMajor(String output) {
        var match = java.util.regex.Pattern.compile("(?:openjdk|java) version \\\"(\\d+)").matcher(output);
        if (!match.find()) return -1;
        try { return Integer.parseInt(match.group(1)); }
        catch (NumberFormatException malformed) { return -1; }
    }

    static Path directory(Path root) throws IOException {
        try (InputStream worker = KotlinRuntime.class.getResourceAsStream("/kotlin-worker/worker.jar")) {
            if (worker == null) throw new IOException("Distribution is missing its Kotlin worker");
            String hash = digest(worker.readAllBytes());
            return root.toAbsolutePath().normalize().resolve(version()).resolve(hash);
        }
    }

    static List<Path> publish(Path directory, List<Path> dependencies) throws IOException {
        if (dependencies.isEmpty()) throw new IOException("Empty compiler dependency graph");
        Path worker = directory.resolve("worker.jar");
        try (InputStream source = KotlinRuntime.class.getResourceAsStream("/kotlin-worker/worker.jar")) {
            if (source == null) throw new IOException("Distribution is missing its Kotlin worker");
            atomicWrite(worker, source.readAllBytes());
        }
        List<Path> files = new ArrayList<>(); files.add(worker); files.addAll(dependencies);
        Properties manifest = new Properties();
        manifest.setProperty("version", version());
        manifest.setProperty("count", Integer.toString(files.size()));
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i).toAbsolutePath().normalize();
            manifest.setProperty("path." + i, file.toString());
            manifest.setProperty("sha256." + i, digest(file, "SHA-256"));
        }
        var bytes = new java.io.ByteArrayOutputStream(); manifest.store(bytes, "Quill K2 runtime");
        atomicWrite(directory.resolve("classpath.properties"), bytes.toByteArray());
        return List.copyOf(files);
    }

    static List<Path> read(Path directory) throws IOException {
        Path file = directory.resolve("classpath.properties");
        if (!Files.isRegularFile(file)) return List.of();
        Properties manifest = new Properties();
        try (var input = Files.newInputStream(file)) { manifest.load(input); }
        try {
            if (!version().equals(manifest.getProperty("version"))) return List.of();
            int count = Integer.parseInt(manifest.getProperty("count"));
            if (count < 2 || count > 100) return List.of();
            List<Path> paths = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Path path = Path.of(manifest.getProperty("path." + i));
                if (!Files.isRegularFile(path)
                        || !digest(path, "SHA-256").equals(manifest.getProperty("sha256." + i))) return List.of();
                paths.add(path);
            }
            if (!paths.getFirst().equals(directory.resolve("worker.jar"))) return List.of();
            return List.copyOf(paths);
        } catch (RuntimeException malformed) { return List.of(); }
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".runtime-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String digest(byte[] bytes) throws IOException {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var hash = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[16 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    static List<Path> resolve() throws IOException {
        Settings settings = settings();
        var system = new RepositorySystemSupplier().get();
        try {
            var session = MavenRepositorySystemUtils.newSession();
            session.setOffline(settings.isOffline() || Boolean.getBoolean("quill.kotlin.offline"));
            session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
            session.setConfigProperty("aether.connector.connectTimeout", 10_000);
            session.setConfigProperty("aether.connector.requestTimeout", 30_000);
            session.setSystemProperties(System.getProperties());
            String local = System.getProperty("quill.kotlin.localRepository",
                    System.getProperty("maven.repo.local", settings.getLocalRepository() == null
                            ? Path.of(System.getProperty("user.home"), ".m2", "repository").toString()
                            : expand(settings.getLocalRepository())));
            session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(local)));
            var mirrors = new DefaultMirrorSelector();
            settings.getMirrors().forEach(m -> mirrors.add(m.getId(), expand(m.getUrl()), m.getLayout(), false,
                    m.isBlocked(), m.getMirrorOf(), m.getMirrorOfLayouts()));
            session.setMirrorSelector(mirrors);
            var authentication = new DefaultAuthenticationSelector();
            settings.getServers().forEach(s -> authentication.add(s.getId(), new AuthenticationBuilder()
                    .addUsername(expand(s.getUsername())).addPassword(expand(s.getPassword())).build()));
            session.setAuthenticationSelector(authentication);
            var proxies = new DefaultProxySelector();
            settings.getProxies().stream().filter(org.apache.maven.settings.Proxy::isActive).forEach(p -> proxies.add(
                    new org.eclipse.aether.repository.Proxy(p.getProtocol(), p.getHost(), p.getPort(),
                            new AuthenticationBuilder().addUsername(expand(p.getUsername())).addPassword(expand(p.getPassword())).build()),
                    p.getNonProxyHosts()));
            session.setProxySelector(proxies);
            var repository = new RemoteRepository.Builder("central", "default",
                    System.getProperty("quill.kotlin.repository", "https://repo.maven.apache.org/maven2/"))
                    .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER,
                            RepositoryPolicy.CHECKSUM_POLICY_FAIL)).build();
            var collect = new CollectRequest();
            collect.setRepositories(system.newResolutionRepositories(session, List.of(repository)));
            collect.addDependency(new Dependency(new DefaultArtifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:" + version()), "runtime"));
            collect.addDependency(new Dependency(new DefaultArtifact("org.jetbrains.kotlin:kotlin-stdlib:" + version()), "runtime"));
            collect.addDependency(new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-databind:"
                    + QuillTopCommand.property("jackson-version")), "runtime"));
            List<Path> files = system.resolveDependencies(session, new DependencyRequest(collect,
                    DependencyFilterUtils.classpathFilter("runtime"))).getArtifactResults().stream()
                    .map(result -> result.getArtifact().getFile().toPath().toAbsolutePath()).distinct().toList();
            for (Path file : files) {
                try (var jar = new java.util.jar.JarFile(file.toFile())) {
                    if (!jar.entries().hasMoreElements()) throw new IOException("Empty runtime artifact");
                }
                Path checksum = Path.of(file + ".sha1");
                if (Files.isRegularFile(checksum)) {
                    String expected = Files.readString(checksum).strip().split("\\s+")[0];
                    String actual = digest(file, "SHA-1");
                    if (!actual.equalsIgnoreCase(expected)) throw new IOException("Cached runtime artifact checksum mismatch");
                }
            }
            return files;
        } catch (Exception failure) {
            // Repository errors can contain URLs or credentials; do not echo their messages.
            throw new IOException("Could not resolve K2 " + version()
                    + "; check repository access, settings and offline cache", failure);
        } finally { system.shutdown(); }
    }

    static Settings settings() throws IOException {
        Path path = Path.of(System.getProperty("quill.kotlin.settings",
                Path.of(System.getProperty("user.home"), ".m2", "settings.xml").toString()));
        if (!Files.isRegularFile(path)) {
            if (System.getProperty("quill.kotlin.settings") != null) throw new IOException("Configured Maven settings file is missing");
            return new Settings();
        }
        try (var reader = Files.newBufferedReader(path)) { return new SettingsXpp3Reader().read(reader); }
        catch (Exception failure) { throw new IOException("Could not read Maven settings for K2", failure); }
    }

    static String expand(String value) {
        if (value == null) return null;
        var pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)}");
        var match = pattern.matcher(value); var result = new StringBuilder();
        while (match.find()) {
            String key = match.group(1);
            String replacement = key.startsWith("env.") ? System.getenv(key.substring(4)) : System.getProperty(key);
            match.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement == null ? match.group() : replacement));
        }
        return match.appendTail(result).toString();
    }
}
