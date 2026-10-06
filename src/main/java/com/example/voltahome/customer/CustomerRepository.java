package com.example.voltahome.customer;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stands in for the application's existing customer data. */
@Repository
public class CustomerRepository {

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findActiveById(String customerId) {
        return jdbc.sql("SELECT * FROM customer WHERE customer_id = :id AND active = TRUE")
                .param("id", customerId)
                .query((rs, n) -> map(rs.getString("customer_id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getBoolean("active")))
                .optional();
    }

    private static Customer map(String id, String email, String name, boolean active) {
        return new Customer(id, email, name, active);
    }
}
