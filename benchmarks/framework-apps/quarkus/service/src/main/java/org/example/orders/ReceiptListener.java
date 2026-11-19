package org.example.orders;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReceiptListener {
    private volatile String lastReceipt;

    public void onPlaced(@jakarta.enterprise.event.Observes OrderPlaced event) {
        lastReceipt = event.receipt();
    }
    public String lastReceipt() { return lastReceipt; }
}
