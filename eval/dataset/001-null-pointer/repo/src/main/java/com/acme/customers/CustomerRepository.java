package com.acme.customers;

import java.util.Optional;

public interface CustomerRepository {

    /** Empty for guest checkouts and for customers who deleted their account. */
    Optional<Customer> findById(long id);
}
