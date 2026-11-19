package com.acme.pricing;

import java.util.List;

public class PriceCalculator {

    static final long MAX_VOLUME_DISCOUNT_CENTS = 20_000;

    public long subtotalCents(List<LineItem> items) {
        return items.stream().mapToLong(item -> item.unitPriceCents() * item.quantity()).sum();
    }

    public long volumeDiscountCents(long subtotalCents, int units) {
        int percent;
        if (units >= 50) {
            percent = 10;
        } else if (units >= 10) {
            percent = 5;
        } else {
            percent = 0;
        }
        long discount = subtotalCents * percent / 100;
        return Math.min(discount, MAX_VOLUME_DISCOUNT_CENTS);
    }

    public long totalCents(List<LineItem> items) {
        long subtotal = subtotalCents(items);
        int units = items.stream().mapToInt(LineItem::quantity).sum();
        return subtotal - volumeDiscountCents(subtotal, units);
    }
}
