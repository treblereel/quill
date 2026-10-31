package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;

@io.quarkus.test.junit.QuarkusTest
class OrderHttpTest {
    @jakarta.inject.Inject @jakarta.inject.Named("card") PaymentGateway gateway;
    @jakarta.inject.Inject DeliveryChannel delivery;
    @jakarta.inject.Inject DiagnosticProbe diagnostic;
    @jakarta.inject.Inject OrderSettings settings;
    @jakarta.inject.Inject ReceiptListener listener;
    @jakarta.inject.Inject UnknownLookup unknown;

    @Test void verifiesRuntimeContract() throws Exception {
        var response = given().post("/orders/A-1");
        int status = response.statusCode();
        String receipt = response.asString();
        assertEquals(200, status);
        assertEquals("test:card:A-1", receipt);
        assertInstanceOf(CardGateway.class, gateway);
        assertEquals("card:A-1", gateway.charge("A-1"));
        assertInstanceOf(TestDelivery.class, delivery);
        assertEquals("test", delivery.name());
        assertTrue(diagnostic.isUnsatisfied());
        assertEquals("test", settings.prefix());
        assertEquals(receipt, listener.lastReceipt());
        assertTrue(unknown.lookup("orders.absent").isEmpty());
        // Record runtime evidence without reading the suite or Quill.
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/runtime-observations.properties"),
            "gateway=" + gateway.charge("A-1") + "\n" +
            "delivery=" + delivery.name() + "\n" +
            "audit_unsatisfied=" + diagnostic.isUnsatisfied() + "\n" +
            "prefix=" + settings.prefix() + "\n" +
            "http_status=" + status + "\n" +
            "http_body=" + receipt + "\n" +
            "event_receipt=" + listener.lastReceipt() + "\n" +
            "missing_dynamic=" + unknown.lookup("orders.absent").isEmpty() + "\n");
    }
}
