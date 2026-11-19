package com.acme.ledger;

public class TransferService {

    public void transfer(Account from, Account to, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        synchronized (from) {
            synchronized (to) {
                if (from.getBalanceCents() < amountCents) {
                    throw new InsufficientFundsException(from.getId());
                }
                from.debit(amountCents);
                to.credit(amountCents);
            }
        }
    }
}
