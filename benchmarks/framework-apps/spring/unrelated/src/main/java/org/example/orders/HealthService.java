package org.example.orders;

public class HealthService {
    public String status() { return "healthy"; }
    public String place(String id) { return "preview:" + id; }
}
