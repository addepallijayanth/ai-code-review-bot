package com.acme.shipping;

import com.acme.customers.Customer;
import com.acme.customers.CustomerRepository;
import com.acme.orders.Order;
import com.acme.orders.OrderNotFoundException;
import com.acme.orders.OrderRepository;

public class LabelService {

    private final OrderRepository orders;
    private final CustomerRepository customers;

    public LabelService(OrderRepository orders, CustomerRepository customers) {
        this.orders = orders;
        this.customers = customers;
    }

    public String trackingNumber(long orderId) {
        return orders.findById(orderId)
                .map(Order::getTrackingNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public ShippingLabel label(long orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        Customer customer = customers.findById(order.getCustomerId()).orElse(null);
        String recipient = customer.getFullName();
        return new ShippingLabel(recipient, customer.getAddress().format(), order.getTrackingNumber());
    }
}
