package com.acme.orders;

import java.util.ArrayList;
import java.util.List;

public class OrderValidator {

    public List<String> validate(OrderRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.items().isEmpty()) {
            errors.add("order has no items");
        }
        errors.addAll(validateAddress(request.shippingAddress()));
        return errors;
    }

    public List<String> validateAddress(Address address) {
        List<String> errors = new ArrayList<>();
        if (address == null) {
            errors.add("shipping address is required");
            return errors;
        }
        if (address.street() == null || address.street().isBlank()) {
            errors.add("street is required");
        }
        if (address.postalCode() == null || !address.postalCode().matches("[0-9A-Za-z -]{3,10}")) {
            errors.add("postal code is invalid");
        }
        if (address.country() == null || address.country().length() != 2) {
            errors.add("country must be a two-letter code");
        }
        return errors;
    }
}
