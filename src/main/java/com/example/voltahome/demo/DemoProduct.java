package com.example.voltahome.demo;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** DEMO ONLY. One row of the product table: the catalogue. Only read. */
@Entity
@Table(name = "product")
class DemoProduct {

    @Id
    private String productCode;
    private String description;
    private BigDecimal price;

    protected DemoProduct() {
        // for JPA
    }

    String productCode() {
        return productCode;
    }

    String description() {
        return description;
    }

    BigDecimal price() {
        return price;
    }
}
