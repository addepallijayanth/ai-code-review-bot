package com.acme.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository payments;

    public RefundService(PaymentGateway gateway, PaymentRepository payments) {
        this.gateway = gateway;
        this.payments = payments;
    }

    public RefundResult refundInFull(Payment payment) {
        return refund(payment, payment.getAmountCents());
    }

    public RefundResult refund(Payment payment, long amountCents) {
        if (amountCents <= 0 || amountCents > payment.getRefundableCents()) {
            return RefundResult.rejected(payment.getId(), "amount out of range");
        }
        try {
            gateway.refund(payment.getGatewayId(), amountCents);
        } catch (GatewayException e) {
            log.debug("refund failed", e);
        }
        payment.markRefunded(amountCents);
        payments.save(payment);
        return RefundResult.success(payment.getId());
    }
}
