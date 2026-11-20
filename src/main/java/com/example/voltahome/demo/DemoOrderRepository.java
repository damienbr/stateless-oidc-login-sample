package com.example.voltahome.demo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** DEMO ONLY. Orders. The only read is limited to the customer: another customer's order is never found. */
interface DemoOrderRepository extends JpaRepository<DemoOrder, String> {

    Optional<DemoOrder> findByOrderIdAndCustomerId(String orderId, String customerId);
}
