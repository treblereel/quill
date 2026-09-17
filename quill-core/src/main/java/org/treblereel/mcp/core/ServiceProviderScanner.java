package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;

/** Reads project-local {@code META-INF/services} provider registrations. */
public final class ServiceProviderScanner {

    private static final Path SERVICES_PATH = Path.of("src", "main", "resources", "META-INF", "services");

    private ServiceProviderScanner() {}

    public record Registration(
            String serviceType, String providerType, String descriptorPath, int line) {}

    public record Result(List<Registration> registrations, int descriptorCount) {}

    public record ResolvedDependencies(
            List<DependencyRecord> internal, List<ExternalDepRecord> external) {}

    public static Result scan(Path projectRoot, List<Path> moduleDirectories) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Set<Path> descriptors = new LinkedHashSet<>();
        for (Path module : moduleDirectories) {
            Path services = module.toAbsolutePath().normalize().resolve(SERVICES_PATH);
            if (!Files.isDirectory(services)) continue;
            try (Stream<Path> files = Files.walk(services)) {
                files.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(descriptors::add);
            } catch (IOException error) {
                throw new RuntimeException("Failed to inspect service descriptors under " + services, error);
            }
        }

        List<Registration> registrations = new ArrayList<>();
        for (Path descriptor : descriptors) {
            String serviceType = descriptor.getFileName().toString();
            List<String> lines;
            try {
                lines = Files.readAllLines(descriptor, StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new RuntimeException("Failed to read service descriptor " + descriptor, error);
            }
            String descriptorPath = descriptor.startsWith(root)
                    ? normalize(root.relativize(descriptor)) : normalize(descriptor);
            for (int index = 0; index < lines.size(); index++) {
                String provider = stripComment(lines.get(index)).strip();
                if (!provider.isEmpty()) {
                    registrations.add(new Registration(
                            serviceType, provider, descriptorPath, index + 1));
                }
            }
        }
        registrations.sort(Comparator.comparing(Registration::descriptorPath)
                .thenComparingInt(Registration::line));
        return new Result(List.copyOf(registrations), descriptors.size());
    }

    public static ResolvedDependencies resolve(
            Result result, Map<String, Integer> applicationClassIds) {
        Set<String> unique = new LinkedHashSet<>();
        List<DependencyRecord> internal = new ArrayList<>();
        List<ExternalDepRecord> external = new ArrayList<>();
        for (Registration registration : result.registrations()) {
            Integer provider = applicationClassIds.get(registration.providerType());
            if (provider == null) continue;
            if (!unique.add(registration.providerType() + "\n" + registration.serviceType())) {
                continue;
            }
            Integer service = applicationClassIds.get(registration.serviceType());
            if (service != null) {
                internal.add(new DependencyRecord(
                        provider, service, "SERVICE_PROVIDES", null, 1));
            } else {
                external.add(new ExternalDepRecord(
                        provider, registration.serviceType(), "SERVICE_PROVIDES"));
            }
        }
        return new ResolvedDependencies(List.copyOf(internal), List.copyOf(external));
    }

    private static String stripComment(String value) {
        int comment = value.indexOf('#');
        return comment >= 0 ? value.substring(0, comment) : value;
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
