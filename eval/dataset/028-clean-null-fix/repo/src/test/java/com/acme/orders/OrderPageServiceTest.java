package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.customers.Customer;
import com.acme.customers.CustomerRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderPageServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final OrderPageService service = new OrderPageService(orders, customers);

    @Test
    void showsTheCustomerName() {
        when(orders.require(1L)).thenReturn(new Order(1L, "A-1", 7L, 1999));
        when(customers.findById(7L)).thenReturn(Optional.of(new Customer(7L, "Ada Lovelace")));

        assertThat(service.page(1L).customerName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void showsDeletedCustomersInsteadOfFailing() {
        when(orders.require(2L)).thenReturn(new Order(2L, "A-2", 8L, 500));
        when(customers.findById(8L)).thenReturn(Optional.empty());

        assertThat(service.page(2L).customerName()).isEqualTo(OrderPageService.DELETED_CUSTOMER);
    }
}
