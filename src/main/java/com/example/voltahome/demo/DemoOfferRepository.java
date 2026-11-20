package com.example.voltahome.demo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** DEMO ONLY. Offers. Every query is limited to the customer: another customer's offer is never found. */
@Transactional
interface DemoOfferRepository extends Repository<DemoOffer, String> {

    Optional<DemoOffer> findByOfferIdAndCustomerId(String offerId, String customerId);

    List<DemoOffer> findByCustomerIdAndStatusOrderByOfferId(String customerId, String status);

    /**
     * Accepts the offer if it is this customer's and still open. A single conditional UPDATE: the number of rows
     * changed (1 or 0) tells whether the offer was accepted, and two concurrent calls cannot both get 1.
     */
    @Modifying
    @Query("""
            update DemoOffer o set o.status = 'ACCEPTED'
            where o.offerId = :offerId and o.customerId = :customerId and o.status = 'OPEN'
            """)
    int accept(String offerId, String customerId);
}
