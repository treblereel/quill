package org.example.orders;

import org.springframework.stereotype.Component;

@Component
public class ReceiptListener {
    private volatile String lastReceipt;
    @org.springframework.context.event.EventListener
    public void onPlaced(OrderPlaced event) {
        lastReceipt = event.receipt();
    }
    public String lastReceipt() { return lastReceipt; }
}
