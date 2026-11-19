package com.acme.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator();

    @Test
    void addsUpLineItems() {
        List<LineItem> items = List.of(new LineItem("A", 250, 2), new LineItem("B", 1000, 1));

        assertThat(calculator.subtotalCents(items)).isEqualTo(1500);
    }

    @Test
    void emptyOrderCostsNothing() {
        assertThat(calculator.subtotalCents(List.of())).isZero();
    }
}
