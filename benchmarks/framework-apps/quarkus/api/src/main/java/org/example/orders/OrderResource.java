package org.example.orders;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/orders")
public class OrderResource {
    private final OrderService orders;
    @Inject
    public OrderResource(OrderService orders) { this.orders = orders; }
    @POST
    @Path("/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String place(@PathParam("id") String id) { return orders.place(id); }
}
