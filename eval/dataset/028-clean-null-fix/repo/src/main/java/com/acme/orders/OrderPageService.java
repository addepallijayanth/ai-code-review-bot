package com.acme.orders;

import com.acme.customers.Customer;
import com.acme.customers.CustomerRepository;

public class OrderPageService {

    static final String DELETED_CUSTOMER = "Deleted customer";

    private final OrderRepository orders;
    private final CustomerRepository customers;

    public OrderPageService(OrderRepository orders, CustomerRepository customers) {
        this.orders = orders;
        this.customers = customers;
    }

    public OrderPage page(long orderId) {
        Order order = orders.require(orderId);
        String customerName = customers.findById(order.getCustomerId())
                .map(Customer::getFullName)
                .orElse(DELETED_CUSTOMER);
        return new OrderPage(order.getNumber(), customerName, order.getTotalCents());
    }
}
