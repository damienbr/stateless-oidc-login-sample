package com.example.voltahome.customer;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Stands in for the application's existing customer data. Read only: Repository has no save(). */
public interface CustomerRepository extends Repository<Customer, String> {

    @Query("select c from Customer c where c.customerId = :customerId and c.active = true")
    Optional<Customer> findActiveById(String customerId);
}
