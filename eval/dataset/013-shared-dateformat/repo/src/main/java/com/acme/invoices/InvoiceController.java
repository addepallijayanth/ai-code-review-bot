package com.acme.invoices;

import java.text.SimpleDateFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceController {

    private static final SimpleDateFormat DUE_DATE = new SimpleDateFormat("yyyy-MM-dd");

    private final InvoiceRepository invoices;

    public InvoiceController(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/api/invoices/{id}")
    public InvoiceView get(@PathVariable long id) {
        Invoice invoice = invoices.require(id);
        return new InvoiceView(invoice.getNumber(), DUE_DATE.format(invoice.getDueDate()), invoice.getTotal());
    }
}
