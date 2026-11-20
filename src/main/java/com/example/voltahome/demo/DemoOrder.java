package com.example.voltahome.demo;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** DEMO ONLY. One row of the customer_order table. An order is created by its confirmation, never before. */
@Entity
@Table(name = "customer_order")
class DemoOrder {

    @Id
    private String orderId;
    private String customerId;
    private String description;
    private BigDecimal amount;
    private String status;

    protected DemoOrder() {
        // for JPA
    }

    DemoOrder(String orderId, String customerId, String description, BigDecimal amount, String status) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.description = description;
        this.amount = amount;
        this.status = status;
    }

    String orderId() {
        return orderId;
    }

    String description() {
        return description;
    }

    BigDecimal amount() {
        return amount;
    }

    String status() {
        return status;
    }
}
