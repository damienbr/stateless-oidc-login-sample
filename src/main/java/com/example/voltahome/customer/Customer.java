package com.example.voltahome.customer;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row of the customer table. Stands in for the application's existing customer data; only read here. */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private String customerId;
    private String email;
    private String displayName;
    private boolean active;

    protected Customer() {
        // for JPA
    }

    public String customerId() {
        return customerId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public boolean active() {
        return active;
    }
}
