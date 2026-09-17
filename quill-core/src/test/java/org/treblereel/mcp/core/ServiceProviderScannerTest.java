package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceProviderScannerTest {

    @TempDir Path tempDir;

    @Test
    void readsOrderedProjectLocalProvidersAndComments() throws Exception {
        Path module = tempDir.resolve("provider-module");
        Path descriptor = module.resolve(
                "src/main/resources/META-INF/services/example.PaymentService");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, """
                # preferred provider
                example.StripeProvider

                example.CashProvider # fallback
                """);

        ServiceProviderScanner.Result result =
                ServiceProviderScanner.scan(tempDir, List.of(module));

        assertEquals(1, result.descriptorCount());
        assertEquals(List.of(
                new ServiceProviderScanner.Registration(
                        "example.PaymentService", "example.StripeProvider",
                        "provider-module/src/main/resources/META-INF/services/example.PaymentService",
                        2),
                new ServiceProviderScanner.Registration(
                        "example.PaymentService", "example.CashProvider",
                        "provider-module/src/main/resources/META-INF/services/example.PaymentService",
                        4)), result.registrations());
    }

    @Test
    void resolvesInternalAndExternalServicesAndDeduplicatesProviders() {
        ServiceProviderScanner.Result result = new ServiceProviderScanner.Result(List.of(
                new ServiceProviderScanner.Registration(
                        "example.InternalService", "example.Provider", "first", 1),
                new ServiceProviderScanner.Registration(
                        "example.InternalService", "example.Provider", "second", 1),
                new ServiceProviderScanner.Registration(
                        "external.Service", "example.Provider", "third", 1),
                new ServiceProviderScanner.Registration(
                        "example.InternalService", "missing.Provider", "fourth", 1)), 4);

        ServiceProviderScanner.ResolvedDependencies resolved =
                ServiceProviderScanner.resolve(result, Map.of(
                        "example.Provider", 1,
                        "example.InternalService", 2));

        assertEquals(1, resolved.internal().size());
        assertEquals("SERVICE_PROVIDES", resolved.internal().getFirst().kind());
        assertEquals(1, resolved.internal().getFirst().fromClassId());
        assertEquals(2, resolved.internal().getFirst().toClassId());
        assertEquals(1, resolved.external().size());
        assertEquals("external.Service", resolved.external().getFirst().externalType());
        assertEquals("SERVICE_PROVIDES", resolved.external().getFirst().usageKind());
    }
}
