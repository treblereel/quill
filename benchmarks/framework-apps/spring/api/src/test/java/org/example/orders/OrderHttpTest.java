package org.example.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class OrderHttpTest {
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.beans.factory.annotation.Qualifier("card") PaymentGateway gateway;
    @org.springframework.beans.factory.annotation.Autowired DeliveryChannel delivery;
    @org.springframework.beans.factory.annotation.Autowired DiagnosticProbe diagnostic;
    @org.springframework.beans.factory.annotation.Autowired OrderSettings settings;
    @org.springframework.beans.factory.annotation.Autowired ReceiptListener listener;
    @org.springframework.beans.factory.annotation.Autowired UnknownLookup unknown;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.boot.test.web.client.TestRestTemplate http;

    @Test void verifiesRuntimeContract() throws Exception {
        var response = http.postForEntity("/orders/A-1", null, String.class);
        int status = response.getStatusCode().value();
        String receipt = response.getBody();
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
