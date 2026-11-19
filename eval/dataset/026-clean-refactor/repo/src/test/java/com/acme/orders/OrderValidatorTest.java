package com.acme.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    @Test
    void acceptsAValidOrder() {
        OrderRequest request = new OrderRequest(List.of(new Item("sku-1", 1)), new Address("1 Main St", "94105", "US"));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void reportsEveryAddressProblem() {
        OrderRequest request = new OrderRequest(List.of(new Item("sku-1", 1)), new Address(" ", "!", "USA"));
        assertThat(validator.validate(request)).containsExactly(
                "street is required", "postal code is invalid", "country must be a two-letter code");
    }

    @Test
    void requiresAnAddress() {
        OrderRequest request = new OrderRequest(List.of(), null);
        assertThat(validator.validate(request)).containsExactly("order has no items", "shipping address is required");
    }
}
