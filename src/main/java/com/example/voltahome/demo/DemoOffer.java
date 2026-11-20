package com.example.voltahome.demo;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * DEMO ONLY. One row of the offer table. Its status is changed by a conditional update query
 * (DemoOfferRepository.accept), never by saving this object.
 */
@Entity
@Table(name = "offer")
class DemoOffer {

    @Id
    private String offerId;
    private String customerId;
    private String description;
    private BigDecimal pricePerKwh;
    private String status;

    protected DemoOffer() {
        // for JPA
    }

    String offerId() {
        return offerId;
    }

    String description() {
        return description;
    }

    BigDecimal pricePerKwh() {
        return pricePerKwh;
    }

    String status() {
        return status;
    }

    boolean isOpen() {
        return "OPEN".equals(status);
    }
}
